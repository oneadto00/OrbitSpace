package com.orbitspace.app.config;

import com.orbitspace.kernel.AsyncJobProcessor;
import com.orbitspace.kernel.GrantType;
import com.orbitspace.kernel.MessageKind;
import com.orbitspace.kernel.OutboxPublisherPort;
import com.orbitspace.kernel.ResourceCardVerifierPort;
import com.orbitspace.kernel.ResourceLifecycleHook;
import com.orbitspace.kernel.ResourceLifecycleState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@AutoConfiguration(afterName = {
    "com.orbitspace.platform.PlatformAutoConfiguration",
    "com.orbitspace.modules.identity.IdentityAutoConfiguration",
    "com.orbitspace.modules.space.SpaceAutoConfiguration",
    "com.orbitspace.modules.gitproject.GitProjectAutoConfiguration",
    "com.orbitspace.modules.chat.ChatAutoConfiguration",
    "com.orbitspace.modules.friendship.FriendshipAutoConfiguration",
    "com.orbitspace.modules.file.FileAutoConfiguration",
    "com.orbitspace.modules.integration.IntegrationAutoConfiguration"
})
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@EnableScheduling
public class P2LifecycleGuardiansAutoConfiguration {

    @Bean
    P2LifecycleGuardians p2LifecycleGuardians(
            ObjectProvider<ResourceLifecycleHook> lifecycleHooksProvider,
            ObjectProvider<OutboxPublisherPort> outboxPublisherProvider,
            ObjectProvider<AsyncJobProcessor> asyncJobProcessorsProvider,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${orbit.guardians.job-pending-grace:PT60S}") Duration pendingJobGrace) {
        return new P2LifecycleGuardians(
                lifecycleHooksProvider,
                outboxPublisherProvider,
                asyncJobProcessorsProvider,
                jdbc,
                transactionManager,
                pendingJobGrace);
    }

    public static class P2LifecycleGuardians {
        private static final Logger log = LoggerFactory.getLogger(P2LifecycleGuardians.class);
        private static final int BATCH_LIMIT = 200;
        private static final int DELETE_IN_CHUNK_SIZE = 500;
        private static final int MAX_JOB_ATTEMPTS = 8;
        private static final long RECOVERY_DISPATCH_BUCKET_SECONDS = 30L;
        private static final String EVENT_JOB_READY = "PLATFORM_JOB_READY";
        private static final String AGGREGATE_ASYNC_JOB = "ASYNC_JOB";

        private record LockedUploadSession(
                String uploadId,
                String status,
                String quotaReservationId,
                boolean expired) {}

        private record LockedBackfillSession(
                String backfillId,
                String status,
                boolean expired) {}

        private record LockedFileShare(
                String shareId,
                String sourceSpaceId,
                boolean revoked,
                boolean expired) {}

        private record LockedAsyncJob(
                String jobId,
                String jobType,
                String status,
                int retries,
                boolean leaseExpired,
                boolean retryDue,
                boolean pendingStale) {}

        private enum JobRecoveryOutcome {
            SKIPPED,
            FAILED_EXHAUSTED,
            LEASE_RECOVERED_AND_DISPATCHED,
            LEASE_RECOVERED_DEDUPED,
            REDISPATCHED
        }

        private final ObjectProvider<ResourceLifecycleHook> lifecycleHooksProvider;
        private final ObjectProvider<OutboxPublisherPort> outboxPublisherProvider;
        private final ObjectProvider<AsyncJobProcessor> asyncJobProcessorsProvider;
        private final JdbcTemplate jdbc;
        private final TransactionTemplate tx;
        private final Duration pendingJobGrace;

        P2LifecycleGuardians(
                ObjectProvider<ResourceLifecycleHook> lifecycleHooksProvider,
                ObjectProvider<OutboxPublisherPort> outboxPublisherProvider,
                ObjectProvider<AsyncJobProcessor> asyncJobProcessorsProvider,
                JdbcTemplate jdbc,
                PlatformTransactionManager transactionManager,
                Duration pendingJobGrace) {
            this.lifecycleHooksProvider = Objects.requireNonNull(lifecycleHooksProvider);
            this.outboxPublisherProvider = Objects.requireNonNull(outboxPublisherProvider);
            this.asyncJobProcessorsProvider = Objects.requireNonNull(asyncJobProcessorsProvider);
            this.jdbc = Objects.requireNonNull(jdbc);
            this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager));
            this.pendingJobGrace = requirePositiveDuration(pendingJobGrace, "Pending job grace");
        }

        /**
         * Cleans up expired chunked upload sessions, detaches upload part references, releases
         * expired quota reservations, and sweeps expired Git content backfill sessions.
         */
        @Scheduled(fixedDelayString = "${orbit.guardians.upload-cleanup-interval:PT5M}")
        public void cleanupExpiredUploadSessions() {
            List<String> uploadIds = jdbc.queryForList(
                    """
                    SELECT upload_id
                    FROM upload_sessions
                    WHERE status IN ('INITIATED', 'UPLOADING')
                      AND expires_at <= CURRENT_TIMESTAMP(6)
                    ORDER BY expires_at ASC, upload_id ASC
                    LIMIT ?
                    """,
                    String.class,
                    BATCH_LIMIT);
            int expiredUploads = 0;
            for (String uploadId : uploadIds) {
                if (Boolean.TRUE.equals(
                        tx.execute(status -> expireUploadSessionLocked(uploadId)))) {
                    expiredUploads++;
                }
            }

            Integer danglingReservations = tx.execute(status -> jdbc.update(
                    """
                    UPDATE quota_reservations
                    SET status = 'EXPIRED'
                    WHERE status = 'ACTIVE'
                      AND expires_at <= CURRENT_TIMESTAMP(6)
                    ORDER BY expires_at ASC, reservation_id ASC
                    LIMIT ?
                    """,
                    BATCH_LIMIT));
            int expiredReservations = danglingReservations != null ? danglingReservations : 0;

            int expiredBackfills = 0;
            if (tableExists("git_backfill_sessions")) {
                boolean hasStagedUploadsTable = tableExists("git_backfill_staged_uploads");
                List<String> backfillIds = jdbc.queryForList(
                        """
                        SELECT backfill_id
                        FROM git_backfill_sessions
                        WHERE status = 'OPEN'
                          AND expires_at <= CURRENT_TIMESTAMP(6)
                        ORDER BY expires_at ASC, backfill_id ASC
                        LIMIT ?
                        """,
                        String.class,
                        BATCH_LIMIT);
                for (String backfillId : backfillIds) {
                    if (Boolean.TRUE.equals(
                            tx.execute(
                                    status ->
                                            expireBackfillSessionLocked(
                                                    backfillId, hasStagedUploadsTable)))) {
                        expiredBackfills++;
                    }
                }
            }

            if (expiredUploads > 0 || expiredReservations > 0 || expiredBackfills > 0) {
                log.info(
                        "P2 upload/backfill cleanup completed (expiredUploads={}, expiredReservations={}, expiredBackfills={})",
                        expiredUploads,
                        expiredReservations,
                        expiredBackfills);
            }
        }

        /**
         * Closes expired independent airdrop file shares, revokes their active download sessions,
         * and notifies {@link ResourceLifecycleHook} beans so referenced chat cards invalidate.
         */
        @Scheduled(fixedDelayString = "${orbit.guardians.share-expiration-interval:PT5M}")
        public void closeExpiredFileShares() {
            List<String> shareIds = jdbc.queryForList(
                    """
                    SELECT share_id
                    FROM file_shares
                    WHERE revoked = FALSE
                      AND expires_at IS NOT NULL
                      AND expires_at <= CURRENT_TIMESTAMP(6)
                    ORDER BY expires_at ASC, share_id ASC
                    LIMIT ?
                    """,
                    String.class,
                    BATCH_LIMIT);
            if (shareIds.isEmpty()) {
                return;
            }
            int closed = 0;
            for (String shareId : shareIds) {
                if (Boolean.TRUE.equals(tx.execute(status -> closeExpiredShareLocked(shareId)))) {
                    closed++;
                }
            }
            if (closed > 0) {
                log.info("Closed {} expired airdrop file share(s)", closed);
            }
        }

        /**
         * Recovers stale external worker async jobs (lease-timed-out RUNNING, due RETRY_WAIT, or
         * stuck PENDING) and re-dispatches {@code PLATFORM_JOB_READY} via the outbox without
         * pushing {@code next_retry_at} into the future.
         */
        @Scheduled(fixedDelayString = "${orbit.guardians.job-recovery-interval:PT30S}")
        public void recoverStaleAsyncJobs() {
            OutboxPublisherPort outbox = outboxPublisherProvider.getIfAvailable();
            if (outbox == null) {
                log.warn("Skipping P2 stale async job recovery: OutboxPublisherPort bean unavailable");
                return;
            }

            Set<String> inProcessTypes = asyncJobProcessorsProvider.orderedStream()
                    .map(AsyncJobProcessor::jobType)
                    .filter(type -> type != null && !type.isBlank())
                    .collect(Collectors.toSet());
            long pendingGraceSeconds = Math.max(1L, pendingJobGrace.toSeconds());

            List<String> candidateIds = findStaleExternalJobs(inProcessTypes, pendingGraceSeconds);
            if (candidateIds.isEmpty()) {
                return;
            }

            int leaseRecovered = 0;
            int redispatched = 0;
            int exhausted = 0;
            for (String jobId : candidateIds) {
                JobRecoveryOutcome outcome = tx.execute(
                        status ->
                                recoverJobLocked(jobId, inProcessTypes, pendingGraceSeconds, outbox));
                if (outcome == null) {
                    continue;
                }
                switch (outcome) {
                    case FAILED_EXHAUSTED -> exhausted++;
                    case LEASE_RECOVERED_AND_DISPATCHED -> {
                        leaseRecovered++;
                        redispatched++;
                    }
                    case LEASE_RECOVERED_DEDUPED -> leaseRecovered++;
                    case REDISPATCHED -> redispatched++;
                    case SKIPPED -> {}
                }
            }

            if (leaseRecovered > 0 || redispatched > 0 || exhausted > 0) {
                log.info(
                        "P2 async job recovery completed (leaseRecovered={}, redispatched={}, exhausted={})",
                        leaseRecovered,
                        redispatched,
                        exhausted);
            }
        }

        private boolean expireUploadSessionLocked(String uploadId) {
            LockedUploadSession session = jdbc.query(
                            """
                            SELECT upload_id, status, quota_reservation_id,
                                   (expires_at <= CURRENT_TIMESTAMP(6)) AS is_expired
                            FROM upload_sessions
                            WHERE upload_id = ?
                            FOR UPDATE
                            """,
                            (rs, i) -> new LockedUploadSession(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getBoolean(4)),
                            uploadId)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (session == null
                    || !session.expired()
                    || (!"INITIATED".equals(session.status())
                            && !"UPLOADING".equals(session.status()))) {
                return false;
            }

            int updated = jdbc.update(
                    """
                    UPDATE upload_sessions
                    SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP(6)
                    WHERE upload_id = ? AND status IN ('INITIATED', 'UPLOADING')
                    """,
                    uploadId);
            if (updated != 1) {
                return false;
            }

            List<Integer> partNumbers = jdbc.queryForList(
                    """
                    SELECT part_number
                    FROM upload_parts
                    WHERE upload_id = ?
                    ORDER BY part_number ASC
                    """,
                    Integer.class,
                    uploadId);
            if (!partNumbers.isEmpty()) {
                List<String> ownerIds = new ArrayList<>(partNumbers.size());
                for (Integer partNumber : partNumbers) {
                    if (partNumber != null) {
                        ownerIds.add(uploadId + ":" + partNumber);
                    }
                }
                deleteUploadPartReferences(ownerIds);
            }

            jdbc.update("DELETE FROM upload_parts WHERE upload_id = ?", uploadId);

            if (session.quotaReservationId() != null && !session.quotaReservationId().isBlank()) {
                jdbc.update(
                        """
                        UPDATE quota_reservations
                        SET status = 'EXPIRED'
                        WHERE reservation_id = ? AND status = 'ACTIVE'
                        """,
                        session.quotaReservationId());
            }
            return true;
        }

        private void deleteUploadPartReferences(List<String> ownerIds) {
            for (int start = 0; start < ownerIds.size(); start += DELETE_IN_CHUNK_SIZE) {
                List<String> chunk =
                        ownerIds.subList(start, Math.min(ownerIds.size(), start + DELETE_IN_CHUNK_SIZE));
                String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
                jdbc.update(
                        "DELETE FROM storage_object_references WHERE owner_type = 'UPLOAD_PART' AND owner_id IN ("
                                + placeholders
                                + ")",
                        chunk.toArray());
            }
        }

        private boolean expireBackfillSessionLocked(
                String backfillId, boolean hasStagedUploadsTable) {
            LockedBackfillSession session = jdbc.query(
                            """
                            SELECT backfill_id, status,
                                   (expires_at <= CURRENT_TIMESTAMP(6)) AS is_expired
                            FROM git_backfill_sessions
                            WHERE backfill_id = ?
                            FOR UPDATE
                            """,
                            (rs, i) -> new LockedBackfillSession(
                                    rs.getString(1), rs.getString(2), rs.getBoolean(3)),
                            backfillId)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (session == null || !session.expired() || !"OPEN".equals(session.status())) {
                return false;
            }

            int updated = jdbc.update(
                    """
                    UPDATE git_backfill_sessions
                    SET status = 'EXPIRED'
                    WHERE backfill_id = ? AND status = 'OPEN'
                    """,
                    backfillId);
            if (updated != 1) {
                return false;
            }

            if (hasStagedUploadsTable) {
                jdbc.update(
                        "DELETE FROM git_backfill_staged_uploads WHERE backfill_id = ?",
                        backfillId);
            }
            return true;
        }

        private boolean closeExpiredShareLocked(String shareId) {
            LockedFileShare share = jdbc.query(
                            """
                            SELECT share_id, source_space_id, revoked,
                                   (expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP(6)) AS is_expired
                            FROM file_shares
                            WHERE share_id = ?
                            FOR UPDATE
                            """,
                            (rs, i) -> new LockedFileShare(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getBoolean(3),
                                    rs.getBoolean(4)),
                            shareId)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (share == null || share.revoked() || !share.expired()) {
                return false;
            }

            int updated = jdbc.update(
                    """
                    UPDATE file_shares
                    SET revoked = TRUE,
                        revoked_at = CURRENT_TIMESTAMP(6),
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE share_id = ? AND revoked = FALSE
                    """,
                    shareId);
            if (updated != 1) {
                return false;
            }

            jdbc.update(
                    """
                    UPDATE download_sessions
                    SET revoked_at = CURRENT_TIMESTAMP(6)
                    WHERE grant_type = 'AIRDROP_SHARE'
                      AND grant_id = ?
                      AND revoked_at IS NULL
                    """,
                    shareId);

            String spaceId =
                    (share.sourceSpaceId() != null && !share.sourceSpaceId().isBlank())
                            ? share.sourceSpaceId()
                            : "*";
            var change = new ResourceLifecycleHook.Change(
                    spaceId,
                    new ResourceCardVerifierPort.Reference(
                            MessageKind.FILE_REF, null, shareId, GrantType.AIRDROP_SHARE),
                    ResourceLifecycleState.EXPIRED_OR_DELETED,
                    1L);
            for (ResourceLifecycleHook hook : lifecycleHooksProvider.orderedStream().toList()) {
                hook.onLifecycleChanged(change);
            }
            return true;
        }

        private List<String> findStaleExternalJobs(
                Set<String> inProcessTypes, long pendingGraceSeconds) {
            StringBuilder sql = new StringBuilder(
                    """
                    SELECT job_id
                    FROM async_jobs
                    WHERE
                    """);
            List<Object> params = new ArrayList<>();
            if (!inProcessTypes.isEmpty()) {
                List<String> excluded = inProcessTypes.stream().sorted().toList();
                String placeholders = String.join(",", Collections.nCopies(excluded.size(), "?"));
                sql.append(" job_type NOT IN (").append(placeholders).append(") AND ");
                params.addAll(excluded);
            }
            sql.append(
                    """
                    (
                        (status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until <= CURRENT_TIMESTAMP(6))
                        OR (status = 'RETRY_WAIT' AND next_retry_at <= CURRENT_TIMESTAMP(6))
                        OR (status = 'PENDING' AND next_retry_at <= TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)))
                    )
                    ORDER BY next_retry_at ASC, job_id ASC
                    LIMIT ?
                    """);
            params.add(-pendingGraceSeconds);
            params.add(BATCH_LIMIT);
            return jdbc.queryForList(sql.toString(), String.class, params.toArray());
        }

        private JobRecoveryOutcome recoverJobLocked(
                String jobId,
                Set<String> inProcessTypes,
                long pendingGraceSeconds,
                OutboxPublisherPort outbox) {
            LockedAsyncJob job = jdbc.query(
                            """
                            SELECT job_id, job_type, status, retries,
                                   (lease_until IS NOT NULL AND lease_until <= CURRENT_TIMESTAMP(6)) AS lease_expired,
                                   (next_retry_at <= CURRENT_TIMESTAMP(6)) AS retry_due,
                                   (next_retry_at <= TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))) AS pending_stale
                            FROM async_jobs
                            WHERE job_id = ?
                            FOR UPDATE
                            """,
                            (rs, i) -> new LockedAsyncJob(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getInt(4),
                                    rs.getBoolean(5),
                                    rs.getBoolean(6),
                                    rs.getBoolean(7)),
                            -pendingGraceSeconds,
                            jobId)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (job == null || inProcessTypes.contains(job.jobType())) {
                return JobRecoveryOutcome.SKIPPED;
            }

            boolean runningExpired = "RUNNING".equals(job.status()) && job.leaseExpired();
            boolean retryDue = "RETRY_WAIT".equals(job.status()) && job.retryDue();
            boolean pendingStale = "PENDING".equals(job.status()) && job.pendingStale();
            if (!runningExpired && !retryDue && !pendingStale) {
                return JobRecoveryOutcome.SKIPPED;
            }

            if (job.retries() >= MAX_JOB_ATTEMPTS) {
                int failed = jdbc.update(
                        """
                        UPDATE async_jobs
                        SET status = 'FAILED',
                            error_summary = 'LEASE_RETRIES_EXHAUSTED',
                            lease_holder = NULL,
                            lease_until = NULL
                        WHERE job_id = ? AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                        """,
                        jobId);
                if (failed != 1) {
                    return JobRecoveryOutcome.SKIPPED;
                }
                jdbc.update(
                        """
                        DELETE FROM storage_object_references
                        WHERE owner_type = 'ASYNC_INPUT' AND owner_id = ?
                        """,
                        jobId);
                return JobRecoveryOutcome.FAILED_EXHAUSTED;
            }

            if (runningExpired) {
                int recovered = jdbc.update(
                        """
                        UPDATE async_jobs
                        SET status = 'RETRY_WAIT',
                            lease_holder = NULL,
                            lease_until = NULL,
                            error_summary = 'LEASE_TIMEOUT_RECOVERED',
                            next_retry_at = CURRENT_TIMESTAMP(6)
                        WHERE job_id = ?
                          AND status = 'RUNNING'
                          AND lease_until IS NOT NULL
                          AND lease_until <= CURRENT_TIMESTAMP(6)
                        """,
                        jobId);
                if (recovered != 1) {
                    return JobRecoveryOutcome.SKIPPED;
                }
            }

            long bucket = Instant.now().getEpochSecond() / RECOVERY_DISPATCH_BUCKET_SECONDS;
            Instant bucketTime = Instant.ofEpochSecond(bucket * RECOVERY_DISPATCH_BUCKET_SECONDS);
            String eventId = "job-recover-" + sha256Hex(jobId + ":" + job.retries() + ":" + bucket);
            boolean alreadyQueued = Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM outbox_events WHERE event_id = ?)",
                            Boolean.class,
                            eventId));
            if (alreadyQueued) {
                return runningExpired
                        ? JobRecoveryOutcome.LEASE_RECOVERED_DEDUPED
                        : JobRecoveryOutcome.SKIPPED;
            }

            outbox.publish(
                    new OutboxPublisherPort.Event(
                            eventId,
                            EVENT_JOB_READY,
                            AGGREGATE_ASYNC_JOB,
                            jobId,
                            "{}",
                            bucketTime));
            return runningExpired
                    ? JobRecoveryOutcome.LEASE_RECOVERED_AND_DISPATCHED
                    : JobRecoveryOutcome.REDISPATCHED;
        }

        private boolean tableExists(String tableName) {
            return Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            """
                            SELECT EXISTS(
                                SELECT 1 FROM information_schema.tables
                                WHERE table_schema = DATABASE() AND table_name = ?
                            )
                            """,
                            Boolean.class,
                            tableName));
        }

        private static String sha256Hex(String value) {
            try {
                byte[] digest =
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }

        private static Duration requirePositiveDuration(Duration value, String label) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(label + " must be positive");
            }
            return value;
        }
    }
}
