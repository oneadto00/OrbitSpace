package com.orbitspace.app.config;

import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.MessageKind;
import com.orbitspace.kernel.ObjectStoragePort;
import com.orbitspace.kernel.ResourceCardVerifierPort;
import com.orbitspace.kernel.ResourceLifecycleHook;
import com.orbitspace.kernel.ResourceLifecycleState;
import com.orbitspace.kernel.RevocationLedgerPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Configures P1 startup revocation ledger verification and scheduled background lifecycle guardians.
 * Runs strictly after platform and domain module auto-configurations so {@code RecoveryLedger}
 * completes replay before the startup gate certifies all replay beans and the ledger high-water mark.
 */
@AutoConfiguration(afterName = {
    "com.orbitspace.platform.PlatformAutoConfiguration",
    "com.orbitspace.modules.identity.IdentityAutoConfiguration",
    "com.orbitspace.modules.space.SpaceAutoConfiguration",
    "com.orbitspace.modules.gitproject.GitProjectAutoConfiguration",
    "com.orbitspace.modules.chat.ChatAutoConfiguration"
})
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@EnableScheduling
public class P1LifecycleGuardiansAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(P1LifecycleGuardiansAutoConfiguration.class);
    private static final String REPLAY_BEAN_PREFIX = "orbitReplay";

    /**
     * Fails startup closed unless every {@link RevocationLedgerPort.Target} replay handler bean is
     * registered and {@link RevocationLedgerPort#verifiedHighWaterMark()} certifies journal completeness.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SmartInitializingSingleton p1RevocationLedgerStartupGate(
            ObjectProvider<RevocationLedgerPort> revocationLedgerProvider,
            ListableBeanFactory beanFactory) {
        return () -> {
            RevocationLedgerPort.Target[] targets = RevocationLedgerPort.Target.values();
            for (RevocationLedgerPort.Target target : targets) {
                String beanName = REPLAY_BEAN_PREFIX + target.name();
                if (!beanFactory.containsBean(beanName)) {
                    throw new IllegalStateException(
                            "Missing required revocation replay bean: " + beanName);
                }
                beanFactory.getBean(beanName, Consumer.class);
            }
            RevocationLedgerPort ledger = revocationLedgerProvider.getIfAvailable();
            if (ledger == null) {
                throw new IllegalStateException(
                        "RevocationLedgerPort bean is required for startup verification");
            }
            long highWaterMark = ledger.verifiedHighWaterMark();
            log.info(
                    "Startup revocation ledger gate verified (targets={}, highWaterMark={})",
                    targets.length,
                    highWaterMark);
        };
    }

    @Bean
    P1LifecycleGuardians p1LifecycleGuardians(
            ObjectProvider<ObjectStoragePort> objectStorageProvider,
            ObjectProvider<ResourceLifecycleHook> lifecycleHooksProvider,
            ObjectProvider<AsyncJobPort> asyncJobProvider,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${orbit.guardians.wip-retention:30d}") Duration wipRetention,
            @Value("${orbit.platform.object-grace:7d}") Duration objectGrace) {
        return new P1LifecycleGuardians(
                objectStorageProvider,
                lifecycleHooksProvider,
                asyncJobProvider,
                jdbc,
                transactionManager,
                wipRetention,
                objectGrace);
    }

    /** Scheduled P1 background guardians for WIP staleness, WIP expiration, and storage GC audits. */
    public static class P1LifecycleGuardians {
        private static final Logger log = LoggerFactory.getLogger(P1LifecycleGuardians.class);
        private static final int EXPIRE_BATCH_LIMIT = 500;
        private static final int STORAGE_SCAN_PAGE_SIZE = 100;
        private static final int STORAGE_SCAN_MAX_PAGES = 10;

        private record LockedSnapshot(
                String id,
                String spaceId,
                String projectId,
                String lifecycleState,
                long lifecycleVersion,
                long referenceCount,
                Timestamp createdAt) {}

        private final ObjectProvider<ObjectStoragePort> objectStorageProvider;
        private final ObjectProvider<ResourceLifecycleHook> lifecycleHooksProvider;
        private final ObjectProvider<AsyncJobPort> asyncJobProvider;
        private final JdbcTemplate jdbc;
        private final TransactionTemplate tx;
        private final Duration wipRetention;
        private final Duration objectGrace;

        P1LifecycleGuardians(
                ObjectProvider<ObjectStoragePort> objectStorageProvider,
                ObjectProvider<ResourceLifecycleHook> lifecycleHooksProvider,
                ObjectProvider<AsyncJobPort> asyncJobProvider,
                JdbcTemplate jdbc,
                PlatformTransactionManager transactionManager,
                Duration wipRetention,
                Duration objectGrace) {
            this.objectStorageProvider = Objects.requireNonNull(objectStorageProvider);
            this.lifecycleHooksProvider = Objects.requireNonNull(lifecycleHooksProvider);
            this.asyncJobProvider = Objects.requireNonNull(asyncJobProvider);
            this.jdbc = Objects.requireNonNull(jdbc);
            this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager));
            this.wipRetention = requirePositiveDuration(wipRetention, "WIP retention");
            this.objectGrace = requirePositiveDuration(objectGrace, "Object GC grace");
        }

        /** Marks unrefreshed ACTIVE worktree WIP snapshots past {@code stale_after_seconds} as STALE. */
        @Scheduled(fixedDelayString = "${orbit.guardians.wip-staleness-interval:PT5M}")
        public void sweepStaleWorktreeSnapshots() {
            Integer updated = tx.execute(status -> jdbc.update(
                    """
                    UPDATE wip_snapshots s
                    JOIN worktree_states w ON w.id = s.worktree_id
                    SET s.freshness = 'STALE'
                    WHERE s.lifecycle_state = 'ACTIVE'
                      AND s.freshness = 'ACTIVE'
                      AND TIMESTAMPDIFF(
                          SECOND,
                          COALESCE(w.last_reported_at, s.captured_at),
                          CURRENT_TIMESTAMP(6)
                      ) >= w.stale_after_seconds
                    """));
            int count = updated != null ? updated : 0;
            if (count > 0) {
                log.info("Marked {} unrefreshed active WIP snapshot(s) STALE", count);
            }
        }

        /**
         * Expires unreferenced ACTIVE WIP snapshots older than {@code wipRetention}, detaching object
         * references, cancelling active parse jobs, and notifying {@link ResourceLifecycleHook} beans.
         */
        @Scheduled(fixedDelayString = "${orbit.guardians.wip-expiration-interval:PT10M}")
        public void cleanupExpiredWipSnapshots() {
            Instant cutoffInstant = Instant.now().minus(wipRetention).truncatedTo(ChronoUnit.MICROS);
            Timestamp cutoff = Timestamp.from(cutoffInstant);
            List<String> candidateIds = jdbc.queryForList(
                    """
                    SELECT id
                    FROM wip_snapshots
                    WHERE lifecycle_state = 'ACTIVE'
                      AND reference_count = 0
                      AND created_at <= ?
                    ORDER BY created_at ASC, id ASC
                    LIMIT ?
                    """,
                    String.class,
                    cutoff,
                    EXPIRE_BATCH_LIMIT);
            if (candidateIds.isEmpty()) {
                return;
            }
            int expired = 0;
            for (String snapshotId : candidateIds) {
                if (Boolean.TRUE.equals(
                        tx.execute(status -> expireSnapshotLocked(snapshotId, cutoffInstant)))) {
                    expired++;
                }
            }
            if (expired > 0) {
                log.info(
                        "Expired {} unreferenced WIP snapshot(s) past retention {}",
                        expired,
                        wipRetention);
            }
        }

        /** Scans and collects unreferenced S3/MinIO objects past grace period, logging summary counts. */
        @Scheduled(fixedDelayString = "${orbit.guardians.storage-gc-interval:PT15M}")
        public void auditAndCollectUnreferencedStorageObjects() {
            ObjectStoragePort storage = objectStorageProvider.getIfAvailable();
            if (storage == null) {
                log.warn("Skipping storage orphan GC audit check: ObjectStoragePort bean unavailable");
                return;
            }
            Instant cutoff = Instant.now().minus(objectGrace);
            String cursor = null;
            int pages = 0;
            int scanned = 0;
            int deleted = 0;
            int skipped = 0;
            int failed = 0;
            do {
                ObjectStoragePort.Candidates batch =
                        storage.scanUnreferenced(cutoff, cursor, STORAGE_SCAN_PAGE_SIZE);
                pages++;
                if (batch == null || batch.objects().isEmpty()) {
                    break;
                }
                for (ObjectStoragePort.Candidate candidate : batch.objects()) {
                    scanned++;
                    try {
                        if (storage.deleteIfStillUnreferenced(candidate, cutoff)) {
                            deleted++;
                        } else {
                            skipped++;
                        }
                    } catch (IOException ex) {
                        failed++;
                        log.warn(
                                "Storage orphan GC deletion failed (objectKey={}, cause={})",
                                candidate.objectKey(),
                                ex.getClass().getSimpleName());
                    }
                }
                cursor = batch.nextCursor();
            } while (cursor != null && pages < STORAGE_SCAN_MAX_PAGES);

            log.info(
                    "Storage orphan GC audit completed (scanned={}, deleted={}, skipped={}, failed={}, pages={})",
                    scanned,
                    deleted,
                    skipped,
                    failed,
                    pages);
        }

        private boolean expireSnapshotLocked(String snapshotId, Instant cutoff) {
            LockedSnapshot snap = jdbc.query(
                            """
                            SELECT id, space_id, project_id, lifecycle_state, lifecycle_version,
                                   reference_count, created_at
                            FROM wip_snapshots
                            WHERE id = ?
                            FOR UPDATE
                            """,
                            (rs, i) -> new LockedSnapshot(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getString(4),
                                    rs.getLong(5),
                                    rs.getLong(6),
                                    rs.getTimestamp(7)),
                            snapshotId)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (snap == null
                    || !"ACTIVE".equals(snap.lifecycleState())
                    || snap.referenceCount() != 0
                    || snap.createdAt() == null
                    || snap.createdAt().toInstant().isAfter(cutoff)) {
                return false;
            }

            long nextVersion = snap.lifecycleVersion() + 1;
            int updated = jdbc.update(
                    """
                    UPDATE wip_snapshots
                    SET lifecycle_state = 'EXPIRED_OR_DELETED',
                        lifecycle_version = ?,
                        parse_state = CASE
                            WHEN parse_state IN ('PENDING', 'RUNNING', 'RETRY_WAIT') THEN 'CANCELLED'
                            ELSE parse_state
                        END
                    WHERE id = ? AND lifecycle_state = 'ACTIVE' AND reference_count = 0
                    """,
                    nextVersion,
                    snapshotId);
            if (updated != 1) {
                return false;
            }

            jdbc.update(
                    "UPDATE worktree_states SET active_wip_snapshot_id = NULL WHERE active_wip_snapshot_id = ?",
                    snapshotId);
            jdbc.update(
                    """
                    DELETE FROM storage_object_references
                    WHERE owner_type IN ('WIP_PATCH', 'WIP_PARSE_RESULT') AND owner_id = ?
                    """,
                    snapshotId);
            cancelActiveJobs(snapshotId, snap.spaceId());
            notifyLifecycleHooks(snap.spaceId(), snap.projectId(), snapshotId, nextVersion);
            return true;
        }

        private void cancelActiveJobs(String snapshotId, String spaceId) {
            List<String> activeJobIds = jdbc.queryForList(
                    """
                    SELECT job_id
                    FROM async_jobs
                    WHERE resource_id = ? AND space_id = ?
                      AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                    """,
                    String.class,
                    snapshotId,
                    spaceId);
            if (activeJobIds.isEmpty()) {
                return;
            }
            AsyncJobPort asyncJobs = asyncJobProvider.getIfAvailable();
            for (String jobId : activeJobIds) {
                if (asyncJobs != null) {
                    asyncJobs.cancel(jobId);
                    continue;
                }
                int cancelled = jdbc.update(
                        """
                        UPDATE async_jobs
                        SET status = 'CANCELLED', lease_holder = NULL, lease_until = NULL
                        WHERE job_id = ? AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                        """,
                        jobId);
                if (cancelled != 0) {
                    jdbc.update(
                            "DELETE FROM storage_object_references WHERE owner_type = 'ASYNC_INPUT' AND owner_id = ?",
                            jobId);
                }
            }
        }

        private void notifyLifecycleHooks(
                String spaceId, String projectId, String snapshotId, long lifecycleVersion) {
            var change = new ResourceLifecycleHook.Change(
                    spaceId,
                    new ResourceCardVerifierPort.Reference(
                            MessageKind.WIP_REF, projectId, snapshotId),
                    ResourceLifecycleState.EXPIRED_OR_DELETED,
                    lifecycleVersion);
            for (ResourceLifecycleHook hook : lifecycleHooksProvider.orderedStream().toList()) {
                hook.onLifecycleChanged(change);
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
