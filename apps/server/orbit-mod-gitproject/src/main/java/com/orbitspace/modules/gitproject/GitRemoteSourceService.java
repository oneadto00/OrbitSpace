package com.orbitspace.modules.gitproject;

import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.ControlledEgressGitPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.MembershipRevocationHook;
import com.orbitspace.kernel.RevocationLedgerPort;
import com.orbitspace.kernel.SpaceAuthorizationPort.Membership;
import com.orbitspace.kernel.SpaceRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class GitRemoteSourceService implements MembershipRevocationHook {
    record Source(String sourceId, String spaceId, String projectId, String httpsUrl, boolean credentialConfigured) {}
    record Receipt(String jobId) {}
    private record Grant(String id, String project, String url, String owner, long ownerEpoch, boolean active,
                         long version, Integer keyVersion, byte[] secret, String jobId, long actorEpoch) {}
    private static final String PURGE_PREFIX = "git-source:";
    private final JdbcTemplate db;
    private final ObjectProvider<ControlledEgressGitPort> gateway;
    private final AsyncJobPort jobs;
    private final GitRemoteSecrets secrets;
    private final GitProjectIdempotencyStore idempotency;
    private final RevocationLedgerPort ledger;

    GitRemoteSourceService(JdbcTemplate db, ObjectProvider<ControlledEgressGitPort> gateway, AsyncJobPort jobs,
                           GitRemoteSecrets secrets, GitProjectIdempotencyStore idempotency, RevocationLedgerPort ledger) {
        this.db = db;
        this.gateway = gateway;
        this.jobs = jobs;
        this.secrets = secrets;
        this.idempotency = idempotency;
        this.ledger = ledger;
    }

    Membership authorize(String space, String project, String user, long epoch, SpaceRole minimum) {
        return db.query("""
                SELECT m.role,m.perm_version FROM space_members m JOIN spaces s ON s.id=m.space_id
                JOIN git_projects p ON p.space_id=m.space_id
                WHERE m.space_id=? AND p.id=? AND m.user_id=? AND m.membership_epoch=?
                  AND s.status='ACTIVE' AND p.status='ACTIVE' AND m.status='ACTIVE' FOR SHARE
                """, (rs, n) -> new Membership(space, user, SpaceRole.valueOf(rs.getString(1)), epoch, rs.getLong(2)),
                space, project, user, epoch).stream().filter(m -> m.role().atLeast(minimum)).findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.AUTH_FORBIDDEN));
    }

    private ControlledEgressGitPort gateway() {
        var port = gateway.getIfAvailable();
        if (port == null) throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        return port;
    }

    @Transactional
    public Source bind(String space, String project, String user, long epoch, String key, String url, String token) {
        authorize(space, project, user, epoch, SpaceRole.MEMBER);
        String canonical = gateway().normalize(url).toASCIIString();
        if (canonical.length() > 2048) throw new DomainException(KernelErrorCode.REMOTE_URL_FORBIDDEN);
        if (token != null && (token.isBlank() || token.length() > 4096 || token.chars().anyMatch(Character::isISOControl))) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return idempotency.execute("REMOTE_BIND", project, user, key, List.of(epoch, canonical, token == null ? "" : token),
                Source.class, () -> {
                    String id = GitProjectUtils.newId();
                    var sealed = token == null ? null : secrets.seal(id, canonical, token);
                    db.update("""
                            INSERT INTO git_sources(id,space_id,project_id,https_url,owner_user_id,owner_epoch,
                                key_version,token_ciphertext) VALUES (?,?,?,?,?,?,?,?)
                            """, id, space, project, canonical, user, epoch,
                            sealed == null ? null : sealed.keyVersion(), sealed == null ? null : sealed.ciphertext());
                    return new Source(id, space, project, canonical, token != null);
                });
    }

    @Transactional
    public List<Source> list(String space, String project, String user, long epoch) {
        authorize(space, project, user, epoch, SpaceRole.VIEWER);
        return db.query("""
                SELECT id,https_url,key_version IS NOT NULL FROM git_sources
                WHERE space_id=? AND project_id=? AND status='ACTIVE' ORDER BY id
                """, (rs, n) -> new Source(rs.getString(1), space, project, rs.getString(2), rs.getBoolean(3)), space, project);
    }

    private Grant lock(String space, String id) {
        return db.query("""
                SELECT id,project_id,https_url,owner_user_id,owner_epoch,status,lifecycle_version,
                       key_version,token_ciphertext,active_job_id,active_actor_epoch
                FROM git_sources WHERE space_id=? AND id=? FOR UPDATE
                """, (rs, n) -> new Grant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5),"ACTIVE".equals(rs.getString(6)), rs.getLong(7), rs.getObject(8, Integer.class),
                rs.getBytes(9), rs.getString(10), rs.getLong(11)), space, id).stream().findFirst().orElse(null);
    }

    private Grant require(String space, String project, String id) {
        Grant source = lock(space, id);
        if (source == null || !source.project().equals(project)) throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        return source;
    }

    @Transactional
    public Receipt sync(String space, String project, String id, String user, long epoch, String key) {
        Membership actor = authorize(space, project, user, epoch, SpaceRole.MEMBER);
        Grant source = require(space, project, id);
        if (!source.active()) throw new DomainException(KernelErrorCode.AUTH_REVOKED);
        checkOwner(space, source);
        return idempotency.execute("REMOTE_SYNC", id, user, key, List.of(epoch, source.version()), Receipt.class, () -> {
            if (source.jobId() != null) jobs.cancel(source.jobId());
            String jobId = gateway().enqueueSync(actor, project, id, source.version(), key);
            db.update("UPDATE git_sources SET active_job_id=?,active_actor_epoch=? WHERE id=?", jobId, epoch, id);
            return new Receipt(jobId);
        });
    }

    private void checkOwner(String space, Grant source) {
        if (source.keyVersion() != null) authorize(space, source.project(), source.owner(), source.ownerEpoch(), SpaceRole.MEMBER);
    }

    @Transactional
    public void unbind(String space, String project, String id, String user, long epoch, String key) {
        Membership actor = authorize(space, project, user, epoch, SpaceRole.VIEWER);
        Grant source = require(space, project, id);
        if (!actor.role().atLeast(SpaceRole.ADMIN) && !(source.owner().equals(user) && source.ownerEpoch() == epoch)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        idempotency.execute("REMOTE_UNBIND", id, user, key, List.of(epoch, id), Boolean.class, () -> {
            if (source.active()) {
                revoke(source);
                ledger.append(new RevocationLedgerPort.Revocation(GitProjectUtils.newId(),
                        RevocationLedgerPort.Target.RESOURCE_FORCE_PURGE, space, PURGE_PREFIX + id, source.version()+1, Instant.now()));
            }
            return true;
        });
    }

    private void revoke(Grant source) {
        if (source.jobId() != null) jobs.cancel(source.jobId());
        db.update("""
                UPDATE git_sources SET status='REVOKED',lifecycle_version=lifecycle_version+1,key_version=NULL,
                    token_ciphertext=NULL,active_job_id=NULL,active_actor_epoch=NULL WHERE id=? AND status='ACTIVE'
                """, source.id());
    }

    boolean replay(RevocationLedgerPort.Entry entry) {
        var revoked = entry.revocation();
        if (!revoked.targetId().startsWith(PURGE_PREFIX)) return false;
        Grant source = lock(revoked.spaceId(), revoked.targetId().substring(PURGE_PREFIX.length()));
        if (source != null && source.active()) revoke(source);
        return true;
    }

    @Override
    public void onRevoked(Revocation ended) {
        var owned = db.queryForList("""
                SELECT id FROM git_sources WHERE space_id=? AND owner_user_id=? AND owner_epoch=?
                AND status='ACTIVE' AND key_version IS NOT NULL ORDER BY id FOR UPDATE
                """, String.class, ended.spaceId(), ended.userId(), ended.endedMembershipEpoch());
        for (String id : owned) revoke(lock(ended.spaceId(), id));
        var active = db.queryForList("""
                SELECT s.active_job_id FROM git_sources s JOIN async_jobs j ON j.job_id=s.active_job_id
                WHERE s.space_id=? AND j.actor_id=? AND s.active_actor_epoch=? FOR UPDATE
                """, String.class, ended.spaceId(), ended.userId(), ended.endedMembershipEpoch());
        for (String jobId : active) {
            jobs.cancel(jobId);
            db.update("UPDATE git_sources SET active_job_id=NULL, active_actor_epoch=NULL WHERE active_job_id=?", jobId);
        }
    }

    private Grant current(AsyncJobPort.Job job) {
        Grant source = lock(job.spaceId(), job.resourceId());
        if (!"REMOTE_GIT_SYNC".equals(job.type()) || source == null || !source.active()
                || source.version() != job.resourceLifecycleVersion() || job.inputVersion() != source.version()
                || !job.jobId().equals(source.jobId())) throw new DomainException(KernelErrorCode.AUTH_REVOKED);
        authorize(job.spaceId(), source.project(), job.actorId(), source.actorEpoch(), SpaceRole.MEMBER);
        checkOwner(job.spaceId(), source);
        return source;
    }

    @Transactional
    public Map<String, Object> workerInput(AsyncJobPort.Job job) {
        Grant source = current(job);
        String url = gateway().normalize(source.url()).toASCIIString();
        if (source.keyVersion() == null) return Map.of("repositoryUrl", url);
        String token = secrets.open(source.id(), source.url(), source.keyVersion(), source.secret());
        if (secrets.activeVersion() != source.keyVersion()) {
            var sealed = secrets.seal(source.id(), source.url(), token);
            db.update("UPDATE git_sources SET key_version=?,token_ciphertext=? WHERE id=?",
                    sealed.keyVersion(), sealed.ciphertext(), source.id());
        }
        return Map.of("repositoryUrl", url,"token", token);
    }

    AsyncJobPort.ResourceState lockAndCheck(AsyncJobPort.Job job) {
        try {
            return new AsyncJobPort.ResourceState(current(job).version(), true);
        } catch (DomainException denied) {
            return new AsyncJobPort.ResourceState(-1, false);
        }
    }
}
