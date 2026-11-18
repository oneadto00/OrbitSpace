package com.orbitspace.modules.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.function.Supplier;

/** Transactionally enforces fixed-payload idempotency keyed by (operation, room, actor, key). */
final class ChatIdempotencyStore {
    private record StoredRecord(String digest, String responseJson) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    ChatIdempotencyStore(JdbcTemplate db, ObjectMapper mapper) {
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
        return trimmed.length() <= 64 ? trimmed : ChatSecrets.hash(trimmed);
    }

    /** Must run inside an active database transaction. */
    <T> T execute(
            String operation,
            String roomId,
            String actorUserId,
            String rawIdempotencyKey,
            Object canonicalInput,
            Class<T> responseType,
            Supplier<T> action) {
        String storedKey = validateKey(rawIdempotencyKey);
        String digest = ChatSecrets.digestJson(mapper, canonicalInput);

        db.update(
                """
                INSERT INTO chat_idempotency_records(
                    operation, room_id, actor_user_id, idempotency_key, request_digest, response_json
                )
                VALUES (?, ?, ?, ?, ?, CAST('null' AS JSON))
                ON DUPLICATE KEY UPDATE operation = operation
                """,
                operation,
                roomId,
                actorUserId,
                storedKey,
                digest);

        StoredRecord existing = db.queryForObject(
                """
                SELECT request_digest, response_json
                FROM chat_idempotency_records
                WHERE operation = ? AND room_id = ? AND actor_user_id = ? AND idempotency_key = ?
                FOR UPDATE
                """,
                (rs, i) -> new StoredRecord(rs.getString(1), rs.getString(2)),
                operation,
                roomId,
                actorUserId,
                storedKey);

        // Concurrent caller holds this row lock until it writes response_json and commits;
        // seeing non-null JSON here guarantees a previously committed outcome.
        if (existing.responseJson() != null && !"null".equals(existing.responseJson().strip())) {
            if (!digest.equals(existing.digest())) {
                throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return decode(existing.responseJson(), responseType);
        }

        T body = action.get();
        String responseJson = ChatSecrets.toJson(mapper, body);
        db.update(
                """
                UPDATE chat_idempotency_records
                SET response_json = CAST(? AS JSON)
                WHERE operation = ? AND room_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """,
                responseJson,
                operation,
                roomId,
                actorUserId,
                storedKey);
        return body;
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Corrupt chat idempotency payload for " + type.getSimpleName(), ex);
        }
    }
}
