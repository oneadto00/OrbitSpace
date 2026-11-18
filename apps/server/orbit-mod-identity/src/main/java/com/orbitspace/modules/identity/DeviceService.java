package com.orbitspace.modules.identity;

import static com.orbitspace.kernel.KernelErrorCode.*;
import com.orbitspace.kernel.*;
import com.orbitspace.security.SpaceAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class DeviceService implements MembershipRevocationHook {
    static final String CLIENT = "orbit-cli";
    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";
    private static final Set<String> ALLOWED = Set.of("metadata:write","patch:write");
    private static final int INTERVAL = 5;
    private static final Duration REQUEST_TTL = Duration.ofMinutes(10), GRANT_TTL = Duration.ofDays(30);

    record Authorization(String device_code, String user_code, String verification_uri, int interval, long expires_in) {}
    record Pending(String deviceLabel, Set<String> requestedScopes, Instant expiresAt) {}
    record Grant(String credentialId, String userId, String deviceId, String deviceLabel, String spaceId,
            String projectId, long membershipEpoch, Set<String> scopes, Instant expiresAt) {}
    record Token(String access_token, String token_type, long expires_in, String scope, Grant grant) {}
    record Poll(Token token, KernelErrorCode failure) {
        static Poll failed(KernelErrorCode failure) { return new Poll(null, failure); }
    }
    private record Request(String hash, String label, String requested, String status, Instant expires,
            int interval, Instant nextPoll, String user, String space, String project, long epoch, String scopes) {}
    private static final String GRANTS = """
            SELECT c.id,c.user_id,c.device_id,d.label,c.space_id,c.project_id,c.membership_epoch,c.scopes,c.expires_at
            FROM device_credentials c JOIN devices d ON d.id=c.device_id AND d.user_id=c.user_id
            """;

    private final JdbcTemplate db;
    private final String origin;
    private final SpaceAccess access;
    private final RevocationLedgerPort ledger;

    DeviceService(JdbcTemplate db, SpaceAccess access, RevocationLedgerPort ledger, @Value("${orbit.public-origin}") String origin) {
        this.db = db;
        this.origin = origin;
        this.access = access;
        this.ledger = ledger;
    }

    static Set<String> scopes(String raw) {
        var words = new TreeSet<>(List.of(raw.split(" ", -1)));
        if (words.isEmpty() || !ALLOWED.containsAll(words)) throw new DomainException(INVALID_REQUEST);
        return words;
    }

    @Transactional
    public Authorization request(String label, String scope) {
        String requested = String.join(" ", scopes(scope));
        String high = IdentitySecrets.token();
        String shortCode = IdentitySecrets.userCode();
        Instant now = Instant.now();
        db.update("""
                INSERT INTO device_authorization_requests
                (device_code_hash,user_code,device_label,requested_scopes,interval_seconds,next_poll_at,expires_at)
                VALUES (?,?,?,?,?,?,?)
                """, IdentitySecrets.hash(high),shortCode,label,requested,INTERVAL,Timestamp.from(now.plusSeconds(INTERVAL)),
                Timestamp.from(now.plus(REQUEST_TTL)));
        return new Authorization(high,shortCode,origin + "/device",INTERVAL,REQUEST_TTL.toSeconds());
    }

    private Request requestRow(ResultSet rs, int i) throws SQLException {
        return new Request(rs.getString("device_code_hash"),rs.getString("device_label"),rs.getString("requested_scopes"),
                rs.getString("status"),rs.getTimestamp("expires_at").toInstant(),rs.getInt("interval_seconds"),
                rs.getTimestamp("next_poll_at").toInstant(),rs.getString("user_id"),rs.getString("space_id"),
                rs.getString("project_id"),rs.getLong("membership_epoch"),rs.getString("granted_scopes"));
    }

    private Request pending(String code, boolean lock) {
        var request = db.query("SELECT * FROM device_authorization_requests WHERE user_code=?" + (lock ? " FOR UPDATE" : ""),
                this::requestRow,code.strip().toUpperCase(Locale.ROOT)).stream().findFirst();
        if (request.isEmpty() || !request.get().status.equals("PENDING")
                || !request.get().expires.isAfter(Instant.now())) throw new DomainException(DEVICE_EXPIRED);
        return request.get();
    }

    public Pending lookup(String userCode) {
        var r = pending(userCode,false);
        return new Pending(r.label,scopes(r.requested),r.expires);
    }

    private void lockFence(String user, String space) {
        db.update("""
                INSERT INTO device_epoch_fences(space_id,user_id) VALUES (?,?)
                ON DUPLICATE KEY UPDATE ended_epoch=ended_epoch
                """,space,user);
    }

    private void permitGrant(String user,String space,String project,long epoch) {
        access.member(user,space,epoch,SpaceRole.MEMBER);
        access.project(space,project);
        lockFence(user,space);
        long ended = db.queryForObject("SELECT ended_epoch FROM device_epoch_fences WHERE space_id=? AND user_id=? FOR UPDATE",
                Long.class,space,user);
        if (epoch <= ended) throw new DomainException(MEMBERSHIP_EPOCH_INVALID);
    }

    @Transactional
    public void approve(String user,String code,String space,String project,long epoch,List<String> approved) {
        var selected = new TreeSet<>(approved);
        if (selected.isEmpty() || selected.size() != approved.size() || !ALLOWED.containsAll(selected))
            throw new DomainException(INVALID_REQUEST);
        permitGrant(user,space,project,epoch);
        var request = pending(code,true);
        if (!scopes(request.requested).containsAll(selected)) throw new DomainException(AUTH_FORBIDDEN);
        db.update("""
                UPDATE device_authorization_requests SET status='APPROVED',user_id=?,space_id=?,project_id=?,
                membership_epoch=?,granted_scopes=? WHERE device_code_hash=?
                """,user,space,project,epoch,String.join(" ",selected),request.hash);
    }

    @Transactional
    public Poll poll(String client, String type, String high) {
        if (!CLIENT.equals(client) || !GRANT_TYPE.equals(type)) return Poll.failed(INVALID_REQUEST);
        if (high == null || !high.matches("[A-Za-z0-9_-]{43}")) return Poll.failed(AUTH_UNAUTHORIZED);
        String hash = IdentitySecrets.hash(high);
        var observed = db.query("SELECT * FROM device_authorization_requests WHERE device_code_hash=?",
                this::requestRow, hash).stream().findFirst().orElse(null);
        if (observed == null) return Poll.failed(AUTH_UNAUTHORIZED);
        // Eligibility, epoch fence, then request row: same lock order as approval and membership removal.
        boolean eligible = observed.status.equals("APPROVED") && observed.expires.isAfter(Instant.now());
        if (eligible) permitGrant(observed.user, observed.space, observed.project, observed.epoch);
        var request = db.queryForObject("SELECT * FROM device_authorization_requests WHERE device_code_hash=? FOR UPDATE",
                this::requestRow, hash);
        Instant now = Instant.now();
        if (!request.expires.isAfter(now)) return Poll.failed(DEVICE_EXPIRED);
        if (request.status.equals("CONSUMED")) return Poll.failed(AUTH_UNAUTHORIZED);
        if (request.status.equals("DENIED")) return Poll.failed(DEVICE_ACCESS_DENIED);
        boolean early = now.isBefore(request.nextPoll);
        int interval = early ? Math.min(900,request.interval+5) : request.interval;
        db.update("UPDATE device_authorization_requests SET interval_seconds=?,next_poll_at=? WHERE device_code_hash=?",
                interval,Timestamp.from(now.plusSeconds(interval)),request.hash);
        if (early) return Poll.failed(DEVICE_SLOW_DOWN);
        // If approval arrived during this poll, check that grant on the next poll in the correct lock order.
        if (!eligible || request.status.equals("PENDING")) return Poll.failed(DEVICE_AUTH_PENDING);

        String device = IdentitySecrets.id();
        String credential = IdentitySecrets.id();
        String secret = "od_" + IdentitySecrets.token();
        Instant expires = now.plus(GRANT_TTL);
        db.update("INSERT INTO devices(id,user_id,label) VALUES (?,?,?)",device,request.user,request.label);
        db.update("""
                INSERT INTO device_credentials(id,token_hash,device_id,user_id,space_id,project_id,membership_epoch,scopes,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,credential,IdentitySecrets.hash(secret),device,request.user,request.space,request.project,request.epoch,
                request.scopes,Timestamp.from(expires));
        db.update("UPDATE device_authorization_requests SET status='CONSUMED' WHERE device_code_hash=?",request.hash);
        var grant = new Grant(credential,request.user,device,request.label,request.space,request.project,request.epoch,
                scopes(request.scopes),expires);
        return new Poll(new Token(secret,"Bearer",GRANT_TTL.toSeconds(),request.scopes,grant), null);
    }

    @Transactional
    public void denyRevokedRequest(String high) {
        db.update("""
                UPDATE device_authorization_requests SET status='DENIED'
                WHERE device_code_hash=? AND status='APPROVED'
                """, IdentitySecrets.hash(high));
    }

    private Grant grant(ResultSet rs, int i) throws SQLException {
        return new Grant(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getString(6),rs.getLong(7),scopes(rs.getString(8)),rs.getTimestamp(9).toInstant());
    }

    public DevicePrincipal authenticate(String raw) {
        if (raw == null || !raw.matches("od_[A-Za-z0-9_-]{43}")) throw new DomainException(AUTH_UNAUTHORIZED);
        var grant = db.query(GRANTS + " WHERE c.token_hash=? AND c.status='ACTIVE' AND c.expires_at>CURRENT_TIMESTAMP(6)",
                this::grant,IdentitySecrets.hash(raw)).stream().findFirst().orElseThrow(() -> new DomainException(AUTH_REVOKED));
        access.member(grant.userId, grant.spaceId, grant.membershipEpoch,SpaceRole.MEMBER);
        access.project(grant.spaceId, grant.projectId);
        return new DevicePrincipal(grant.userId,grant.deviceId,grant.spaceId,grant.projectId,grant.scopes,grant.membershipEpoch);
    }

    public List<Grant> list(String user) {
        return db.query(GRANTS + " WHERE c.user_id=? AND c.status='ACTIVE' AND c.expires_at>CURRENT_TIMESTAMP(6) ORDER BY c.id",
                this::grant,user);
    }

    @Transactional
    public void revoke(String user,String credential) {
        var grant = db.query(GRANTS + " WHERE c.user_id=? AND c.id=? FOR UPDATE",this::grant,user,credential)
                .stream().findFirst().orElseThrow(() -> new DomainException(RESOURCE_NOT_FOUND));
        int changed = db.update("""
                UPDATE device_credentials SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP(6)
                WHERE id=? AND status='ACTIVE'
                """,credential);
        if (changed == 1) ledger.append(new RevocationLedgerPort.Revocation(IdentitySecrets.id(),RevocationLedgerPort.Target.DEVICE_CREDENTIAL,
                grant.spaceId,credential,grant.membershipEpoch,Instant.now()));
    }

    @Transactional
    public void replay(RevocationLedgerPort.Entry entry) {
        var record = entry.revocation();
        if (record.target() != RevocationLedgerPort.Target.DEVICE_CREDENTIAL) throw new IllegalArgumentException("Unsupported target");
        db.update("""
                UPDATE device_credentials SET status='REVOKED',revoked_at=COALESCE(revoked_at,?)
                WHERE id=? AND space_id=? AND membership_epoch=?
                """,Timestamp.from(record.effectiveAt()),record.targetId(),record.spaceId(),record.version());
    }

    @Override @Transactional
    public void onRevoked(Revocation removal) {
        lockFence(removal.userId(),removal.spaceId());
        db.update("UPDATE device_epoch_fences SET ended_epoch=GREATEST(ended_epoch,?) WHERE space_id=? AND user_id=?",
                removal.endedMembershipEpoch(),removal.spaceId(),removal.userId());
        db.update("""
                UPDATE device_credentials SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP(6)
                WHERE space_id=? AND user_id=? AND membership_epoch<=? AND status='ACTIVE'
                """,removal.spaceId(),removal.userId(),removal.endedMembershipEpoch());
        db.update("""
                UPDATE device_authorization_requests SET status='DENIED'
                WHERE space_id=? AND user_id=? AND membership_epoch<=? AND status='APPROVED'
                """,removal.spaceId(),removal.userId(),removal.endedMembershipEpoch());
    }
}
