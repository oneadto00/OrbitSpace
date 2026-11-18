package com.orbitspace.modules.file;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/** Transactionally enforces fixed-payload idempotency keyed by (operation, actor, idempotencyKey). */
class FileIdempotencyStore {
    private record StoredRecord(String digest, int status, String responseJson) {}

    record Outcome<T>(int status, T body) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    FileIdempotencyStore(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    static String validateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > 160) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String trimmed = rawKey.strip();
        if (trimmed.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return trimmed.length() <= 64 ? trimmed : sha256Hex(trimmed);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    <T> Outcome<T> execute(
            String operation,
            String actorUserId,
            String rawIdempotencyKey,
            Object canonicalInput,
            int successStatus,
            Class<T> responseType,
            Supplier<T> action) {
        String storedKey = validateKey(rawIdempotencyKey);
        String digest = sha256Hex(toJson(canonicalInput));

        db.update(
                """
                INSERT INTO file_idempotency_records(
                    operation, actor_user_id, idempotency_key, request_digest, response_status, response_json
                )
                VALUES (?, ?, ?, ?, 0, CAST('null' AS JSON))
                ON DUPLICATE KEY UPDATE operation = operation
                """,
                operation,
                actorUserId,
                storedKey,
                digest);

        StoredRecord existing =
                db.queryForObject(
                        """
                        SELECT request_digest, response_status, response_json
                        FROM file_idempotency_records
                        WHERE operation = ? AND actor_user_id = ? AND idempotency_key = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> new StoredRecord(rs.getString(1), rs.getInt(2), rs.getString(3)),
                        operation,
                        actorUserId,
                        storedKey);

        if (existing != null && existing.status() != 0) {
            if (!digest.equals(existing.digest())) {
                throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
            }
            T decoded =
                    responseType == Void.class
                            ? null
                            : decode(existing.responseJson(), responseType);
            return new Outcome<>(existing.status(), decoded);
        }

        T body = action.get();
        String responseJson = body == null ? "null" : toJson(body);
        db.update(
                """
                UPDATE file_idempotency_records
                SET response_status = ?, response_json = CAST(? AS JSON)
                WHERE operation = ? AND actor_user_id = ? AND idempotency_key = ?
                """,
                successStatus,
                responseJson,
                operation,
                actorUserId,
                storedKey);
        return new Outcome<>(successStatus, body);
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize file payload", ex);
        }
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Corrupt idempotency payload for " + type.getSimpleName(), ex);
        }
    }
}
