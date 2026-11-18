package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/** Transactionally enforces fixed-payload idempotency via gitproject_idempotency_records. */
class GitProjectIdempotencyStore {
    private record StoredRecord(String requestDigest, String responseJson) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    GitProjectIdempotencyStore(JdbcTemplate db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    <T> T execute(
            String operation,
            String scopeId,
            String actorUserId,
            String rawIdempotencyKey,
            Object canonicalInput,
            Class<T> responseType,
            Supplier<T> action) {
        String digest = GitProjectUtils.sha256Hex(GitProjectUtils.toJson(mapper, canonicalInput));
        return executeWithDigest(
                operation, scopeId, actorUserId, rawIdempotencyKey, digest, responseType, action);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    <T> T executeWithDigest(
            String operation,
            String scopeId,
            String actorUserId,
            String rawIdempotencyKey,
            String requestDigest,
            Class<T> responseType,
            Supplier<T> action) {
        String storedScopeId = GitProjectUtils.normalizeKey64(scopeId);
        String storedKey = GitProjectUtils.normalizeKey64(rawIdempotencyKey);

        db.update(
                """
                INSERT INTO gitproject_idempotency_records(
                    operation, scope_id, actor_user_id, idempotency_key, request_digest, response_json
                )
                VALUES (?, ?, ?, ?, ?, CAST('null' AS JSON))
                ON DUPLICATE KEY UPDATE operation = operation
                """,
                operation,
                storedScopeId,
                actorUserId,
                storedKey,
                requestDigest);

        StoredRecord existing =
                db.queryForObject(
                        """
                        SELECT request_digest, response_json
                        FROM gitproject_idempotency_records
                        WHERE operation = ? AND scope_id = ? AND actor_user_id = ? AND idempotency_key = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> new StoredRecord(rs.getString(1), rs.getString(2)),
                        operation,
                        storedScopeId,
                        actorUserId,
                        storedKey);

        if (existing != null && !isNullJson(existing.responseJson())) {
            if (!requestDigest.equals(existing.requestDigest())) {
                throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return GitProjectUtils.fromJson(mapper, existing.responseJson(), responseType);
        }

        T body = action.get();
        String responseJson = GitProjectUtils.toJson(mapper, body);
        db.update(
                """
                UPDATE gitproject_idempotency_records
                SET response_json = CAST(? AS JSON)
                WHERE operation = ? AND scope_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """,
                responseJson,
                operation,
                storedScopeId,
                actorUserId,
                storedKey);
        return body;
    }

    boolean hasRecord(
            String operation, String scopeId, String actorUserId, String rawIdempotencyKey) {
        String storedScopeId = GitProjectUtils.normalizeKey64(scopeId);
        String storedKey = GitProjectUtils.normalizeKey64(rawIdempotencyKey);
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(
                            SELECT 1 FROM gitproject_idempotency_records
                            WHERE operation = ? AND scope_id = ? AND actor_user_id = ? AND idempotency_key = ?
                        )
                        """,
                        Boolean.class,
                        operation,
                        storedScopeId,
                        actorUserId,
                        storedKey));
    }

    private boolean isNullJson(String json) {
        if (json == null) return true;
        try {
            return mapper.readTree(json).isNull();
        } catch (JsonProcessingException ex) {
            return false;
        }
    }
}
