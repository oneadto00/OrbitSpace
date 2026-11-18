package com.orbitspace.modules.integration;

import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.AsyncJobProcessor;
import com.orbitspace.kernel.AsyncJobStatus;
import com.orbitspace.kernel.AuditLogPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.ObjectStoragePort;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.integration.IntegrationDtos.InternalClaimJobRequest;
import com.orbitspace.modules.integration.IntegrationDtos.InternalClaimedJobResponse;
import com.orbitspace.modules.integration.IntegrationDtos.InternalJobFailRequest;
import com.orbitspace.modules.integration.IntegrationDtos.InternalJobFailResponse;
import com.orbitspace.modules.integration.IntegrationDtos.InternalJobResultRequest;
import com.orbitspace.modules.integration.IntegrationDtos.InternalJobResultResponse;
import com.orbitspace.modules.integration.IntegrationDtos.InternalStoredOutputResponse;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Service backing /internal/v1/jobs/** for worker claim, output staging, failure, and anti-revival result acceptance. */
public class IntegrationJobResultService {
    private static final String JOB_IMAGE_THUMBNAIL = "IMAGE_THUMBNAIL";
    private static final String JOB_DIFF_ENRICHMENT = "DIFF_ENRICHMENT";
    private static final String JOB_WIP_PATCH_PARSE = "GIT_WIP_PATCH_PARSE";

    private record JobRow(AsyncJobPort.Job job, String status) {}

    private record FileLockRow(
            String scope,
            String spaceId,
            String ownerUserId,
            String lifecycleState,
            long lifecycleVersion,
            String thumbnailJobId) {}

    private record DiffLockRow(
            String projectId,
            String lifecycleState,
            long lifecycleVersion,
            String enrichmentJobId,
            String status) {}

    private final JdbcTemplate db;
    private final AsyncJobPort asyncJobs;
    private final ObjectStoragePort objectStorage;
    private final AuditLogPort auditLog;
    private final ObjectProvider<S3Client> contentClientProvider;
    private final ObjectProvider<AsyncJobProcessor> processors;
    private final String s3Bucket;
    private final long maxOutputBytes;

    public IntegrationJobResultService(
            JdbcTemplate db,
            AsyncJobPort asyncJobs,
            ObjectStoragePort objectStorage,
            AuditLogPort auditLog,
            @Qualifier("contentClient") ObjectProvider<S3Client> contentClientProvider,
            ObjectProvider<AsyncJobProcessor> processors,
            @Value("${orbit.s3.bucket:orbitspace-private}") String s3Bucket,
            @Value("${orbit.integration.max-output-bytes:16777216}") long maxOutputBytes) {
        this.db = db;
        this.asyncJobs = asyncJobs;
        this.objectStorage = objectStorage;
        this.auditLog = auditLog;
        this.contentClientProvider = contentClientProvider;
        this.processors = processors;
        this.s3Bucket = s3Bucket;
        this.maxOutputBytes = Math.max(1024L, maxOutputBytes);
    }

    public InternalClaimedJobResponse claimJob(String rawJobId, InternalClaimJobRequest req) {
        String jobId = normalizeId(rawJobId);
        JobRow row = loadJob(jobId);
        Optional<AsyncJobPort.Attempt> attemptOpt =
                asyncJobs.claim(jobId, Duration.ofSeconds(req.leaseSeconds()));
        if (attemptOpt.isEmpty()) {
            AsyncJobPort.Job job = row.job();
            return new InternalClaimedJobResponse(
                    false,
                    job.jobId(),
                    job.type(),
                    job.spaceId(),
                    job.resourceId(),
                    job.actorId(),
                    job.inputObjectKey(),
                    job.inputVersion(),
                    job.resourceLifecycleVersion(),
                    null,
                    null);
        }
        AsyncJobPort.Attempt attempt = attemptOpt.get();
        AsyncJobPort.Job job = attempt.job();
        return new InternalClaimedJobResponse(
                true,
                job.jobId(),
                job.type(),
                job.spaceId(),
                job.resourceId(),
                job.actorId(),
                job.inputObjectKey(),
                job.inputVersion(),
                job.resourceLifecycleVersion(),
                attempt.leaseId(),
                attempt.leaseUntil());
    }

    public InternalStoredOutputResponse storeOutput(
            String rawJobId, String rawContentSha256, byte[] bodyBytes) {
        String jobId = normalizeId(rawJobId);
        if (rawContentSha256 == null
                || !rawContentSha256.strip().matches("^[0-9a-f]{64}$")
                || bodyBytes.length > maxOutputBytes) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String expectedSha256 = rawContentSha256.strip();
        String actualSha256 = IntegrationHmacSupport.sha256Hex(bodyBytes);
        if (!expectedSha256.equals(actualSha256)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        JobRow job = loadJob(jobId);
        if (!"RUNNING".equals(job.status())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }

        try {
            ObjectStoragePort.StoredObject temp =
                    objectStorage.writeTemporary(
                            "job-output:" + jobId + ":" + expectedSha256,
                            new ByteArrayInputStream(bodyBytes),
                            bodyBytes.length);
            ObjectStoragePort.StoredObject promoted =
                    objectStorage.promote(temp.objectKey(), bodyBytes.length, expectedSha256);
            return new InternalStoredOutputResponse(
                    promoted.objectKey(), promoted.sizeBytes(), promoted.sha256());
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    public InternalJobResultResponse acceptResult(
            String rawJobId, InternalJobResultRequest req) {
        String jobId = normalizeId(rawJobId);
        if (req.expectedJobStatus() != AsyncJobStatus.RUNNING) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        JobRow existing = loadJob(jobId);
        String resolvedObjectKey = resolveAndPromoteResultObject(jobId, req);
        AsyncJobPort.ResultTarget target = buildResultTarget(existing.job().type());

        AsyncJobPort.Acceptance acceptance =
                asyncJobs.acceptResult(
                        new AsyncJobPort.Result(
                                jobId,
                                req.leaseId().strip(),
                                req.expectedJobStatus(),
                                req.inputVersion(),
                                req.resourceLifecycleVersion(),
                                resolvedObjectKey),
                        target);

        String returnedKey =
                acceptance == AsyncJobPort.Acceptance.STALE ? null : resolvedObjectKey;
        return new InternalJobResultResponse(acceptance, jobId, returnedKey);
    }

    public InternalJobFailResponse failJob(String rawJobId, InternalJobFailRequest req) {
        String jobId = normalizeId(rawJobId);
        JobRow row = loadJob(jobId);
        AsyncJobPort.Attempt attempt =
                new AsyncJobPort.Attempt(row.job(), req.leaseId().strip(), Instant.now());
        boolean updated;
        if (req.retryable()) {
            int delaySeconds = req.retryDelaySeconds() != null ? req.retryDelaySeconds() : 30;
            updated =
                    asyncJobs.retryLater(
                            attempt,
                            Instant.now().plusSeconds(delaySeconds),
                            req.failureCode().strip());
        } else {
            updated = asyncJobs.fail(attempt, req.failureCode().strip());
        }
        return new InternalJobFailResponse(updated);
    }

    private String resolveAndPromoteResultObject(String jobId, InternalJobResultRequest req) {
        if (req.stagingObjectKey() != null && !req.stagingObjectKey().isBlank()) {
            String stagingKey = req.stagingObjectKey().strip();
            if (!stagingKey.startsWith("staging/")
                    || stagingKey.contains("..")
                    || stagingKey.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            S3Client s3 = contentClientProvider.getIfAvailable();
            if (s3 == null) {
                throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
            }
            long limit =
                    req.expectedSizeBytes() != null
                            ? Math.min(maxOutputBytes, req.expectedSizeBytes())
                            : maxOutputBytes;
            try (InputStream s3Stream =
                    s3.getObject(b -> b.bucket(s3Bucket).key(stagingKey))) {
                ObjectStoragePort.StoredObject temp =
                        objectStorage.writeTemporary(
                                "job-ingest:" + jobId + ":" + req.leaseId().strip(),
                                s3Stream,
                                limit);
                if (req.expectedSizeBytes() != null
                        && temp.sizeBytes() != req.expectedSizeBytes()) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                if (req.expectedSha256() != null
                        && !req.expectedSha256().equals(temp.sha256())) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                ObjectStoragePort.StoredObject promoted =
                        objectStorage.promote(temp.objectKey(), temp.sizeBytes(), temp.sha256());
                return promoted.objectKey();
            } catch (IOException ex) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }

        if (req.resultObjectKey() != null && !req.resultObjectKey().isBlank()) {
            String resultKey = req.resultObjectKey().strip();
            if (!resultKey.matches("^immutable/[0-9a-f]{64}$")) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            if (req.expectedSha256() != null
                    && !resultKey.equals("immutable/" + req.expectedSha256())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            return resultKey;
        }

        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
    }

    private AsyncJobPort.ResultTarget buildResultTarget(String jobType) {
        if (JOB_IMAGE_THUMBNAIL.equals(jobType)) {
            return new ThumbnailResultTarget();
        }
        if (JOB_DIFF_ENRICHMENT.equals(jobType)) {
            return new DiffEnrichmentResultTarget();
        }
        if (JOB_WIP_PATCH_PARSE.equals(jobType)) {
            AsyncJobProcessor wipProcessor =
                    processors
                            .orderedStream()
                            .filter(p -> JOB_WIP_PATCH_PARSE.equals(p.jobType()))
                            .findFirst()
                            .orElse(null);
            if (wipProcessor != null) {
                return wipProcessor;
            }
        }
        return new AsyncJobPort.ResultTarget() {
            @Override
            public AsyncJobPort.ResourceState lockAndCheck(AsyncJobPort.Job job) {
                return new AsyncJobPort.ResourceState(-1L, false);
            }

            @Override
            public void attachResult(AsyncJobPort.Job job, String resultObjectKey) {
                throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
            }
        };
    }

    private final class ThumbnailResultTarget implements AsyncJobPort.ResultTarget {
        @Override
        public AsyncJobPort.ResourceState lockAndCheck(AsyncJobPort.Job job) {
            FileLockRow file =
                    db.query(
                                    """
                                    SELECT scope, space_id, owner_user_id, lifecycle_state,
                                           lifecycle_version, thumbnail_job_id
                                    FROM files
                                    WHERE id = ?
                                    FOR UPDATE
                                    """,
                                    (rs, i) ->
                                            new FileLockRow(
                                                    rs.getString(1),
                                                    rs.getString(2),
                                                    rs.getString(3),
                                                    rs.getString(4),
                                                    rs.getLong(5),
                                                    rs.getString(6)),
                                    job.resourceId())
                            .stream()
                            .findFirst()
                            .orElse(null);
            if (file == null) {
                return new AsyncJobPort.ResourceState(-1L, false);
            }
            boolean authorized =
                    "ACTIVE".equals(file.lifecycleState())
                            && job.jobId().equals(file.thumbnailJobId())
                            && isFileGrantActive(file, job.actorId());
            return new AsyncJobPort.ResourceState(file.lifecycleVersion(), authorized);
        }

        @Override
        public void attachResult(AsyncJobPort.Job job, String resultObjectKey) {
            pinStorageReference("FILE_THUMBNAIL", job.resourceId(), resultObjectKey);
            db.update(
                    """
                    UPDATE files
                    SET thumbnail_object_key = ?,
                        thumbnail_ready = TRUE,
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = ? AND thumbnail_job_id = ? AND lifecycle_state = 'ACTIVE'
                    """,
                    resultObjectKey,
                    job.resourceId(),
                    job.jobId());
            auditLog.append(
                    new AuditLogPort.Entry(
                            requestIdFromMdc(),
                            job.actorId(),
                            "JOB_THUMBNAIL_ACCEPT",
                            job.resourceId(),
                            "SUCCESS",
                            Instant.now()));
        }
    }

    private final class DiffEnrichmentResultTarget implements AsyncJobPort.ResultTarget {
        @Override
        public AsyncJobPort.ResourceState lockAndCheck(AsyncJobPort.Job job) {
            if (!tableExists("two_point_diffs")) {
                return new AsyncJobPort.ResourceState(-1L, false);
            }
            DiffLockRow diff =
                    db.query(
                                    """
                                    SELECT project_id, lifecycle_state, lifecycle_version,
                                           enrichment_job_id, status
                                    FROM two_point_diffs
                                    WHERE id = ? AND space_id = ?
                                    FOR UPDATE
                                    """,
                                    (rs, i) ->
                                            new DiffLockRow(
                                                    rs.getString(1),
                                                    rs.getString(2),
                                                    rs.getLong(3),
                                                    rs.getString(4),
                                                    rs.getString(5)),
                                    job.resourceId(),
                                    job.spaceId())
                            .stream()
                            .findFirst()
                            .orElse(null);
            if (diff == null) {
                return new AsyncJobPort.ResourceState(-1L, false);
            }
            boolean authorized =
                    "ACTIVE".equals(diff.lifecycleState())
                            && job.jobId().equals(diff.enrichmentJobId())
                            && ("PENDING".equals(diff.status()) || "READY".equals(diff.status()))
                            && isSpaceProjectMemberActive(
                                    job.spaceId(), diff.projectId(), job.actorId(), SpaceRole.VIEWER);
            return new AsyncJobPort.ResourceState(diff.lifecycleVersion(), authorized);
        }

        @Override
        public void attachResult(AsyncJobPort.Job job, String resultObjectKey) {
            pinStorageReference("DIFF_ENRICHMENT", job.resourceId(), resultObjectKey);
            db.update(
                    """
                    UPDATE two_point_diffs
                    SET status = 'READY',
                        enrichment_result_object_key = ?
                    WHERE id = ? AND space_id = ? AND enrichment_job_id = ? AND lifecycle_state = 'ACTIVE'
                    """,
                    resultObjectKey,
                    job.resourceId(),
                    job.spaceId(),
                    job.jobId());
            auditLog.append(
                    new AuditLogPort.Entry(
                            requestIdFromMdc(),
                            job.actorId(),
                            "JOB_DIFF_ENRICH_ACCEPT",
                            job.resourceId(),
                            "SUCCESS",
                            Instant.now()));
        }
    }

    private boolean isFileGrantActive(FileLockRow file, String actorId) {
        if ("SPACE_FILE".equals(file.scope())) {
            if (file.spaceId() == null) {
                return false;
            }
            return isSpaceActiveMember(file.spaceId(), actorId, SpaceRole.MEMBER);
        }
        if ("AIRDROP_SHARE".equals(file.scope())) {
            if (!file.ownerUserId().equals(actorId)) {
                return false;
            }
            return Boolean.TRUE.equals(
                    db.queryForObject(
                            """
                            SELECT EXISTS(
                                SELECT 1 FROM users WHERE id = ? AND email_verified = TRUE
                            )
                            """,
                            Boolean.class,
                            actorId));
        }
        return false;
    }

    private boolean isSpaceProjectMemberActive(
            String spaceId, String projectId, String actorId, SpaceRole minRole) {
        boolean projectActive =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM git_projects p
                                    JOIN spaces s ON s.id = p.space_id
                                    WHERE p.id = ? AND p.space_id = ?
                                      AND p.status = 'ACTIVE' AND s.status = 'ACTIVE'
                                )
                                """,
                                Boolean.class,
                                projectId,
                                spaceId));
        return projectActive && isSpaceActiveMember(spaceId, actorId, minRole);
    }

    private boolean isSpaceActiveMember(String spaceId, String actorId, SpaceRole minRole) {
        List<String> roles =
                db.queryForList(
                        """
                        SELECT m.role
                        FROM space_members m
                        JOIN spaces s ON s.id = m.space_id
                        WHERE m.space_id = ? AND m.user_id = ?
                          AND m.status = 'ACTIVE' AND s.status = 'ACTIVE'
                        """,
                        String.class,
                        spaceId,
                        actorId);
        if (roles.isEmpty()) {
            return false;
        }
        return SpaceRole.valueOf(roles.getFirst()).atLeast(minRole);
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(
                            SELECT 1 FROM information_schema.tables
                            WHERE table_schema = DATABASE() AND table_name = ?
                        )
                        """,
                        Boolean.class,
                        tableName));
    }

    private void pinStorageReference(String ownerType, String ownerId, String objectKey) {
        boolean exists =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM storage_object_references
                                    WHERE owner_type = ? AND owner_id = ? AND object_key = ?
                                )
                                """,
                                Boolean.class,
                                ownerType,
                                ownerId,
                                objectKey));
        if (!exists) {
            db.update(
                    """
                    INSERT INTO storage_object_references(owner_type, owner_id, object_key)
                    VALUES (?, ?, ?)
                    """,
                    ownerType,
                    ownerId,
                    objectKey);
        }
    }

    private JobRow loadJob(String jobId) {
        return db.query(
                        """
                        SELECT job_id, job_type, space_id, resource_id, actor_id,
                               input_object_key, input_version, expected_resource_lifecycle_version,
                               status
                        FROM async_jobs
                        WHERE job_id = ?
                        """,
                        this::mapJobRow,
                        jobId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private JobRow mapJobRow(ResultSet rs, int i) throws SQLException {
        return new JobRow(
                new AsyncJobPort.Job(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getLong(7),
                        rs.getLong(8)),
                rs.getString(9));
    }

    private static String normalizeId(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 160) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return trimmed;
    }

    private static String requestIdFromMdc() {
        String id = MDC.get("requestId");
        return id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    }
}
