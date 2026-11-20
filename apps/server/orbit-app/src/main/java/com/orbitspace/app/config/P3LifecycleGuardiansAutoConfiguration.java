package com.orbitspace.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.ObjectStoragePort;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Configures P3 scheduled lifecycle guardians for collaborative document full CRDT snapshot
 * solidification and retention-window cleanup of unreferenced historical update batches and
 * superseded AUTO versions, strictly respecting active document load boundaries.
 */
@AutoConfiguration(afterName = {
    "com.orbitspace.platform.PlatformAutoConfiguration",
    "com.orbitspace.modules.identity.IdentityAutoConfiguration",
    "com.orbitspace.modules.space.SpaceAutoConfiguration",
    "com.orbitspace.modules.gitproject.GitProjectAutoConfiguration",
    "com.orbitspace.modules.chat.ChatAutoConfiguration",
    "com.orbitspace.modules.friendship.FriendshipAutoConfiguration",
    "com.orbitspace.modules.file.FileAutoConfiguration",
    "com.orbitspace.modules.integration.IntegrationAutoConfiguration",
    "com.orbitspace.modules.document.DocumentAutoConfiguration"
})
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@EnableScheduling
public class P3LifecycleGuardiansAutoConfiguration {

    @Bean
    P3LifecycleGuardians p3LifecycleGuardians(
            ObjectProvider<ObjectStoragePort> objectStorageProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            @Value("${ORBIT_INTERNAL_HMAC_SECRET:local-only-internal-hmac-secret}")
                    String internalHmacSecret,
            @Value(
                            "${orbit.document.transform-url:http://collab-hocuspocus:1234/internal/v1/document-transform}")
                    URI transformEndpoint,
            @Value("${orbit.guardians.doc-batch-retention:7d}") Duration docBatchRetention,
            @Value("${orbit.guardians.doc-auto-version-retention:30d}")
                    Duration docAutoVersionRetention) {
        return new P3LifecycleGuardians(
                objectStorageProvider,
                meterRegistryProvider,
                jdbc,
                transactionManager,
                objectMapper,
                internalHmacSecret,
                transformEndpoint,
                docBatchRetention,
                docAutoVersionRetention);
    }

    /** Reconstructs the Y.Doc before snapshotting. Retention pruning uses the persisted load boundary. */
    public static class P3LifecycleGuardians {
        private static final Logger log = LoggerFactory.getLogger(P3LifecycleGuardians.class);
        private static final int DOC_BATCH_LIMIT = 32;
        private static final int PRUNE_DOC_LIMIT = 64;
        private static final int PRUNE_CHUNK_SIZE = 200;
        private static final int MAX_BATCH_BYTES = 6 * 1024 * 1024;
        private static final int MAX_STATE_BYTES = 48 * 1024 * 1024;
        private static final long COMPACT_INPUT_CHAR_THRESHOLD = 56L * 1024 * 1024;

        private record DocState(
                String id,
                String spaceId,
                long persistedBatchSeq,
                long snapshotSeq,
                String snapshotObjectKey,
                String lifecycleState) {}

        private record BatchRef(long batchSeq, String objectKey) {}

        private record VersionCandidate(
                String versionId, String documentId, long persistedBatchSeq) {}

        private final ObjectProvider<ObjectStoragePort> objectStorageProvider;
        private final ObjectProvider<MeterRegistry> meterRegistryProvider;
        private final JdbcTemplate jdbc;
        private final TransactionTemplate tx;
        private final TransactionTemplate storageIO;
        private final ObjectMapper objectMapper;
        private final byte[] hmacSecret;
        private final URI transformEndpoint;
        private final Duration docBatchRetention;
        private final Duration docAutoVersionRetention;
        private final HttpClient httpClient;

        P3LifecycleGuardians(
                ObjectProvider<ObjectStoragePort> objectStorageProvider,
                ObjectProvider<MeterRegistry> meterRegistryProvider,
                JdbcTemplate jdbc,
                PlatformTransactionManager transactionManager,
                ObjectMapper objectMapper,
                String internalHmacSecret,
                URI transformEndpoint,
                Duration docBatchRetention,
                Duration docAutoVersionRetention) {
            this.objectStorageProvider = Objects.requireNonNull(objectStorageProvider);
            this.meterRegistryProvider = Objects.requireNonNull(meterRegistryProvider);
            this.jdbc = Objects.requireNonNull(jdbc);
            this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager));
            this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            this.storageIO = new TransactionTemplate(transactionManager);
            this.storageIO.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            this.objectMapper = Objects.requireNonNull(objectMapper);
            this.hmacSecret = Objects.requireNonNull(internalHmacSecret)
                    .strip()
                    .getBytes(StandardCharsets.UTF_8);
            if (this.hmacSecret.length == 0) {
                throw new IllegalArgumentException("Internal HMAC secret must not be blank");
            }
            this.transformEndpoint = Objects.requireNonNull(transformEndpoint);
            this.docBatchRetention =
                    requirePositiveDuration(docBatchRetention, "Document batch retention");
            this.docAutoVersionRetention =
                    requirePositiveDuration(
                            docAutoVersionRetention, "Document AUTO version retention");
            this.httpClient =
                    HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(3))
                            .build();
        }

        @Scheduled(fixedDelayString = "${orbit.guardians.doc-snapshot-interval:PT5M}")
        public void solidifyDocumentCrdtSnapshots() {
            ObjectStoragePort storage = objectStorageProvider.getIfAvailable();
            if (storage == null) {
                return;
            }
            List<String> candidateDocIds = jdbc.queryForList(
                    """
                    SELECT id
                    FROM documents
                    WHERE lifecycle_state = 'ACTIVE'
                      AND persisted_batch_seq > snapshot_seq
                    ORDER BY id ASC
                    LIMIT ?
                    """,
                    String.class,
                    DOC_BATCH_LIMIT);
            if (candidateDocIds.isEmpty()) {
                return;
            }

            int solidified = 0;
            int deferred = 0;
            for (String documentId : candidateDocIds) {
                long startedNanos = System.nanoTime();
                try {
                    if (solidifySingleDocument(documentId, storage)) {
                        solidified++;
                        recordTimer(
                                "orbit.document.snapshot.solidify.duration",
                                System.nanoTime() - startedNanos);
                        incrementCounter("orbit.document.snapshot.solidified.total", 1);
                    }
                } catch (RuntimeException ex) {
                    deferred++;
                    log.warn(
                            "P3 CRDT snapshot solidification deferred (documentId={}, cause={})",
                            documentId,
                            ex.getClass().getSimpleName());
                }
            }

            if (solidified > 0 || deferred > 0) {
                log.info(
                        "P3 CRDT snapshot solidification sweep completed (solidified={}, deferred={})",
                        solidified,
                        deferred);
            }
        }

        /**
         * Prunes retention-expired historical update batches strictly covered by solidified load
         * snapshots, unreferenced superseded AUTO versions, expired restore previews, and expired
         * unconsumed collaboration tickets.
         */
        @Scheduled(fixedDelayString = "${orbit.guardians.doc-retention-interval:PT15M}")
        public void pruneExpiredDocumentHistory() {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            Timestamp batchCutoff = Timestamp.from(now.minus(docBatchRetention));
            Timestamp autoVersionCutoff = Timestamp.from(now.minus(docAutoVersionRetention));

            int prunedActiveBatches = pruneCoveredActiveBatches(batchCutoff);
            int prunedDeletedBatches = pruneDeletedDocumentBatches();
            int prunedAutoVersions = pruneSupersededAutoVersions(autoVersionCutoff);
            int expiredPreviews = pruneExpiredRestorePreviews();
            int expiredTickets = pruneExpiredUnconsumedSessions();

            int totalBatches = prunedActiveBatches + prunedDeletedBatches;
            if (totalBatches > 0) {
                incrementCounter("orbit.document.batches.pruned.total", totalBatches);
            }
            if (prunedAutoVersions > 0) {
                incrementCounter("orbit.document.versions.pruned.total", prunedAutoVersions);
            }
            if (totalBatches > 0
                    || prunedAutoVersions > 0
                    || expiredPreviews > 0
                    || expiredTickets > 0) {
                log.info(
                        "P3 document retention cleanup completed (activeBatches={}, deletedDocBatches={}, autoVersions={}, expiredPreviews={}, expiredTickets={})",
                        prunedActiveBatches,
                        prunedDeletedBatches,
                        prunedAutoVersions,
                        expiredPreviews,
                        expiredTickets);
            }
        }

        private boolean solidifySingleDocument(String documentId, ObjectStoragePort storage) {
            DocState current = readDocumentState(documentId);
            if (current == null
                    || !"ACTIVE".equals(current.lifecycleState())
                    || current.persistedBatchSeq() <= current.snapshotSeq()) {
                return false;
            }
            long targetSeq = current.persistedBatchSeq();

            String compactedBase64 = buildFullCrdtSnapshot(current, targetSeq, storage);
            byte[] snapshotBytes = Base64.getDecoder().decode(compactedBase64);
            if (snapshotBytes.length == 0 || snapshotBytes.length > MAX_STATE_BYTES) {
                throw new IllegalStateException("Compacted CRDT snapshot size out of bounds");
            }

            ObjectStoragePort.StoredObject promoted = promoteSnapshot(snapshotBytes, storage);
            return Boolean.TRUE.equals(
                    tx.execute(status -> commitSnapshotLocked(documentId, targetSeq, promoted)));
        }

        private String buildFullCrdtSnapshot(
                DocState doc, long throughSeq, ObjectStoragePort storage) {
            List<Map<String, Object>> latestVersion = jdbc.queryForList(
                    """
                    SELECT persisted_batch_seq, snapshot_object_key
                    FROM document_versions
                    WHERE document_id = ?
                      AND persisted_batch_seq <= ?
                    ORDER BY persisted_batch_seq DESC
                    LIMIT 1
                    """,
                    doc.id(),
                    throughSeq);

            long afterSeq = 0L;
            List<String> inputs = new ArrayList<>();
            Runnable verifyActive = () -> {
                DocState state = readDocumentState(doc.id());
                if (state == null || !"ACTIVE".equals(state.lifecycleState())) {
                    throw new IllegalStateException("Document is no longer active");
                }
            };

            if (!latestVersion.isEmpty()) {
                Map<String, Object> row = latestVersion.getFirst();
                afterSeq = ((Number) row.get("persisted_batch_seq")).longValue();
                String key = (String) row.get("snapshot_object_key");
                inputs.add(readBase64(storage, key, MAX_STATE_BYTES, verifyActive));
            }

            while (afterSeq < throughSeq) {
                List<BatchRef> page = jdbc.query(
                        """
                        SELECT batch_seq, object_key
                        FROM document_update_batches
                        WHERE document_id = ?
                          AND batch_seq > ?
                          AND batch_seq <= ?
                        ORDER BY batch_seq ASC
                        LIMIT 8
                        """,
                        (rs, i) -> new BatchRef(rs.getLong(1), rs.getString(2)),
                        doc.id(),
                        afterSeq,
                        throughSeq);
                if (page.isEmpty()) {
                    throw new IllegalStateException("Incomplete durable batch log");
                }
                for (BatchRef ref : page) {
                    if (ref.batchSeq() != ++afterSeq) {
                        throw new IllegalStateException("Non-contiguous document batch sequence");
                    }
                    inputs.add(readBase64(storage, ref.objectKey(), MAX_BATCH_BYTES, verifyActive));
                    long totalChars = inputs.stream().mapToLong(String::length).sum();
                    if (totalChars >= COMPACT_INPUT_CHAR_THRESHOLD) {
                        String intermediate = requestCrdtSnapshot(inputs);
                        inputs.clear();
                        inputs.add(intermediate);
                    }
                }
                if (afterSeq < throughSeq) {
                    String intermediate = requestCrdtSnapshot(inputs);
                    inputs.clear();
                    inputs.add(intermediate);
                }
            }

            if (inputs.isEmpty()) {
                return "AAA=";
            }
            return requestCrdtSnapshot(inputs);
        }

        private boolean commitSnapshotLocked(
                String documentId, long targetSeq, ObjectStoragePort.StoredObject promoted) {
            DocState locked = jdbc.query(
                            """
                            SELECT id, space_id, persisted_batch_seq, snapshot_seq, snapshot_object_key, lifecycle_state
                            FROM documents
                            WHERE id = ?
                            FOR UPDATE
                            """,
                            (rs, i) -> new DocState(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getLong(3),
                                    rs.getLong(4),
                                    rs.getString(5),
                                    rs.getString(6)),
                            documentId)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (locked == null
                    || !"ACTIVE".equals(locked.lifecycleState())
                    || locked.snapshotSeq() >= targetSeq
                    || locked.persistedBatchSeq() < targetSeq) {
                return false;
            }

            List<Map<String, Object>> existingAtSeq = jdbc.queryForList(
                    """
                    SELECT snapshot_object_key, snapshot_sha256
                    FROM document_versions
                    WHERE document_id = ? AND persisted_batch_seq = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                    """,
                    documentId,
                    targetSeq);

            String objectKey;
            String sha256;
            if (!existingAtSeq.isEmpty()) {
                objectKey = (String) existingAtSeq.getFirst().get("snapshot_object_key");
                sha256 = (String) existingAtSeq.getFirst().get("snapshot_sha256");
            } else {
                objectKey = promoted.objectKey();
                sha256 = promoted.sha256();
                String versionId = UUID.randomUUID().toString();
                jdbc.update(
                        "INSERT INTO storage_object_references(owner_type, owner_id, object_key) VALUES ('DOCUMENT_VERSION', ?, ?)",
                        versionId,
                        objectKey);
                jdbc.update(
                        """
                        INSERT INTO document_versions(id, document_id, persisted_batch_seq, snapshot_object_key, snapshot_sha256, kind)
                        VALUES (?, ?, ?, ?, ?, 'AUTO')
                        """,
                        versionId,
                        documentId,
                        targetSeq,
                        objectKey,
                        sha256);
            }

            int updated = jdbc.update(
                    """
                    UPDATE documents
                    SET snapshot_seq = ?, snapshot_object_key = ?
                    WHERE id = ? AND lifecycle_state = 'ACTIVE' AND snapshot_seq < ?
                    """,
                    targetSeq,
                    objectKey,
                    documentId,
                    targetSeq);
            return updated == 1;
        }

        private int pruneCoveredActiveBatches(Timestamp batchCutoff) {
            List<String> docIds = jdbc.queryForList(
                    """
                    SELECT d.id
                    FROM documents d
                    WHERE d.lifecycle_state = 'ACTIVE'
                      AND d.snapshot_seq > 0
                      AND d.snapshot_object_key IS NOT NULL
                      AND EXISTS (
                          SELECT 1
                          FROM document_update_batches b
                          WHERE b.document_id = d.id
                            AND b.batch_seq <= d.snapshot_seq
                      )
                    ORDER BY d.id ASC
                    LIMIT ?
                    """,
                    String.class,
                    PRUNE_DOC_LIMIT);
            int totalPruned = 0;
            for (String documentId : docIds) {
                Integer pruned = tx.execute(
                        status -> pruneActiveDocumentBatchesLocked(documentId, batchCutoff));
                if (pruned != null) {
                    totalPruned += pruned;
                }
            }
            return totalPruned;
        }

        private int pruneActiveDocumentBatchesLocked(String documentId, Timestamp batchCutoff) {
            DocState locked = jdbc.query(
                            """
                            SELECT id, space_id, persisted_batch_seq, snapshot_seq, snapshot_object_key, lifecycle_state
                            FROM documents
                            WHERE id = ?
                            FOR UPDATE
                            """,
                            (rs, i) -> new DocState(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getLong(3),
                                    rs.getLong(4),
                                    rs.getString(5),
                                    rs.getString(6)),
                            documentId)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (locked == null
                    || !"ACTIVE".equals(locked.lifecycleState())
                    || locked.snapshotSeq() <= 0
                    || locked.snapshotObjectKey() == null) {
                return 0;
            }

            // Only prune batches strictly covered by a solidified snapshot whose version predates
            // the retention cutoff, ensuring active load cursors and recent quotes are never broken.
            Long safeBoundary = jdbc.queryForObject(
                    """
                    SELECT MAX(persisted_batch_seq)
                    FROM document_versions
                    WHERE document_id = ?
                      AND persisted_batch_seq <= ?
                      AND created_at <= ?
                    """,
                    Long.class,
                    documentId,
                    locked.snapshotSeq(),
                    batchCutoff);
            if (safeBoundary == null || safeBoundary <= 0) {
                return 0;
            }

            // Defer pruning if any active restore preview references a base sequence within range.
            Long activePreviewCount = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM document_restore_previews
                    WHERE document_id = ?
                      AND expires_at > CURRENT_TIMESTAMP(6)
                      AND base_seq <= ?
                    """,
                    Long.class,
                    documentId,
                    safeBoundary);
            if (activePreviewCount != null && activePreviewCount > 0) {
                return 0;
            }

            List<Long> batchSeqs = jdbc.queryForList(
                    """
                    SELECT batch_seq
                    FROM document_update_batches
                    WHERE document_id = ?
                      AND batch_seq <= ?
                    ORDER BY batch_seq ASC
                    LIMIT ?
                    """,
                    Long.class,
                    documentId,
                    safeBoundary,
                    PRUNE_CHUNK_SIZE);
            if (batchSeqs.isEmpty()) {
                return 0;
            }

            detachBatchObjectReferences(documentId, batchSeqs);
            long maxDeletedSeq = batchSeqs.getLast();
            return jdbc.update(
                    "DELETE FROM document_update_batches WHERE document_id = ? AND batch_seq <= ?",
                    documentId,
                    maxDeletedSeq);
        }

        private int pruneDeletedDocumentBatches() {
            List<String> deletedDocIds = jdbc.queryForList(
                    """
                    SELECT d.id
                    FROM documents d
                    WHERE d.lifecycle_state IN ('DELETED', 'FORCE_PURGED')
                      AND EXISTS (
                          SELECT 1 FROM document_update_batches b WHERE b.document_id = d.id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM document_sessions s WHERE s.document_id = d.id AND s.revoked = FALSE
                      )
                    ORDER BY d.id ASC
                    LIMIT ?
                    """,
                    String.class,
                    PRUNE_DOC_LIMIT);
            int totalPruned = 0;
            for (String documentId : deletedDocIds) {
                Integer pruned = tx.execute(status -> {
                    String state = jdbc.queryForList(
                                    "SELECT lifecycle_state FROM documents WHERE id = ? FOR UPDATE",
                                    String.class,
                                    documentId)
                            .stream()
                            .findFirst()
                            .orElse(null);
                    if (!"DELETED".equals(state) && !"FORCE_PURGED".equals(state)) {
                        return 0;
                    }
                    List<Long> seqs = jdbc.queryForList(
                            """
                            SELECT batch_seq
                            FROM document_update_batches
                            WHERE document_id = ?
                            ORDER BY batch_seq ASC
                            LIMIT ?
                            """,
                            Long.class,
                            documentId,
                            PRUNE_CHUNK_SIZE);
                    if (seqs.isEmpty()) {
                        return 0;
                    }
                    detachBatchObjectReferences(documentId, seqs);
                    return jdbc.update(
                            "DELETE FROM document_update_batches WHERE document_id = ? AND batch_seq <= ?",
                            documentId,
                            seqs.getLast());
                });
                if (pruned != null) {
                    totalPruned += pruned;
                }
            }
            return totalPruned;
        }

        private int pruneSupersededAutoVersions(Timestamp autoVersionCutoff) {
            List<VersionCandidate> candidates = jdbc.query(
                    """
                    SELECT v.id, v.document_id, v.persisted_batch_seq
                    FROM document_versions v
                    JOIN documents d ON d.id = v.document_id
                    WHERE v.kind = 'AUTO'
                      AND v.created_at <= ?
                      AND v.persisted_batch_seq < d.snapshot_seq
                      AND NOT EXISTS (
                          SELECT 1 FROM document_comments c WHERE c.version_id = v.id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM chat_doc_pdf_cards card WHERE card.persisted_version_id = v.id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM document_restore_previews p WHERE p.version_id = v.id
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM document_update_batches b
                          WHERE b.document_id = v.document_id
                            AND b.batch_seq <= v.persisted_batch_seq
                      )
                    ORDER BY v.created_at ASC, v.id ASC
                    LIMIT ?
                    """,
                    (rs, i) -> new VersionCandidate(rs.getString(1), rs.getString(2), rs.getLong(3)),
                    autoVersionCutoff,
                    PRUNE_DOC_LIMIT);
            int pruned = 0;
            for (VersionCandidate candidate : candidates) {
                Boolean deleted = tx.execute(
                        status -> pruneAutoVersionLocked(candidate, autoVersionCutoff));
                if (Boolean.TRUE.equals(deleted)) {
                    pruned++;
                }
            }
            return pruned;
        }

        private boolean pruneAutoVersionLocked(
                VersionCandidate candidate, Timestamp autoVersionCutoff) {
            Long currentSnapshotSeq = jdbc.queryForList(
                            "SELECT snapshot_seq FROM documents WHERE id = ? FOR UPDATE",
                            Long.class,
                            candidate.documentId())
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (currentSnapshotSeq == null || candidate.persistedBatchSeq() >= currentSnapshotSeq) {
                return false;
            }
            Long stillSafe = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM document_versions v
                    WHERE v.id = ?
                      AND v.kind = 'AUTO'
                      AND v.created_at <= ?
                      AND NOT EXISTS (SELECT 1 FROM document_comments c WHERE c.version_id = v.id)
                      AND NOT EXISTS (SELECT 1 FROM chat_doc_pdf_cards card WHERE card.persisted_version_id = v.id)
                      AND NOT EXISTS (SELECT 1 FROM document_restore_previews p WHERE p.version_id = v.id)
                    """,
                    Long.class,
                    candidate.versionId(),
                    autoVersionCutoff);
            if (stillSafe == null || stillSafe != 1L) {
                return false;
            }
            jdbc.update(
                    "DELETE FROM storage_object_references WHERE owner_type = 'DOCUMENT_VERSION' AND owner_id = ?",
                    candidate.versionId());
            return jdbc.update("DELETE FROM document_versions WHERE id = ?", candidate.versionId())
                    == 1;
        }

        private int pruneExpiredRestorePreviews() {
            Integer deleted = tx.execute(status -> jdbc.update(
                    """
                    DELETE FROM document_restore_previews
                    WHERE expires_at <= TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP(6))
                    LIMIT ?
                    """,
                    PRUNE_CHUNK_SIZE));
            return deleted != null ? deleted : 0;
        }

        private int pruneExpiredUnconsumedSessions() {
            Integer deleted = tx.execute(status -> jdbc.update(
                    """
                    DELETE FROM document_sessions
                    WHERE consumed = FALSE
                      AND (revoked = TRUE OR ticket_expires <= TIMESTAMPADD(HOUR, -24, CURRENT_TIMESTAMP(6)))
                    LIMIT ?
                    """,
                    PRUNE_CHUNK_SIZE));
            return deleted != null ? deleted : 0;
        }

        private void detachBatchObjectReferences(String documentId, List<Long> batchSeqs) {
            List<String> ownerIds = new ArrayList<>(batchSeqs.size());
            for (Long seq : batchSeqs) {
                ownerIds.add(documentId + "/" + seq);
            }
            String placeholders = String.join(",", Collections.nCopies(ownerIds.size(), "?"));
            jdbc.update(
                    "DELETE FROM storage_object_references WHERE owner_type = 'DOCUMENT_BATCH' AND owner_id IN ("
                            + placeholders
                            + ")",
                    ownerIds.toArray());
        }

        private DocState readDocumentState(String documentId) {
            return jdbc.query(
                            """
                            SELECT id, space_id, persisted_batch_seq, snapshot_seq, snapshot_object_key, lifecycle_state
                            FROM documents
                            WHERE id = ?
                            """,
                            (rs, i) -> new DocState(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getLong(3),
                                    rs.getLong(4),
                                    rs.getString(5),
                                    rs.getString(6)),
                            documentId)
                    .stream()
                    .findFirst()
                    .orElse(null);
        }

        private String readBase64(
                ObjectStoragePort storage, String objectKey, int maxBytes, Runnable accessCheck) {
            try (InputStream in = storage.openRead(objectKey, accessCheck)) {
                byte[] bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new IllegalStateException("Stored CRDT payload exceeds limit");
                }
                return Base64.getEncoder().encodeToString(bytes);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read stored CRDT object", ex);
            }
        }

        private ObjectStoragePort.StoredObject promoteSnapshot(
                byte[] data, ObjectStoragePort storage) {
            String digest = sha256Hex(data);
            String immutableKey = "immutable/" + digest;
            List<String> existing = jdbc.queryForList(
                    """
                    SELECT object_key
                    FROM storage_objects
                    WHERE object_key = ? AND size_bytes = ? AND sha256_hex = ?
                      AND status = 'IMMUTABLE' AND reference_count > 0
                    """,
                    String.class,
                    immutableKey,
                    data.length,
                    digest);
            if (!existing.isEmpty()) {
                return new ObjectStoragePort.StoredObject(immutableKey, data.length, digest);
            }
            return storageIO.execute(ignored -> {
                try {
                    ObjectStoragePort.StoredObject temp = storage.writeTemporary(
                            "doc-guardian-snap/" + UUID.randomUUID(),
                            new ByteArrayInputStream(data),
                            data.length);
                    return storage.promote(temp.objectKey(), data.length, digest);
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to promote CRDT snapshot object", ex);
                }
            });
        }

        private String requestCrdtSnapshot(List<String> updates) {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("operation", "SNAPSHOT");
                body.put("updates", updates);
                byte[] payload = objectMapper.writeValueAsBytes(body);
                String nonce = UUID.randomUUID().toString();
                String ts = Long.toString(Instant.now().getEpochSecond());
                String canonical =
                        String.join(
                                "\n",
                                "POST",
                                transformEndpoint.getPath(),
                                ts,
                                nonce,
                                sha256Hex(payload));
                String signature = hmacSha256Hex(canonical);
                HttpRequest request = HttpRequest.newBuilder(transformEndpoint)
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .header("X-Orbit-Timestamp", ts)
                        .header("X-Orbit-Nonce", nonce)
                        .header("X-Orbit-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
                HttpResponse<byte[]> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(
                            "CRDT snapshot transform returned status " + response.statusCode());
                }
                JsonNode parsed = objectMapper.readTree(response.body());
                return parsed.required("snapshot").asText();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted CRDT snapshot transform", ex);
            } catch (IOException ex) {
                throw new IllegalStateException("CRDT snapshot transform unreachable", ex);
            }
        }

        private String hmacSha256Hex(String message) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("HMAC signing unavailable", ex);
            }
        }

        private static String sha256Hex(byte[] data) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 unavailable", ex);
            }
        }

        private void incrementCounter(String name, double amount) {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                Counter.builder(name).register(registry).increment(amount);
            }
        }

        private void recordTimer(String name, long durationNanos) {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                Timer.builder(name)
                        .register(registry)
                        .record(Math.max(0L, durationNanos), java.util.concurrent.TimeUnit.NANOSECONDS);
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
