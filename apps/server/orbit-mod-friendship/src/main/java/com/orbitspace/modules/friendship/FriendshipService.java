package com.orbitspace.modules.friendship;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AuditLogPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.FriendshipQueryPort;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.RateLimitPort;
import com.orbitspace.modules.friendship.FriendshipDtos.FriendListResponse;
import com.orbitspace.modules.friendship.FriendshipDtos.FriendRequestDto;
import com.orbitspace.modules.friendship.FriendshipDtos.FriendRequestListResponse;
import com.orbitspace.modules.friendship.FriendshipDtos.FriendRequestStatus;
import com.orbitspace.modules.friendship.FriendshipDtos.FriendSummary;
import com.orbitspace.modules.friendship.FriendshipDtos.RequestDirection;
import com.orbitspace.modules.friendship.FriendshipDtos.UserSearchHit;
import com.orbitspace.modules.friendship.FriendshipDtos.UserSearchResponse;

import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Core transactional service for basic P2 friend requests, friend list, and FriendshipQueryPort. */
public class FriendshipService implements FriendshipQueryPort {
    private static final int DEFAULT_SEARCH_LIMIT = 10;
    private static final int MAX_SEARCH_LIMIT = 50;
    private static final int SEARCH_RATE_LIMIT_USER = 30;
    private static final int SEARCH_RATE_LIMIT_IP = 60;
    private static final int REQUEST_RATE_LIMIT_USER = 30;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private record OrderedPair(String low, String high) {}

    private record SendInput(String targetUserId) {}

    private record RequestActionInput(String requestId) {}

    private record RequestRow(
            String id,
            String requesterUserId,
            String recipientUserId,
            String pairLowId,
            String pairHighId,
            FriendRequestStatus status,
            Instant createdAt,
            Instant updatedAt) {
        FriendRequestDto toDto() {
            return new FriendRequestDto(
                    id, requesterUserId, recipientUserId, status, createdAt, updatedAt);
        }
    }

    private final JdbcTemplate db;
    private final FriendshipIdempotencyStore idempotency;
    private final RateLimitPort rateLimit;
    private final AuditLogPort auditLog;

    public FriendshipService(
            JdbcTemplate db,
            ObjectMapper mapper,
            RateLimitPort rateLimit,
            AuditLogPort auditLog) {
        this.db = db;
        this.idempotency = new FriendshipIdempotencyStore(db, mapper);
        this.rateLimit = rateLimit;
        this.auditLog = auditLog;
    }

    public RateLimitPort.Decision checkSearchRateLimit(String actorUserId, String remoteAddr) {
        RateLimitPort.Decision ipDecision =
                rateLimit.tryAcquire(
                        "friendship:search:ip:" + FriendshipIdempotencyStore.sha256Hex(safeIp(remoteAddr)),
                        SEARCH_RATE_LIMIT_IP,
                        RATE_WINDOW);
        if (!ipDecision.allowed()) {
            return ipDecision;
        }
        return rateLimit.tryAcquire(
                "friendship:search:user:" + FriendshipIdempotencyStore.sha256Hex(actorUserId),
                SEARCH_RATE_LIMIT_USER,
                RATE_WINDOW);
    }

    public RateLimitPort.Decision checkSendRequestRateLimit(String actorUserId) {
        return rateLimit.tryAcquire(
                "friendship:request:user:" + FriendshipIdempotencyStore.sha256Hex(actorUserId),
                REQUEST_RATE_LIMIT_USER,
                RATE_WINDOW);
    }

    @Transactional(readOnly = true)
    public UserSearchResponse searchUsers(String actorUserId, String rawQuery, Integer rawLimit) {
        String q = rawQuery == null ? "" : rawQuery.strip();
        if (q.isEmpty() || q.length() > 120) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        int limit = rawLimit == null ? DEFAULT_SEARCH_LIMIT : rawLimit;
        if (limit < 1 || limit > MAX_SEARCH_LIMIT) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        String displayLike = "%" + escapeLike(q) + "%";
        List<UserSearchHit> hits;
        if (q.contains("@")) {
            String normalizedEmail = q.toLowerCase(Locale.ROOT);
            hits =
                    db.query(
                            """
                            SELECT id, display_name, email
                            FROM users
                            WHERE email_verified = TRUE
                              AND id <> ?
                              AND (email = ? OR display_name LIKE ? ESCAPE '\\\\')
                            ORDER BY CASE WHEN email = ? THEN 0 ELSE 1 END, display_name ASC, id ASC
                            LIMIT ?
                            """,
                            (rs, i) ->
                                    new UserSearchHit(
                                            rs.getString(1),
                                            rs.getString(2),
                                            maskEmail(rs.getString(3))),
                            actorUserId,
                            normalizedEmail,
                            displayLike,
                            normalizedEmail,
                            limit);
        } else {
            hits =
                    db.query(
                            """
                            SELECT id, display_name, email
                            FROM users
                            WHERE email_verified = TRUE
                              AND id <> ?
                              AND display_name LIKE ? ESCAPE '\\\\'
                            ORDER BY display_name ASC, id ASC
                            LIMIT ?
                            """,
                            (rs, i) ->
                                    new UserSearchHit(
                                            rs.getString(1),
                                            rs.getString(2),
                                            maskEmail(rs.getString(3))),
                            actorUserId,
                            displayLike,
                            limit);
        }
        return new UserSearchResponse(List.copyOf(hits));
    }

    @Transactional
    public FriendRequestDto sendFriendRequest(
            String actorUserId, String idempotencyKey, String rawTargetUserId) {
        String targetUserId = normalizeId(rawTargetUserId);
        if (targetUserId.equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        return idempotency.execute(
                        "SEND_FRIEND_REQUEST",
                        actorUserId,
                        idempotencyKey,
                        new SendInput(targetUserId),
                        201,
                        FriendRequestDto.class,
                        () -> sendFriendRequestInternal(actorUserId, targetUserId))
                .body();
    }

    private FriendRequestDto sendFriendRequestInternal(String actorUserId, String targetUserId) {
        OrderedPair pair = orderPair(actorUserId, targetUserId);
        Map<String, Boolean> verifiedUsers = lockUsersInOrder(pair);
        if (!Boolean.TRUE.equals(verifiedUsers.get(actorUserId))
                || !Boolean.TRUE.equals(verifiedUsers.get(targetUserId))) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }

        boolean alreadyFriends =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM friendships
                                    WHERE user_low_id = ? AND user_high_id = ?
                                )
                                """,
                                Boolean.class,
                                pair.low(),
                                pair.high()));
        if (alreadyFriends) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "ALREADY_FRIENDS"));
        }

        boolean pendingExists =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM friend_requests
                                    WHERE pair_low_id = ? AND pair_high_id = ? AND status = 'PENDING'
                                )
                                """,
                                Boolean.class,
                                pair.low(),
                                pair.high()));
        if (pendingExists) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "PENDING_REQUEST_EXISTS"));
        }

        String requestId = UUID.randomUUID().toString();
        try {
            db.update(
                    """
                    INSERT INTO friend_requests(
                        id, requester_user_id, recipient_user_id, pair_low_id, pair_high_id, status
                    )
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                    """,
                    requestId,
                    actorUserId,
                    targetUserId,
                    pair.low(),
                    pair.high());
        } catch (DuplicateKeyException ex) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "PENDING_REQUEST_EXISTS"));
        }

        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        "FRIEND_REQUEST_SEND",
                        requestId,
                        "SUCCESS",
                        Instant.now()));

        return loadRequest(requestId, false).toDto();
    }

    @Transactional(readOnly = true)
    public FriendRequestListResponse listFriendRequests(
            String actorUserId, RequestDirection direction) {
        RequestDirection effective = direction == null ? RequestDirection.ALL : direction;
        String sql =
                switch (effective) {
                    case INCOMING ->
                            """
                            SELECT id, requester_user_id, recipient_user_id, pair_low_id, pair_high_id,
                                   status, created_at, updated_at
                            FROM friend_requests
                            WHERE recipient_user_id = ?
                            ORDER BY updated_at DESC, id ASC
                            """;
                    case OUTGOING ->
                            """
                            SELECT id, requester_user_id, recipient_user_id, pair_low_id, pair_high_id,
                                   status, created_at, updated_at
                            FROM friend_requests
                            WHERE requester_user_id = ?
                            ORDER BY updated_at DESC, id ASC
                            """;
                    case ALL ->
                            """
                            SELECT id, requester_user_id, recipient_user_id, pair_low_id, pair_high_id,
                                   status, created_at, updated_at
                            FROM friend_requests
                            WHERE requester_user_id = ? OR recipient_user_id = ?
                            ORDER BY updated_at DESC, id ASC
                            """;
                };

        List<RequestRow> rows =
                effective == RequestDirection.ALL
                        ? db.query(sql, this::mapRequestRow, actorUserId, actorUserId)
                        : db.query(sql, this::mapRequestRow, actorUserId);

        return new FriendRequestListResponse(rows.stream().map(RequestRow::toDto).toList());
    }

    @Transactional
    public FriendRequestDto acceptFriendRequest(
            String actorUserId, String idempotencyKey, String rawRequestId) {
        String requestId = normalizeId(rawRequestId);
        return idempotency.execute(
                        "ACCEPT_FRIEND_REQUEST",
                        actorUserId,
                        idempotencyKey,
                        new RequestActionInput(requestId),
                        200,
                        FriendRequestDto.class,
                        () -> acceptInternal(actorUserId, requestId))
                .body();
    }

    private FriendRequestDto acceptInternal(String actorUserId, String requestId) {
        RequestRow row = loadRequest(requestId, true);
        if (!row.recipientUserId().equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (row.status() != FriendRequestStatus.PENDING) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        OrderedPair pair = orderPair(row.requesterUserId(), row.recipientUserId());
        lockUsersInOrder(pair);

        int updated =
                db.update(
                        """
                        UPDATE friend_requests
                        SET status = 'ACCEPTED', updated_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ? AND status = 'PENDING'
                        """,
                        requestId);
        if (updated != 1) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        db.update(
                """
                INSERT INTO friendships(user_low_id, user_high_id, source_request_id)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE source_request_id = VALUES(source_request_id)
                """,
                pair.low(),
                pair.high(),
                requestId);

        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        "FRIEND_REQUEST_ACCEPT",
                        requestId,
                        "SUCCESS",
                        Instant.now()));

        return loadRequest(requestId, false).toDto();
    }

    @Transactional
    public FriendRequestDto rejectFriendRequest(
            String actorUserId, String idempotencyKey, String rawRequestId) {
        String requestId = normalizeId(rawRequestId);
        return idempotency.execute(
                        "REJECT_FRIEND_REQUEST",
                        actorUserId,
                        idempotencyKey,
                        new RequestActionInput(requestId),
                        200,
                        FriendRequestDto.class,
                        () -> transitionRequest(actorUserId, requestId, true, FriendRequestStatus.REJECTED, "FRIEND_REQUEST_REJECT"))
                .body();
    }

    @Transactional
    public FriendRequestDto cancelFriendRequest(
            String actorUserId, String idempotencyKey, String rawRequestId) {
        String requestId = normalizeId(rawRequestId);
        return idempotency.execute(
                        "CANCEL_FRIEND_REQUEST",
                        actorUserId,
                        idempotencyKey,
                        new RequestActionInput(requestId),
                        200,
                        FriendRequestDto.class,
                        () -> transitionRequest(actorUserId, requestId, false, FriendRequestStatus.CANCELLED, "FRIEND_REQUEST_CANCEL"))
                .body();
    }

    private FriendRequestDto transitionRequest(
            String actorUserId,
            String requestId,
            boolean recipientAction,
            FriendRequestStatus targetStatus,
            String auditAction) {
        RequestRow row = loadRequest(requestId, true);
        String expectedActor = recipientAction ? row.recipientUserId() : row.requesterUserId();
        if (!expectedActor.equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (row.status() != FriendRequestStatus.PENDING) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        int updated =
                db.update(
                        """
                        UPDATE friend_requests
                        SET status = ?, updated_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ? AND status = 'PENDING'
                        """,
                        targetStatus.name(),
                        requestId);
        if (updated != 1) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        auditAction,
                        requestId,
                        "SUCCESS",
                        Instant.now()));

        return loadRequest(requestId, false).toDto();
    }

    @Transactional(readOnly = true)
    public FriendListResponse listFriends(String actorUserId) {
        List<FriendSummary> items =
                db.query(
                        """
                        SELECT u.id, u.display_name, f.became_friends_at
                        FROM friendships f
                        JOIN users u ON u.id = CASE
                            WHEN f.user_low_id = ? THEN f.user_high_id
                            ELSE f.user_low_id
                        END
                        WHERE f.user_low_id = ? OR f.user_high_id = ?
                        ORDER BY u.display_name ASC, u.id ASC
                        """,
                        (rs, i) ->
                                new FriendSummary(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getTimestamp(3).toInstant()),
                        actorUserId,
                        actorUserId,
                        actorUserId);
        return new FriendListResponse(List.copyOf(items));
    }

    @Transactional
    public void removeFriend(String actorUserId, String rawFriendUserId) {
        String friendUserId = normalizeId(rawFriendUserId);
        if (friendUserId.equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        OrderedPair pair = orderPair(actorUserId, friendUserId);
        lockUsersInOrder(pair);
        int deleted =
                db.update(
                        "DELETE FROM friendships WHERE user_low_id = ? AND user_high_id = ?",
                        pair.low(),
                        pair.high());
        if (deleted == 0) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }
        // Space memberships are strictly independent and governed solely by space_members.
        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        "FRIEND_REMOVE",
                        friendUserId,
                        "SUCCESS",
                        Instant.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean areFriends(String userIdA, String userIdB) {
        if (userIdA == null || userIdA.isBlank() || userIdB == null || userIdB.isBlank()) {
            return false;
        }
        String a = userIdA.strip();
        String b = userIdB.strip();
        if (a.equals(b)) {
            return false;
        }
        OrderedPair pair = orderPair(a, b);
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(
                            SELECT 1 FROM friendships WHERE user_low_id = ? AND user_high_id = ?
                        )
                        """,
                        Boolean.class,
                        pair.low(),
                        pair.high()));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> filterFriends(String userId, Collection<String> candidateUserIds) {
        if (userId == null
                || userId.isBlank()
                || candidateUserIds == null
                || candidateUserIds.isEmpty()) {
            return Set.of();
        }
        String self = userId.strip();
        List<String> candidates =
                candidateUserIds.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::strip)
                        .filter(id -> !id.equals(self))
                        .distinct()
                        .toList();
        if (candidates.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", Collections.nCopies(candidates.size(), "?"));
        Object[] queryParams = new Object[1 + candidates.size()];
        queryParams[0] = self;
        for (int i = 0; i < candidates.size(); i++) {
            queryParams[i + 1] = candidates.get(i);
        }

        String lowSql =
                "SELECT user_high_id FROM friendships WHERE user_low_id = ? AND user_high_id IN ("
                        + placeholders
                        + ")";
        String highSql =
                "SELECT user_low_id FROM friendships WHERE user_high_id = ? AND user_low_id IN ("
                        + placeholders
                        + ")";

        Set<String> result = new HashSet<>(db.queryForList(lowSql, String.class, queryParams));
        result.addAll(db.queryForList(highSql, String.class, queryParams));
        return Set.copyOf(result);
    }

    private Map<String, Boolean> lockUsersInOrder(OrderedPair pair) {
        Map<String, Boolean> verified = new HashMap<>(2);
        db.query(
                """
                SELECT id, email_verified
                FROM users
                WHERE id IN (?, ?)
                ORDER BY id ASC
                FOR UPDATE
                """,
                (rs, i) -> {
                    verified.put(rs.getString(1), rs.getBoolean(2));
                    return null;
                },
                pair.low(),
                pair.high());
        return verified;
    }

    private RequestRow loadRequest(String requestId, boolean forUpdate) {
        String sql =
                """
                SELECT id, requester_user_id, recipient_user_id, pair_low_id, pair_high_id,
                       status, created_at, updated_at
                FROM friend_requests
                WHERE id = ?
                """
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(sql, this::mapRequestRow, requestId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private RequestRow mapRequestRow(ResultSet rs, int rowNum) throws SQLException {
        return new RequestRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                FriendRequestStatus.valueOf(rs.getString(6)),
                rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8).toInstant());
    }

    private static OrderedPair orderPair(String a, String b) {
        return a.compareTo(b) <= 0 ? new OrderedPair(a, b) : new OrderedPair(b, a);
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

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String maskEmail(String email) {
        if (email == null) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        int visible = local.length() > 2 ? 2 : 1;
        return local.substring(0, visible) + "***" + domain;
    }

    private static String safeIp(String remoteAddr) {
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;
    }

    private static String requestIdFromMdc() {
        String id = MDC.get("requestId");
        return id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    }
}
