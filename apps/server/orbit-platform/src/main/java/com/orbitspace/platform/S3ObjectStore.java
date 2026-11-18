package com.orbitspace.platform;

import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.ObjectStoragePort;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

public class S3ObjectStore implements ObjectStoragePort {
    private record ObjectRow(StoredObject object, String status) {}

    private final JdbcTemplate db;
    private final TransactionTemplate independent;
    private final S3Client s3;
    private final String bucket;
    private final Duration grace;

    public S3ObjectStore(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            S3Client s3,
            String bucket,
            Duration grace) {
        if (grace.isNegative() || grace.isZero())
            throw new IllegalArgumentException("Positive GC grace required");
        this.db = db;
        this.s3 = s3;
        this.bucket = bucket;
        this.grace = grace;
        this.independent = new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // Commit inventory before contacting S3. Even a crash after a successful PUT leaves a GC
    // candidate.
    private void reserve(StoredObject object) {
        independent.executeWithoutResult(
                tx ->
                        db.update(
                                """
                                INSERT INTO storage_objects(object_key,bucket,size_bytes,sha256_hex,status)
                                VALUES (?,?,?,?,'PENDING_GC') ON DUPLICATE KEY UPDATE object_key=object_key
                                """,
                                object.objectKey(),
                                bucket,
                                object.sizeBytes(),
                                object.sha256()));
    }

    private ObjectRow lock(String key) {
        return db.queryForObject(
                """
                SELECT object_key,size_bytes,sha256_hex,status FROM storage_objects
                WHERE object_key=? AND bucket=? FOR UPDATE
                """,
                (rs, i) ->
                        new ObjectRow(
                                new StoredObject(rs.getString(1), rs.getLong(2), rs.getString(3)),
                                rs.getString(4)),
                key,
                bucket);
    }

    private void publish(StoredObject object, String state) {
        db.update(
                """
                UPDATE storage_objects SET status=?,unreferenced_since=CURRENT_TIMESTAMP(6)
                WHERE object_key=? AND reference_count=0
                """,
                state,
                object.objectKey());
    }

    @Override
    public StoredObject writeTemporary(String uploadId, InputStream bytes, long sizeLimit)
            throws IOException {
        if (uploadId == null || uploadId.isBlank() || sizeLimit < 0)
            throw new IllegalArgumentException("Invalid upload");
        Path spool = Files.createTempFile("orbit-upload-", ".part");
        try {
            MessageDigest digest = PlatformValueSupport.sha256();
            long size;
            try (OutputStream out = Files.newOutputStream(spool)) {
                size = transfer(bytes, out, digest, sizeLimit);
            }
            StoredObject object =
                    new StoredObject(
                            "temporary/" + PlatformValueSupport.digest(uploadId),
                            size,
                            HexFormat.of().formatHex(digest.digest()));
            reserve(object);
            return independent.execute(
                    tx -> {
                        ObjectRow row = lock(object.objectKey());
                        if (!row.object().equals(object))
                            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                        if (!"TEMP_UPLOADED".equals(row.status())) {
                            s3.putObject(
                                    b -> b.bucket(bucket).key(object.objectKey()),
                                    RequestBody.fromFile(spool));
                            publish(object, "TEMP_UPLOADED");
                        }
                        return object;
                    });
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    private static long transfer(InputStream in, OutputStream out, MessageDigest digest, long limit)
            throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int count; (count = in.read(buffer)) != -1; ) {
            if (count > limit - total)
                throw new IOException("Object exceeds its declared size limit");
            digest.update(buffer, 0, count);
            out.write(buffer, 0, count);
            total += count;
        }
        return total;
    }

    @Override
    public StoredObject promote(String temporaryKey, long expectedSize, String expectedSha256)
            throws IOException {
        if (!temporaryKey.matches("temporary/[0-9a-f]{64}")
                || !expectedSha256.matches("[0-9a-f]{64}")
                || expectedSize < 0)
            throw new IllegalArgumentException("Invalid promotion request");
        StoredObject immutable =
                new StoredObject("immutable/" + expectedSha256, expectedSize, expectedSha256);
        boolean matches =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(SELECT 1 FROM storage_objects WHERE object_key=? AND bucket=? AND size_bytes=? AND sha256_hex=?)
                                """,
                                Boolean.class,
                                temporaryKey,
                                bucket,
                                expectedSize,
                                expectedSha256));
        if (!matches) throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        reserve(immutable);
        try {
            return independent.execute(
                    tx -> {
                        ObjectRow source = lock(temporaryKey);
                        if (!expectedSha256.equals(source.object.sha256())
                                || expectedSize != source.object.sizeBytes())
                            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                        ObjectRow target = lock(immutable.objectKey());
                        if (!immutable.equals(target.object))
                            throw new IllegalStateException("Immutable metadata mismatch");
                        if ("IMMUTABLE".equals(target.status())) {
                            // Give a retried promotion time to attach a reference, including after
                            // an abandoned upload.
                            db.update(
                                    "UPDATE storage_objects SET"
                                        + " unreferenced_since=CURRENT_TIMESTAMP(6) WHERE"
                                        + " object_key=? AND reference_count=0",
                                    immutable.objectKey());
                            return immutable;
                        }
                        if (!"TEMP_UPLOADED".equals(source.status()))
                            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                        MessageDigest digest = PlatformValueSupport.sha256();
                        try (InputStream input =
                                s3.getObject(b -> b.bucket(bucket).key(temporaryKey))) {
                            long size =
                                    transfer(
                                            input,
                                            OutputStream.nullOutputStream(),
                                            digest,
                                            expectedSize);
                            if (size != expectedSize
                                    || !expectedSha256.equals(
                                            HexFormat.of().formatHex(digest.digest())))
                                throw new IOException(
                                        "Stored content did not match the promotion manifest");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        s3.copyObject(
                                b ->
                                        b.sourceBucket(bucket)
                                                .sourceKey(temporaryKey)
                                                .destinationBucket(bucket)
                                                .destinationKey(immutable.objectKey()));
                        publish(immutable, "IMMUTABLE");
                        return immutable;
                    });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public InputStream openRead(String objectKey, Runnable requireReadAccess) {
        requireReadAccess.run();
        boolean visible =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(SELECT 1 FROM storage_objects WHERE object_key=? AND bucket=? AND status='IMMUTABLE')
                                """,
                                Boolean.class,
                                objectKey,
                                bucket));
        if (!visible) throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        return s3.getObject(b -> b.bucket(bucket).key(objectKey));
    }

    private Timestamp cutoff(Instant requested) {
        Instant allowed = Instant.now().minus(grace);
        return PlatformValueSupport.time(requested.isBefore(allowed) ? requested : allowed);
    }

    @Override
    public Candidates scanUnreferenced(Instant cutoff, String cursor, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid scan limit");
        List<Candidate> objects =
                db.query(
                        """
                        SELECT object_key,unreferenced_since FROM storage_objects
                        WHERE object_key>? AND bucket=? AND unreferenced_since<=? AND reference_count=0 AND status<>'DELETED'
                        AND (retain_until IS NULL OR retain_until<=CURRENT_TIMESTAMP(6))
                        ORDER BY object_key LIMIT ?
                        """,
                        (rs, i) -> new Candidate(rs.getString(1), rs.getTimestamp(2).toInstant()),
                        cursor == null ? "" : cursor,
                        bucket,
                        cutoff(cutoff),
                        limit);
        return new Candidates(
                objects, objects.size() == limit ? objects.getLast().objectKey() : null);
    }

    @Override
    public boolean deleteIfStillUnreferenced(Candidate candidate, Instant cutoff) {
        // Commit the fence before deleting: rolling back the final SQL update can never re-enable
        // references.
        boolean fenced =
                Boolean.TRUE.equals(
                        independent.execute(
                                tx -> {
                                    lock(candidate.objectKey());
                                    if (!eligible(candidate, cutoff)) return false;
                                    db.update(
                                            "UPDATE storage_objects SET status='PENDING_GC' WHERE"
                                                + " object_key=?",
                                            candidate.objectKey());
                                    return true;
                                }));
        if (!fenced) return false;
        return Boolean.TRUE.equals(
                independent.execute(
                        tx -> {
                            ObjectRow object = lock(candidate.objectKey());
                            if (!"PENDING_GC".equals(object.status())
                                    || !eligible(candidate, cutoff)) return false;
                            s3.deleteObject(b -> b.bucket(bucket).key(candidate.objectKey()));
                            db.update(
                                    "UPDATE storage_objects SET"
                                        + " status='DELETED',unreferenced_since=NULL WHERE"
                                        + " object_key=?",
                                    candidate.objectKey());
                            return true;
                        }));
    }

    private boolean eligible(Candidate candidate, Instant cutoff) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(SELECT 1 FROM storage_objects o WHERE object_key=? AND bucket=? AND status<>'DELETED'
                            AND reference_count=0 AND unreferenced_since=? AND unreferenced_since<=?
                            AND (retain_until IS NULL OR retain_until<=CURRENT_TIMESTAMP(6))
                            AND NOT EXISTS (SELECT 1 FROM storage_object_references r WHERE r.object_key=o.object_key))
                        """,
                        Boolean.class,
                        candidate.objectKey(),
                        bucket,
                        PlatformValueSupport.time(candidate.unreferencedSince()),
                        cutoff(cutoff)));
    }

    @Scheduled(fixedDelayString = "${orbit.platform.gc-interval:PT5M}")
    public void collectOrphans() {
        Instant cutoff = Instant.now().minus(grace);
        for (Candidate c : scanUnreferenced(cutoff, null, 100).objects())
            deleteIfStillUnreferenced(c, cutoff);
    }
}
