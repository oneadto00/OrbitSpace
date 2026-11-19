package com.orbitspace.modules.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class IntegrationEgressService implements ControlledEgressGitPort, AiSummaryGatewayPort {
    static final String REMOTE = "REMOTE_GIT_SYNC";
    static final String SUMMARY = "AI_SUMMARY";
    static final String PDF = "PDF_INDEX";
    private final Set<String> hosts;
    private final JdbcTemplate db;
    private final AsyncJobPort jobs;
    private final ObjectStoragePort storage;
    private final ObjectMapper json;
    private final TransactionTemplate outsideBusinessTransaction;
    private final ObjectProvider<Function<AsyncJobPort.Job, Map<String,Object>>> gitInput;
    private final ObjectProvider<Function<AsyncJobPort.Job, Map<String,Object>>> aiInput;
    private final ObjectProvider<AsyncJobPort.ResultTarget> gitTarget, aiTarget, pdfTarget;

    public IntegrationEgressService(JdbcTemplate db, AsyncJobPort jobs, ObjectStoragePort storage, ObjectMapper json, PlatformTransactionManager transactions,
            @Value("${ORBIT_GIT_TRUSTED_HOSTS:github.com,gitlab.com,bitbucket.org}") String hosts,
            @Qualifier("remoteGitWorkerInput") ObjectProvider<Function<AsyncJobPort.Job,Map<String,Object>>> gitInput,
            @Qualifier("aiWorkerInput") ObjectProvider<Function<AsyncJobPort.Job,Map<String,Object>>> aiInput,
            @Qualifier("remoteGitResultTarget") ObjectProvider<AsyncJobPort.ResultTarget> gitTarget,
            @Qualifier("aiResultTarget") ObjectProvider<AsyncJobPort.ResultTarget> aiTarget,
            @Qualifier("pdfResultTarget") ObjectProvider<AsyncJobPort.ResultTarget> pdfTarget) {
        this.db = db;
        this.jobs = jobs;
        this.storage = storage;
        this.json = json;
        this.outsideBusinessTransaction = new TransactionTemplate(transactions);
        this.outsideBusinessTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.hosts = Arrays.stream(hosts.split(",")).map(String::strip).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.gitInput = gitInput;
        this.aiInput = aiInput;
        this.gitTarget = gitTarget;
        this.aiTarget = aiTarget;
        this.pdfTarget = pdfTarget;
    }

    public URI normalize(String url) {
        try {
            if (url == null || url.length() > 2048) throw new IllegalArgumentException();
            URI uri = URI.create(url);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath().replaceFirst("/$", "");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !hosts.contains(host)
                    || !uri.getRawAuthority().matches("(?i)[a-z0-9.-]+(:443)?")
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !path.matches("(/[a-zA-Z0-9_.-]+){2,}")
                    || Arrays.stream(path.split("/")).anyMatch(p -> p.equals(".") || p.equals(".."))) {
                throw new IllegalArgumentException();
            }
            return URI.create("https://" + host + path);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new DomainException(KernelErrorCode.REMOTE_URL_FORBIDDEN);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueueSync(SpaceAuthorizationPort.Membership actor, String projectId, String sourceId,
                              long lifecycle, String key) {
        requireMember(actor.spaceId(), actor.userId(), actor.membershipEpoch(), SpaceRole.MEMBER);
        var rows = db.queryForList("""
                SELECT s.https_url,s.owner_user_id,s.owner_epoch,s.key_version
                FROM git_sources s JOIN git_projects p ON p.id=s.project_id AND p.space_id=s.space_id
                WHERE s.id=? AND s.project_id=? AND s.space_id=? AND s.status='ACTIVE'
                    AND s.lifecycle_version=? AND p.status='ACTIVE' FOR UPDATE
                """,sourceId,projectId,actor.spaceId(),lifecycle);
        if (rows.isEmpty()) throw denied();
        var source = rows.getFirst();
        if (source.get("key_version") != null) {
            requireMember(actor.spaceId(),source.get("owner_user_id").toString(),
                    ((Number)source.get("owner_epoch")).longValue(),SpaceRole.MEMBER);
        }
        normalize((String) source.get("https_url"));
        return enqueue(REMOTE,actor,sourceId,lifecycle,key,
                Map.of("sourceId",sourceId,"projectId",projectId,"lifecycleVersion",lifecycle,
                        "keyVersion",String.valueOf(source.get("key_version"))),List.of());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueueSummary(Request req) {
        var actor = req.actor();
        requireMember(actor.spaceId(),actor.userId(),actor.membershipEpoch(),SpaceRole.VIEWER);
        if (req.sources().size() > 64) throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        return enqueue(SUMMARY,actor,null,1,req.idempotencyKey(),req.sources(),req.sources());
    }

    private String enqueue(String type, SpaceAuthorizationPort.Membership actor, String resource, long lifecycle,
                           String key, Object manifest, List<Source> sources) {
        if (key == null || key.isBlank() || key.length()>160) throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        var id = IntegrationHmacSupport.sha256Hex(bytes(List.of(type,actor.spaceId(),actor.userId(),actor.membershipEpoch(),key)));
        byte[] input = bytes(manifest);
        var digest = IntegrationHmacSupport.sha256Hex(input);
        // Object promotion performs its own commits. Its existence check must see the new
        // temporary row even when the caller already holds a repeatable-read snapshot.
        String objectKey = outsideBusinessTransaction.execute(status -> {
            try {
                var temp = storage.writeTemporary("manifest-" + id + "-" + digest, new ByteArrayInputStream(input),input.length);
                return storage.promote(temp.objectKey(),input.length,digest).objectKey();
            } catch (IOException ex) {
                throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
            }
        });
        jobs.enqueue(new AsyncJobPort.Job(id,type,actor.spaceId(),resource == null ? id : resource,actor.userId(),
                objectKey,lifecycle,lifecycle));
        var existing = db.queryForList("SELECT request_digest FROM integration_job_grants WHERE job_id=? FOR UPDATE",String.class,id);
        if (existing.isEmpty()) {
            db.update("INSERT INTO integration_job_grants(job_id,membership_epoch,request_digest) VALUES(?,?,?)",
                    id,actor.membershipEpoch(),digest);
            for (int i=0; i<sources.size(); i++) {
                var s = sources.get(i);
                db.update("INSERT INTO integration_ai_sources(job_id,ordinal_no,source_kind,resource_id,version_id,lifecycle_version) VALUES(?,?,?,?,?,?)",
                        id,i,s.kind().name(),s.resourceId(),s.versionId(),s.resourceLifecycleVersion());
            }
        } else if (!digest.equals(existing.getFirst())) throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        return id;
    }

    private byte[] bytes(Object value) {
        try {
            return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);
        } catch (JsonProcessingException ex) { throw new IllegalArgumentException(ex); }
    }

    private void requireMember(String space, String user, long epoch, SpaceRole role) {
        var roles = db.queryForList("""
                SELECT m.role FROM space_members m JOIN spaces s ON s.id=m.space_id
                WHERE m.space_id=? AND m.user_id=? AND m.membership_epoch=? AND m.status='ACTIVE'
                    AND s.status='ACTIVE' FOR SHARE
                """,String.class,space,user,epoch);
        if (roles.isEmpty() || !SpaceRole.valueOf(roles.getFirst()).atLeast(role)) throw denied();
    }

    private static DomainException denied() { return new DomainException(KernelErrorCode.AUTH_FORBIDDEN); }

    boolean handles(String type) { return Set.of(REMOTE,SUMMARY,PDF).contains(type); }

    AsyncJobPort.ResultTarget target(String type) {
        var domain = switch(type) {
            case REMOTE -> gitTarget.getObject();
            case SUMMARY -> aiTarget.getObject();
            case PDF -> pdfTarget.getObject();
            default -> throw denied();
        };
        return new AsyncJobPort.ResultTarget() {
            public AsyncJobPort.ResourceState lockAndCheck(AsyncJobPort.Job job) {
                try {
                    if (!PDF.equals(type)) {
                        var epochs = db.queryForList("SELECT membership_epoch FROM integration_job_grants WHERE job_id=? FOR SHARE",
                                Long.class,job.jobId());
                        if (epochs.isEmpty()) return new AsyncJobPort.ResourceState(-1,false);
                        requireMember(job.spaceId(),job.actorId(),epochs.getFirst(),REMOTE.equals(type) ? SpaceRole.MEMBER : SpaceRole.VIEWER);
                    }
                    return domain.lockAndCheck(job);
                } catch (DomainException e) { return new AsyncJobPort.ResourceState(-1,false); }
            }
            public void attachResult(AsyncJobPort.Job job, String objectKey) { domain.attachResult(job,objectKey); }
        };
    }

    @Transactional
    public Map<String,Object> workerInput(String jobId, String lease, boolean checkOnly) {
        var rows = db.query("""
                SELECT job_id,job_type,space_id,resource_id,actor_id,input_object_key,input_version,expected_resource_lifecycle_version
                FROM async_jobs WHERE job_id=? AND status='RUNNING' AND lease_holder=? AND lease_until>CURRENT_TIMESTAMP(6) FOR UPDATE
                """,(rs,i) -> new AsyncJobPort.Job(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getLong(7),rs.getLong(8)),jobId,lease);
        if (rows.isEmpty()) throw denied();
        var job = rows.getFirst();
        var state = target(job.type()).lockAndCheck(job);
        if (!state.processingAuthorized() || state.lifecycleVersion()!=job.resourceLifecycleVersion()) throw denied();
        if (checkOnly) return Map.of("active",true);
        return switch(job.type()) {
            case REMOTE -> gitInput.getObject().apply(job);
            case SUMMARY -> aiInput.getObject().apply(job);
            case PDF -> Map.of("objectKey",job.inputObjectKey());
            default -> throw denied();
        };
    }
}
