package com.orbitspace.modules.document;

import static com.orbitspace.kernel.KernelErrorCode.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentStore {
    static final int MAX_BATCH = 6 * 1024 * 1024;
    static final int MAX_STATE = 48 * 1024 * 1024;
    record Actor(String space, String user, long epoch, String accountSession) {}
    record Doc(String id, String space, String title, long seq, long snapshotSeq, String snapshotKey,
            long lifecycle) {}
    record Version(String documentId, String persistedVersionId, long persistedBatchSeq, String kind,
            Instant createdAt) {}
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final TransactionTemplate storageIO;
    final ObjectMapper mapper;
    final ObjectStoragePort objects;
    final SpaceAuthorizationPort authorization;
    final OutboxPublisherPort outbox;
    final org.springframework.context.ApplicationEventPublisher events;

    DocumentStore(JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager,
            ObjectMapper mapper, ObjectStoragePort objects, SpaceAuthorizationPort authorization,
            OutboxPublisherPort outbox, org.springframework.context.ApplicationEventPublisher events) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.storageIO = new TransactionTemplate(manager);
        this.storageIO.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.mapper = mapper;
        this.objects = objects;
        this.authorization = authorization;
        this.outbox = outbox;
        this.events = events;
    }

    SpaceAuthorizationPort.Membership member(Actor a, SpaceRole role) {
        // Share the row lock used by space removal and role changes, including for worker result attachment.
        if (db.queryForList("""
                SELECT user_id FROM space_members WHERE space_id=? AND user_id=?
                AND membership_epoch=? AND status='ACTIVE' FOR SHARE
                """, String.class, a.space, a.user, a.epoch).isEmpty())
            throw error(MEMBERSHIP_EPOCH_INVALID);
        return authorization.requireMembership(a.space, a.user, a.epoch, role);
    }

    Doc document(String space, String id) {
        return db
                .query("""
                SELECT id,space_id,title,persisted_batch_seq,snapshot_seq,snapshot_object_key,lifecycle_version
                FROM documents WHERE space_id=? AND id=? AND lifecycle_state='ACTIVE' FOR UPDATE
                """,
                        (r, i)
                                -> new Doc(r.getString(1), r.getString(2), r.getString(3), r.getLong(4),
                                        r.getLong(5), r.getString(6), r.getLong(7)),
                        space, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> error(RESOURCE_EXPIRED_OR_PURGED));
    }

    static Version versionRow(ResultSet r, int index) throws SQLException {
        return new Version(r.getString("document_id"), r.getString("id"), r.getLong("persisted_batch_seq"),
                r.getString("kind"), r.getTimestamp("created_at").toInstant());
    }

    String scalar(String query, Object... args) {
        return db.queryForList(query, String.class, args)
                .stream()
                .findFirst()
                .orElseThrow(() -> error(RESOURCE_NOT_FOUND));
    }

    JsonNode once(Actor a, String op, String key, Object body, Supplier<?> action) {
        check(key != null && !key.isBlank() && key.length() <= 160);
        String scope =
                hash(json(List.of(a.space, a.user, a.epoch, op, key)).getBytes(StandardCharsets.UTF_8));
        String digest = hash(json(body).getBytes(StandardCharsets.UTF_8));
        db.update("""
                INSERT INTO document_requests(scope_hash,request_digest) VALUES(?,?)
                ON DUPLICATE KEY UPDATE scope_hash=scope_hash
                """, scope, digest);
        var row = db.queryForMap(
                "SELECT request_digest,result_json FROM document_requests WHERE scope_hash=? FOR UPDATE",
                scope);
        if (!digest.equals(row.get("request_digest")))
            throw error(IDEMPOTENCY_CONFLICT);
        if (row.get("result_json") != null)
            return parse((String) row.get("result_json"));
        var result = mapper.valueToTree(action.get());
        db.update("UPDATE document_requests SET result_json=? WHERE scope_hash=?", json(result), scope);
        return result;
    }

    String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
    JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw error(INVALID_REQUEST);
        }
    }
    static String id() {
        return UUID.randomUUID().toString();
    }
    static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    static DomainException error(KernelErrorCode code) {
        return new DomainException(code);
    }
    static void check(boolean valid) {
        if (!valid)
            throw error(INVALID_REQUEST);
    }
    static String text(JsonNode node, String name, int max) {
        JsonNode value = node.path(name);
        check(value.isTextual() && !value.textValue().isBlank() && value.textValue().length() <= max);
        return value.textValue();
    }
    static long seq(JsonNode node, String name) {
        var n = node.path(name);
        check(n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 0
                && n.longValue() <= 9007199254740991L);
        return n.longValue();
    }
    static void expect(long actual, long expected) {
        if (actual != expected)
            throw error(DOC_ANCHOR_UNPERSISTED);
    }
    static byte[] bytes(String base64, int max) {
        check(base64 != null && !base64.isEmpty() && base64.length() <= ((max + 2L) / 3) * 4);
        try {
            byte[] data = Base64.getDecoder().decode(base64);
            check(data.length > 0 && data.length <= max);
            return data;
        } catch (IllegalArgumentException e) {
            throw error(INVALID_REQUEST);
        }
    }
    ObjectStoragePort.StoredObject promote(byte[] data) {
        String digest = hash(data);
        // A backup and a new edit can share an object already pinned by this transaction.
        // Reuse verified content; a nested promotion would contend with our own inventory lock.
        String key = "immutable/" + digest;
        if (!db.queryForList("SELECT object_key FROM storage_objects WHERE object_key=? AND size_bytes=? AND "
                             + "sha256_hex=? AND status='IMMUTABLE' AND reference_count>0",
                       String.class, key, data.length, digest)
                        .isEmpty())
            return new ObjectStoragePort.StoredObject(key, data.length, digest);
        return storageIO.execute(ignored -> {
            try {
                var temporary = objects.writeTemporary(
                        "document/" + id(), new ByteArrayInputStream(data), data.length);
                return objects.promote(temporary.objectKey(), data.length, digest);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
    void attach(String owner, String id, String objectKey) {
        db.update("INSERT INTO storage_object_references(owner_type,owner_id,object_key) VALUES(?,?,?)",
                owner, id, objectKey);
    }
    byte[] read(String key, int max, Runnable access) {
        try (var in = objects.openRead(key, access)) {
            var out = in.readNBytes(max + 1);
            check(out.length <= max);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    String encoded(String key, int max, Runnable access) {
        return Base64.getEncoder().encodeToString(read(key, max, access));
    }
    void notifyDocument(String event, Doc doc) {
        var notification = new OutboxPublisherPort.Event(id(), event, "DOCUMENT", doc.id(),
                json(Map.of("type", event, "spaceId", doc.space(), "documentId", doc.id())), Instant.now());
        outbox.publish(notification);
        events.publishEvent(notification);
    }
}
