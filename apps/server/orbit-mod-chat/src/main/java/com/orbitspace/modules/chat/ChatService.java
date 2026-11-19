package com.orbitspace.modules.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.ChatUnreadQueryPort;
import com.orbitspace.kernel.DiscussionStatus;
import com.orbitspace.kernel.DiscussionThreadPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.GrantType;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.MessageKind;
import com.orbitspace.kernel.OutboxPublisherPort;
import com.orbitspace.kernel.ResourceCardVerifierPort;
import com.orbitspace.kernel.ResourceCardVerifierPort.Preview;
import com.orbitspace.kernel.ResourceCardVerifierPort.Reference;
import com.orbitspace.kernel.ResourceLifecycleHook;
import com.orbitspace.kernel.ResourceLifecycleState;
import com.orbitspace.kernel.SpaceAuthorizationPort;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.kernel.SpaceRoomPort;
import com.orbitspace.modules.chat.ChatDtos.AdvanceReadRequest;
import com.orbitspace.modules.chat.ChatDtos.CardView;
import com.orbitspace.modules.chat.ChatDtos.ChangeDiscussionRequest;
import com.orbitspace.modules.chat.ChatDtos.ChatMessage;
import com.orbitspace.modules.chat.ChatDtos.ClientContent;
import com.orbitspace.modules.chat.ChatDtos.CodeCommentRefContent;
import com.orbitspace.modules.chat.ChatDtos.CommitRefContent;
import com.orbitspace.modules.chat.ChatDtos.Discussion;
import com.orbitspace.modules.chat.ChatDtos.DiscussionStatusChangedPayload;
import com.orbitspace.modules.chat.ChatDtos.DocRefContent;
import com.orbitspace.modules.chat.ChatDtos.EventPage;
import com.orbitspace.modules.chat.ChatDtos.FileRefContent;
import com.orbitspace.modules.chat.ChatDtos.ForwardCommentRequest;
import com.orbitspace.modules.chat.ChatDtos.HistoryPage;
import com.orbitspace.modules.chat.ChatDtos.MessageAck;
import com.orbitspace.modules.chat.ChatDtos.MessageContent;
import com.orbitspace.modules.chat.ChatDtos.MessageCreatedPayload;
import com.orbitspace.modules.chat.ChatDtos.PdfRefContent;
import com.orbitspace.modules.chat.ChatDtos.PdfSelectionRect;
import com.orbitspace.modules.chat.ChatDtos.ReadCursorResponse;
import com.orbitspace.modules.chat.ChatDtos.ResolveCardsRequest;
import com.orbitspace.modules.chat.ChatDtos.ResourceCardResult;
import com.orbitspace.modules.chat.ChatDtos.ResourceCardStateChangedPayload;
import com.orbitspace.modules.chat.ChatDtos.RoomEvent;
import com.orbitspace.modules.chat.ChatDtos.RoomEventType;
import com.orbitspace.modules.chat.ChatDtos.RoomInfo;
import com.orbitspace.modules.chat.ChatDtos.SendMessageRequest;
import com.orbitspace.modules.chat.ChatDtos.SyncBoundary;
import com.orbitspace.modules.chat.ChatDtos.SystemContent;
import com.orbitspace.modules.chat.ChatDtos.TextContent;
import com.orbitspace.modules.chat.ChatDtos.WipRefContent;
import com.orbitspace.modules.chat.ChatDtos.WsEnvelope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Core transactional domain service for room chat, dual-cursor sync, read state, and cards. */
public class ChatService
        implements SpaceRoomPort, ChatUnreadQueryPort, DiscussionThreadPort, ResourceLifecycleHook {
    static final String ROOM_EVENTS_CHANNEL = "orbit:chat:room-events";

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);
    private static final Pattern GIT_OID = Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final int DEFAULT_PAGE_LIMIT = 50;
    private static final int MAX_PAGE_LIMIT = 200;

    private record RoomRow(
            String roomId, long maxSeq, long maxEventSeq, long minAfterEventSeq) {
        SyncBoundary toBoundary() {
            return new SyncBoundary(maxSeq, maxEventSeq, minAfterEventSeq);
        }
    }

    private record ReadStateRow(long joinedBaselineSeq, long lastReadSeq) {}

    private record ExistingMessageRow(String id, long seq, String payloadDigest) {}

    private record CanonicalSendPayload(String replyToMsgId, ClientContent content) {}

    private record CanonicalChangeDiscussion(
            String messageId, DiscussionStatus status, String conclusionMsgId) {}

    private record CanonicalForwardComment(
            MessageKind kind,
            String resourceId,
            String versionId,
            String commentId,
            String clientMsgId,
            String purpose) {}

    private record DocumentCommentAnchorRow(
            String commentId,
            MessageKind kind,
            String documentId,
            String persistedVersionId,
            String relativeStart,
            String relativeEnd,
            String fileId,
            String fileVersionId,
            Integer pageNumber,
            List<PdfSelectionRect> rectangles,
            String rectanglesJson,
            String quotedText,
            String contextBefore,
            String contextAfter,
            String discussionThreadId) {}

    private record CardReferenceRow(Reference reference, String cardLifecycleState) {}

    private record LockedRootMessage(String senderId, String replyToMsgId) {}

    private record LockedThread(
            DiscussionStatus status, String conclusionMsgId, long version) {}

    private record ReplyParentRow(String id, String replyToMsgId) {}

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final SpaceAuthorizationPort authorization;
    private final OutboxPublisherPort outbox;
    private final ObjectProvider<ResourceCardVerifierPort> verifiers;
    private final ChatIdempotencyStore idempotency;

    public ChatService(
            JdbcTemplate db,
            PlatformTransactionManager txManager,
            ObjectMapper mapper,
            StringRedisTemplate redis,
            SpaceAuthorizationPort authorization,
            OutboxPublisherPort outbox,
            ObjectProvider<ResourceCardVerifierPort> verifiers) {
        this.db = db;
        this.tx = new TransactionTemplate(txManager);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
        this.mapper = mapper;
        this.redis = redis;
        this.authorization = authorization;
        this.outbox = outbox;
        this.verifiers = verifiers;
        this.idempotency = new ChatIdempotencyStore(db, mapper);
    }

    @Override
    public String ensureMainRoom(String spaceId) {
        validateId(spaceId, 64);
        return tx.execute(
                status -> {
                    String newRoomId = ChatSecrets.id();
                    db.update(
                            """
                            INSERT INTO chat_rooms(
                                id, space_id, max_msg_seq, max_event_seq, min_retained_event_seq
                            )
                            VALUES (?, ?, 0, 0, 0)
                            ON DUPLICATE KEY UPDATE id = id
                            """,
                            newRoomId,
                            spaceId);
                    return db.queryForObject(
                            "SELECT id FROM chat_rooms WHERE space_id = ?", String.class, spaceId);
                });
    }

    @Override
    public RoomPosition currentPosition(String spaceId) {
        validateId(spaceId, 64);
        RoomRow room = findRoomBySpaceId(spaceId);
        if (room != null) {
            return new RoomPosition(room.roomId(), room.maxSeq());
        }
        String roomId = ensureMainRoom(spaceId);
        RoomRow created = findRoomBySpaceId(spaceId);
        return new RoomPosition(roomId, created != null ? created.maxSeq() : 0L);
    }

    @Override
    public long countUnread(String spaceId, String userId, long membershipEpoch) {
        if (spaceId == null
                || spaceId.isBlank()
                || userId == null
                || userId.isBlank()
                || membershipEpoch < 1) {
            return 0L;
        }
        RoomRow room = findRoomBySpaceId(spaceId);
        if (room == null) {
            return 0L;
        }
        Long threshold = db.query(
                        """
                        SELECT GREATEST(joined_baseline_seq, last_read_seq)
                        FROM chat_read_states
                        WHERE room_id = ? AND user_id = ? AND membership_epoch = ?
                        """,
                        (rs, i) -> rs.getLong(1),
                        room.roomId(),
                        userId,
                        membershipEpoch)
                .stream()
                .findFirst()
                .orElse(null);

        if (threshold == null) {
            threshold = db.query(
                            """
                            SELECT joined_baseline_seq
                            FROM space_members
                            WHERE space_id = ? AND user_id = ? AND membership_epoch = ?
                            """,
                            (rs, i) -> rs.getLong(1),
                            spaceId,
                            userId,
                            membershipEpoch)
                    .stream()
                    .findFirst()
                    .orElse(room.maxSeq());
        }

        Long count = db.queryForObject(
                """
                SELECT COUNT(*)
                FROM chat_messages
                WHERE room_id = ?
                  AND seq > ?
                  AND kind <> 'SYSTEM'
                  AND (sender_id IS NULL OR sender_id <> ?)
                """,
                Long.class,
                room.roomId(),
                threshold,
                userId);
        return count != null ? count : 0L;
    }

    @Override
    public Optional<ThreadSummary> findThread(String spaceId, String rootMessageId) {
        if (spaceId == null
                || spaceId.isBlank()
                || rootMessageId == null
                || rootMessageId.isBlank()) {
            return Optional.empty();
        }
        return db.query(
                        """
                        SELECT dt.root_msg_id, dt.room_id, dt.status, dt.conclusion_msg_id, dt.version
                        FROM discussion_threads dt
                        JOIN chat_rooms r ON r.id = dt.room_id
                        WHERE r.space_id = ? AND dt.root_msg_id = ?
                        """,
                        (rs, i) -> new ThreadSummary(
                                rs.getString(1),
                                rs.getString(2),
                                DiscussionStatus.valueOf(rs.getString(3)),
                                rs.getString(4),
                                rs.getLong(5)),
                        spaceId,
                        rootMessageId)
                .stream()
                .findFirst();
    }

    @Override
    public ThreadSummary bindOrForwardCodeComment(CodeCommentCardRequest request) {
        if (request == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        validateId(request.spaceId(), 64);
        validateId(request.projectId(), 64);
        validateId(request.commentId(), 128);
        validateId(request.authorUserId(), 64);
        String normalizedPurpose = normalizeOptionalPurpose(request.purpose());
        String normalizedClientMsgId = normalizeOptionalClientMsgId(request.clientMsgId());

        authorization.requireMembership(
                request.spaceId(),
                request.authorUserId(),
                request.membershipEpoch(),
                SpaceRole.MEMBER);

        CodeCommentRefContent content =
                new CodeCommentRefContent(
                        request.projectId(), request.commentId(), normalizedPurpose);
        String payloadDigest =
                ChatSecrets.digestJson(mapper, new CanonicalSendPayload(null, content));

        return tx.execute(
                status -> {
                    RoomRow room = ensureAndLockRoom(request.spaceId());

                    ThreadSummary existingBySource =
                            findThreadByCodeComment(
                                    room.roomId(), request.projectId(), request.commentId());
                    if (existingBySource != null) {
                        return existingBySource;
                    }

                    ExistingMessageRow existingByClientId =
                            findExistingMessageRow(
                                    room.roomId(), request.authorUserId(), normalizedClientMsgId);
                    if (existingByClientId != null) {
                        if (!payloadDigest.equals(existingByClientId.payloadDigest())) {
                            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                        }
                        ThreadSummary existingThread =
                                findThreadByRootMsgId(room.roomId(), existingByClientId.id());
                        if (existingThread != null) {
                            return existingThread;
                        }
                        throw new IllegalStateException(
                                "Missing discussion thread for existing root message");
                    }

                    requireResourceActive(
                            request.authorUserId(),
                            request.spaceId(),
                            request.membershipEpoch(),
                            content);

                    long nextSeq = room.maxSeq() + 1;
                    long nextEventSeq = room.maxEventSeq() + 1;
                    db.update(
                            "UPDATE chat_rooms SET max_msg_seq = ?, max_event_seq = ? WHERE id = ?",
                            nextSeq,
                            nextEventSeq,
                            room.roomId());

                    String messageId = ChatSecrets.id();
                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    Timestamp createdAt = Timestamp.from(now);

                    db.update(
                            """
                            INSERT INTO chat_messages(
                                id, room_id, seq, sender_id, client_msg_id, kind,
                                body_text, ref_project_id, ref_target_id, ref_grant_type, ref_purpose,
                                reply_to_msg_id, payload_digest, discussion_version, created_at
                            )
                            VALUES (?, ?, ?, ?, ?, 'CODE_COMMENT_REF', NULL, ?, ?, NULL, ?, NULL, ?, 1, ?)
                            """,
                            messageId,
                            room.roomId(),
                            nextSeq,
                            request.authorUserId(),
                            normalizedClientMsgId,
                            request.projectId(),
                            request.commentId(),
                            normalizedPurpose,
                            payloadDigest,
                            createdAt);

                    db.update(
                            """
                            INSERT INTO discussion_threads(
                                root_msg_id, room_id, source_kind, source_project_id, source_anchor_id,
                                status, conclusion_msg_id, version, updated_by_user_id, updated_at
                            )
                            VALUES (?, ?, 'CODE_COMMENT', ?, ?, 'OPEN', NULL, 1, ?, ?)
                            """,
                            messageId,
                            room.roomId(),
                            request.projectId(),
                            request.commentId(),
                            request.authorUserId(),
                            createdAt);

                    Discussion discussion = new Discussion(DiscussionStatus.OPEN, null, 1L);
                    ChatMessage chatMessage =
                            new ChatMessage(
                                    room.roomId(),
                                    messageId,
                                    nextSeq,
                                    request.authorUserId(),
                                    normalizedClientMsgId,
                                    now,
                                    null,
                                    discussion,
                                    content);
                    String payloadJson =
                            ChatSecrets.toJson(mapper, new MessageCreatedPayload(chatMessage));
                    String eventId = ChatSecrets.id();

                    db.update(
                            """
                            INSERT INTO chat_events(
                                id, room_id, event_seq, event_type, target_id, target_version, payload_json, created_at
                            )
                            VALUES (?, ?, ?, 'MESSAGE_CREATED', ?, 1, CAST(? AS JSON), ?)
                            """,
                            eventId,
                            room.roomId(),
                            nextEventSeq,
                            messageId,
                            payloadJson,
                            createdAt);

                    RoomEvent roomEvent =
                            new RoomEvent(
                                    RoomEventType.MESSAGE_CREATED,
                                    room.roomId(),
                                    nextEventSeq,
                                    messageId,
                                    1L,
                                    readTree(payloadJson));
                    outbox.publish(
                            new OutboxPublisherPort.Event(
                                    eventId,
                                    "CHAT_MESSAGE_CREATED",
                                    "CHAT_ROOM",
                                    room.roomId(),
                                    ChatSecrets.toJson(mapper, roomEvent),
                                    now));

                    String wsEnvelopeJson =
                            ChatSecrets.toJson(mapper, new WsEnvelope("ROOM_EVENT", roomEvent));
                    afterCommit(() -> broadcastRoomEventSafely(wsEnvelopeJson));

                    return new ThreadSummary(
                            messageId, room.roomId(), DiscussionStatus.OPEN, null, 1L);
                });
    }

    @Override
    public ThreadSummary syncConclusionStatus(
            String spaceId,
            String rootMessageId,
            String actorUserId,
            long membershipEpoch,
            DiscussionStatus status,
            String conclusionMessageId,
            String idempotencyKey) {
        Discussion updated =
                changeDiscussion(
                        spaceId,
                        actorUserId,
                        membershipEpoch,
                        rootMessageId,
                        idempotencyKey,
                        new ChangeDiscussionRequest(status, conclusionMessageId));
        RoomRow room = findRoomBySpaceId(spaceId);
        if (room == null) {
            throw new IllegalStateException("Missing chat room after discussion status update");
        }
        return new ThreadSummary(
                rootMessageId,
                room.roomId(),
                updated.status(),
                updated.conclusionMsgId(),
                updated.version());
    }

    public Map<String, Object> replyToDiscussionThread(Map<String, Object> input) {
        if (input == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String spaceId = requireBlankCheckedStringField(input, "spaceId");
        String userId = requireBlankCheckedStringField(input, "userId");
        long membershipEpoch = requireLongField(input, "membershipEpoch");
        String rootMessageId = requireBlankCheckedStringField(input, "rootMessageId");
        String rawClientMsgId = requireBlankCheckedStringField(input, "clientMsgId");
        String bodyText = requireNonEmptyStringField(input, "bodyText");

        String normalizedClientMsgId = normalizeStrictClientMsgId(rawClientMsgId);
        MessageAck ack =
                sendMessage(
                        userId,
                        spaceId,
                        membershipEpoch,
                        new SendMessageRequest(
                                normalizedClientMsgId, rootMessageId, new TextContent(bodyText)));

        Instant createdAt =
                db.query(
                                "SELECT created_at FROM chat_messages WHERE id = ?",
                                (rs, i) -> rs.getTimestamp(1).toInstant(),
                                ack.messageId())
                        .stream()
                        .findFirst()
                        .orElseGet(() -> Instant.now().truncatedTo(ChronoUnit.MICROS));

        return Map.of(
                "messageId", ack.messageId(),
                "senderId", userId,
                "bodyText", bodyText,
                "createdAt", createdAt.toString());
    }

    @Override
    public void onLifecycleChanged(ResourceLifecycleHook.Change change) {
        if (change == null || change.reference() == null) {
            throw new IllegalArgumentException("Lifecycle change requires reference");
        }
        Reference ref = change.reference();
        GrantType fileGrantType =
                ref.kind() == MessageKind.FILE_REF
                        ? (ref.grantType() != null ? ref.grantType() : GrantType.SPACE_FILE)
                        : null;
        boolean isAirdrop =
                ref.kind() == MessageKind.FILE_REF && fileGrantType == GrantType.AIRDROP_SHARE;
        if (!isAirdrop && (change.spaceId() == null || change.spaceId().isBlank())) {
            throw new IllegalArgumentException("Lifecycle change requires spaceId");
        }

        tx.executeWithoutResult(
                status -> {
                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    String payloadJson =
                            ChatSecrets.toJson(mapper, new ResourceCardStateChangedPayload(true));
                    JsonNode payloadNode = readTree(payloadJson);
                    List<String> envelopeJsons = new ArrayList<>();

                    if (isAirdrop) {
                        List<String> roomIds =
                                db.queryForList(
                                        """
                                        SELECT DISTINCT room_id
                                        FROM chat_messages
                                        WHERE kind = 'FILE_REF'
                                          AND ref_grant_type = 'AIRDROP_SHARE'
                                          AND ref_target_id = ?
                                        ORDER BY room_id ASC
                                        """,
                                        String.class,
                                        ref.targetId());
                        for (String roomId : roomIds) {
                            RoomRow room = lockRoomById(roomId);
                            if (room == null) {
                                continue;
                            }
                            List<String> messageIds =
                                    db.queryForList(
                                            """
                                            SELECT id
                                            FROM chat_messages
                                            WHERE room_id = ?
                                              AND kind = 'FILE_REF'
                                              AND ref_grant_type = 'AIRDROP_SHARE'
                                              AND ref_target_id = ?
                                            ORDER BY seq ASC
                                            """,
                                            String.class,
                                            room.roomId(),
                                            ref.targetId());
                            emitCardStateChangedEvents(
                                    room,
                                    messageIds,
                                    change.lifecycleVersion(),
                                    now,
                                    payloadJson,
                                    payloadNode,
                                    envelopeJsons);
                        }
                    } else if (ref.kind() == MessageKind.FILE_REF) {
                        RoomRow room = lockRoomBySpaceId(change.spaceId());
                        if (room == null) {
                            return;
                        }
                        List<String> messageIds =
                                db.queryForList(
                                        """
                                        SELECT id
                                        FROM chat_messages
                                        WHERE room_id = ?
                                          AND kind = 'FILE_REF'
                                          AND ref_grant_type = 'SPACE_FILE'
                                          AND ref_target_id = ?
                                        ORDER BY seq ASC
                                        """,
                                        String.class,
                                        room.roomId(),
                                        ref.targetId());
                        emitCardStateChangedEvents(
                                room,
                                messageIds,
                                change.lifecycleVersion(),
                                now,
                                payloadJson,
                                payloadNode,
                                envelopeJsons);
                    } else if (ref.kind() == MessageKind.DOC_REF
                            || ref.kind() == MessageKind.PDF_REF) {
                        RoomRow room = lockRoomBySpaceId(change.spaceId());
                        if (room == null) {
                            return;
                        }
                        if (change.state() != ResourceLifecycleState.ACTIVE) {
                            String persistedState =
                                    change.state() == ResourceLifecycleState.FORCE_PURGED
                                            ? "FORCE_PURGED"
                                            : "EXPIRED_OR_DELETED";
                            Timestamp invalidatedAt = Timestamp.from(now);
                            db.update(
                                    """
                                    UPDATE chat_doc_pdf_cards
                                    SET quoted_text = NULL,
                                        context_before = NULL,
                                        context_after = NULL,
                                        relative_start = CASE WHEN ? = 'FORCE_PURGED' THEN NULL ELSE relative_start END,
                                        relative_end = CASE WHEN ? = 'FORCE_PURGED' THEN NULL ELSE relative_end END,
                                        rectangles_json = CASE WHEN ? = 'FORCE_PURGED' THEN NULL ELSE rectangles_json END,
                                        lifecycle_state = CASE
                                            WHEN lifecycle_state = 'FORCE_PURGED' THEN 'FORCE_PURGED'
                                            ELSE ?
                                        END,
                                        lifecycle_version = GREATEST(lifecycle_version, ?),
                                        invalidated_at = COALESCE(invalidated_at, ?)
                                    WHERE room_id = ?
                                      AND kind = ?
                                      AND comment_id = ?
                                    """,
                                    persistedState,
                                    persistedState,
                                    persistedState,
                                    persistedState,
                                    Math.max(1L, change.lifecycleVersion()),
                                    invalidatedAt,
                                    room.roomId(),
                                    ref.kind().name(),
                                    ref.targetId());
                        }
                        List<String> messageIds =
                                db.queryForList(
                                        """
                                        SELECT id
                                        FROM chat_messages
                                        WHERE room_id = ?
                                          AND kind = ?
                                          AND ref_target_id = ?
                                        ORDER BY seq ASC
                                        """,
                                        String.class,
                                        room.roomId(),
                                        ref.kind().name(),
                                        ref.targetId());
                        emitCardStateChangedEvents(
                                room,
                                messageIds,
                                change.lifecycleVersion(),
                                now,
                                payloadJson,
                                payloadNode,
                                envelopeJsons);
                    } else {
                        RoomRow room = lockRoomBySpaceId(change.spaceId());
                        if (room == null) {
                            return;
                        }
                        List<String> messageIds =
                                db.queryForList(
                                        """
                                        SELECT id
                                        FROM chat_messages
                                        WHERE room_id = ?
                                          AND ref_project_id = ?
                                          AND kind = ?
                                          AND ref_target_id = ?
                                        ORDER BY seq ASC
                                        """,
                                        String.class,
                                        room.roomId(),
                                        ref.projectId(),
                                        ref.kind().name(),
                                        ref.targetId());
                        emitCardStateChangedEvents(
                                room,
                                messageIds,
                                change.lifecycleVersion(),
                                now,
                                payloadJson,
                                payloadNode,
                                envelopeJsons);
                    }

                    if (!envelopeJsons.isEmpty()) {
                        afterCommit(() -> envelopeJsons.forEach(this::broadcastRoomEventSafely));
                    }
                });
    }

    private void emitCardStateChangedEvents(
            RoomRow room,
            List<String> messageIds,
            long lifecycleVersion,
            Instant now,
            String payloadJson,
            JsonNode payloadNode,
            List<String> envelopeJsons) {
        if (messageIds.isEmpty()) {
            return;
        }
        long nextEventSeq = room.maxEventSeq();
        long requestedVersion = Math.max(1L, lifecycleVersion);
        Timestamp createdAt = Timestamp.from(now);

        for (String messageId : messageIds) {
            Long maxExistingVersion =
                    db.queryForObject(
                            """
                            SELECT COALESCE(MAX(target_version), 0)
                            FROM chat_events
                            WHERE room_id = ?
                              AND event_type = 'RESOURCE_CARD_STATE_CHANGED'
                              AND target_id = ?
                            """,
                            Long.class,
                            room.roomId(),
                            messageId);
            long existingVersion = maxExistingVersion != null ? maxExistingVersion : 0L;
            long targetVersion = Math.max(requestedVersion, existingVersion + 1L);

            nextEventSeq++;
            String eventId = ChatSecrets.id();
            db.update(
                    """
                    INSERT INTO chat_events(
                        id, room_id, event_seq, event_type, target_id, target_version, payload_json, created_at
                    )
                    VALUES (?, ?, ?, 'RESOURCE_CARD_STATE_CHANGED', ?, ?, CAST(? AS JSON), ?)
                    """,
                    eventId,
                    room.roomId(),
                    nextEventSeq,
                    messageId,
                    targetVersion,
                    payloadJson,
                    createdAt);
            RoomEvent roomEvent =
                    new RoomEvent(
                            RoomEventType.RESOURCE_CARD_STATE_CHANGED,
                            room.roomId(),
                            nextEventSeq,
                            messageId,
                            targetVersion,
                            payloadNode);
            outbox.publish(
                    new OutboxPublisherPort.Event(
                            eventId,
                            "CHAT_RESOURCE_CARD_STATE_CHANGED",
                            "CHAT_ROOM",
                            room.roomId(),
                            ChatSecrets.toJson(mapper, roomEvent),
                            now));
            envelopeJsons.add(ChatSecrets.toJson(mapper, new WsEnvelope("ROOM_EVENT", roomEvent)));
        }

        db.update(
                "UPDATE chat_rooms SET max_event_seq = ? WHERE id = ?",
                nextEventSeq,
                room.roomId());
    }

    public RoomInfo getRoom(String spaceId, String userId, long membershipEpoch) {
        authorization.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.VIEWER);
        return tx.execute(
                status -> {
                    RoomRow room = ensureRoomRow(spaceId);
                    ReadStateRow readState = ensureReadState(
                            room.roomId(), spaceId, userId, membershipEpoch, room.maxSeq(), false);
                    return new RoomInfo(
                            spaceId,
                            room.roomId(),
                            membershipEpoch,
                            readState.joinedBaselineSeq(),
                            readState.lastReadSeq(),
                            room.toBoundary());
                });
    }

    public MessageAck forwardDocumentComment(
            String spaceId,
            String documentId,
            String commentId,
            String userId,
            long membershipEpoch,
            String idempotencyKey,
            ForwardCommentRequest request) {
        authorization.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.MEMBER);
        validateId(spaceId, 64);
        validateId(documentId, 64);
        validateId(commentId, 64);
        ChatIdempotencyStore.validateKey(idempotencyKey);
        if (request == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String storedClientMsgId = normalizeStrictClientMsgId(request.clientMsgId());
        validatePurpose(request.purpose());

        DocumentCommentAnchorRow anchor =
                loadDocumentCommentAnchor(spaceId, documentId, commentId);
        requireResourceActive(
                userId,
                spaceId,
                membershipEpoch,
                new Reference(MessageKind.DOC_REF, null, anchor.commentId()));

        DocRefContent content =
                new DocRefContent(
                        anchor.documentId(),
                        anchor.commentId(),
                        anchor.persistedVersionId(),
                        anchor.relativeStart(),
                        anchor.relativeEnd(),
                        request.purpose());
        CanonicalForwardComment canonicalInput =
                new CanonicalForwardComment(
                        MessageKind.DOC_REF,
                        documentId,
                        anchor.persistedVersionId(),
                        commentId,
                        storedClientMsgId,
                        request.purpose());

        return forwardDocOrPdfCommentInternal(
                "FORWARD_DOC_COMMENT",
                spaceId,
                userId,
                idempotencyKey,
                storedClientMsgId,
                canonicalInput,
                anchor,
                content);
    }

    public MessageAck forwardPdfComment(
            String spaceId,
            String fileId,
            String fileVersionId,
            String commentId,
            String userId,
            long membershipEpoch,
            String idempotencyKey,
            ForwardCommentRequest request) {
        authorization.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.MEMBER);
        validateId(spaceId, 64);
        validateId(fileId, 64);
        validateId(fileVersionId, 64);
        validateId(commentId, 64);
        ChatIdempotencyStore.validateKey(idempotencyKey);
        if (request == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String storedClientMsgId = normalizeStrictClientMsgId(request.clientMsgId());
        validatePurpose(request.purpose());

        DocumentCommentAnchorRow anchor =
                loadPdfCommentAnchor(spaceId, fileId, fileVersionId, commentId);
        requireResourceActive(
                userId,
                spaceId,
                membershipEpoch,
                new Reference(MessageKind.PDF_REF, null, anchor.commentId()));

        PdfRefContent content =
                new PdfRefContent(
                        anchor.fileId(),
                        anchor.fileVersionId(),
                        anchor.commentId(),
                        anchor.pageNumber(),
                        anchor.rectangles(),
                        request.purpose());
        CanonicalForwardComment canonicalInput =
                new CanonicalForwardComment(
                        MessageKind.PDF_REF,
                        fileId,
                        fileVersionId,
                        commentId,
                        storedClientMsgId,
                        request.purpose());

        return forwardDocOrPdfCommentInternal(
                "FORWARD_PDF_COMMENT",
                spaceId,
                userId,
                idempotencyKey,
                storedClientMsgId,
                canonicalInput,
                anchor,
                content);
    }

    private MessageAck forwardDocOrPdfCommentInternal(
            String operation,
            String spaceId,
            String userId,
            String idempotencyKey,
            String storedClientMsgId,
            CanonicalForwardComment canonicalInput,
            DocumentCommentAnchorRow anchor,
            ClientContent content) {
        return tx.execute(
                status -> {
                    RoomRow room = ensureAndLockRoom(spaceId);
                    return idempotency.execute(
                            operation,
                            room.roomId(),
                            userId,
                            idempotencyKey,
                            canonicalInput,
                            MessageAck.class,
                            () -> {
                                requireSourceStillActiveInTx(spaceId, anchor);
                                ThreadSummary sharedThread =
                                        findThread(spaceId, anchor.discussionThreadId())
                                                .filter(t -> room.roomId().equals(t.roomId()))
                                                .orElseThrow(
                                                        () ->
                                                                new DomainException(
                                                                        KernelErrorCode
                                                                                .RESOURCE_NOT_FOUND));
                                String threadRootMsgId = sharedThread.rootMessageId();
                                String payloadDigest =
                                        ChatSecrets.digestJson(
                                                mapper,
                                                new CanonicalSendPayload(
                                                        threadRootMsgId, content));
                                MessageAck existingByClientId =
                                        findExistingMessageAck(
                                                room.roomId(),
                                                userId,
                                                storedClientMsgId,
                                                payloadDigest);
                                if (existingByClientId != null) {
                                    return existingByClientId;
                                }

                                long nextSeq = room.maxSeq() + 1;
                                long nextEventSeq = room.maxEventSeq() + 1;
                                db.update(
                                        "UPDATE chat_rooms SET max_msg_seq = ?, max_event_seq = ? WHERE id = ?",
                                        nextSeq,
                                        nextEventSeq,
                                        room.roomId());

                                String messageId = ChatSecrets.id();
                                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                                Timestamp createdAt = Timestamp.from(now);
                                String refPurpose =
                                        content instanceof DocRefContent doc
                                                ? doc.purpose()
                                                : ((PdfRefContent) content).purpose();

                                db.update(
                                        """
                                        INSERT INTO chat_messages(
                                            id, room_id, seq, sender_id, client_msg_id, kind,
                                            body_text, ref_project_id, ref_target_id, ref_grant_type, ref_purpose,
                                            reply_to_msg_id, payload_digest, discussion_version, created_at
                                        )
                                        VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL, ?, ?, ?, NULL, ?)
                                        """,
                                        messageId,
                                        room.roomId(),
                                        nextSeq,
                                        userId,
                                        storedClientMsgId,
                                        content.messageKind().name(),
                                        anchor.commentId(),
                                        refPurpose,
                                        threadRootMsgId,
                                        payloadDigest,
                                        createdAt);

                                bindDiscussionThreadAnchor(
                                        room.roomId(),
                                        threadRootMsgId,
                                        anchor.kind(),
                                        anchor.commentId());
                                insertDocPdfCardMetadata(
                                        messageId, room.roomId(), threadRootMsgId, anchor, createdAt);

                                ChatMessage chatMessage =
                                        new ChatMessage(
                                                room.roomId(),
                                                messageId,
                                                nextSeq,
                                                userId,
                                                storedClientMsgId,
                                                now,
                                                threadRootMsgId,
                                                null,
                                                content);
                                String payloadJson =
                                        ChatSecrets.toJson(
                                                mapper, new MessageCreatedPayload(chatMessage));
                                String eventId = ChatSecrets.id();

                                db.update(
                                        """
                                        INSERT INTO chat_events(
                                            id, room_id, event_seq, event_type, target_id, target_version, payload_json, created_at
                                        )
                                        VALUES (?, ?, ?, 'MESSAGE_CREATED', ?, 1, CAST(? AS JSON), ?)
                                        """,
                                        eventId,
                                        room.roomId(),
                                        nextEventSeq,
                                        messageId,
                                        payloadJson,
                                        createdAt);

                                RoomEvent roomEvent =
                                        new RoomEvent(
                                                RoomEventType.MESSAGE_CREATED,
                                                room.roomId(),
                                                nextEventSeq,
                                                messageId,
                                                1L,
                                                readTree(payloadJson));
                                outbox.publish(
                                        new OutboxPublisherPort.Event(
                                                eventId,
                                                "CHAT_MESSAGE_CREATED",
                                                "CHAT_ROOM",
                                                room.roomId(),
                                                ChatSecrets.toJson(mapper, roomEvent),
                                                now));

                                String wsEnvelopeJson =
                                        ChatSecrets.toJson(
                                                mapper, new WsEnvelope("ROOM_EVENT", roomEvent));
                                afterCommit(() -> broadcastRoomEventSafely(wsEnvelopeJson));

                                return new MessageAck(
                                        room.roomId(),
                                        storedClientMsgId,
                                        messageId,
                                        nextSeq,
                                        nextEventSeq);
                            });
                });
    }

    public MessageAck sendMessage(
            String userId, String spaceId, long membershipEpoch, SendMessageRequest request) {
        authorization.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.MEMBER);
        validateSendMessage(request);

        DocumentCommentAnchorRow docPdfAnchor = null;
        ClientContent effectiveContent = request.content();
        if (request.content() instanceof DocRefContent docRef) {
            docPdfAnchor =
                    loadDocumentCommentAnchor(spaceId, docRef.documentId(), docRef.commentId());
            verifySubmittedDocAnchor(docRef, docPdfAnchor);
            effectiveContent =
                    new DocRefContent(
                            docPdfAnchor.documentId(),
                            docPdfAnchor.commentId(),
                            docPdfAnchor.persistedVersionId(),
                            docPdfAnchor.relativeStart(),
                            docPdfAnchor.relativeEnd(),
                            docRef.purpose());
        } else if (request.content() instanceof PdfRefContent pdfRef) {
            docPdfAnchor =
                    loadPdfCommentAnchor(
                            spaceId,
                            pdfRef.fileId(),
                            pdfRef.fileVersionId(),
                            pdfRef.commentId());
            verifySubmittedPdfAnchor(pdfRef, docPdfAnchor);
            effectiveContent =
                    new PdfRefContent(
                            docPdfAnchor.fileId(),
                            docPdfAnchor.fileVersionId(),
                            docPdfAnchor.commentId(),
                            docPdfAnchor.pageNumber(),
                            docPdfAnchor.rectangles(),
                            pdfRef.purpose());
        }

        final DocumentCommentAnchorRow resolvedAnchor = docPdfAnchor;
        final ClientContent canonicalContent = effectiveContent;

        String payloadDigest = ChatSecrets.digestJson(
                mapper, new CanonicalSendPayload(request.replyToMsgId(), canonicalContent));

        if (resolvedAnchor != null) {
            requireResourceActive(userId, spaceId, membershipEpoch, canonicalContent);
        }

        RoomRow preflightRoom = findRoomBySpaceId(spaceId);
        if (preflightRoom != null) {
            MessageAck replayed = findExistingMessageAck(
                    preflightRoom.roomId(), userId, request.clientMsgId(), payloadDigest);
            if (replayed != null) {
                return replayed;
            }
        }

        if (resolvedAnchor == null && !(canonicalContent instanceof TextContent)) {
            requireResourceActive(userId, spaceId, membershipEpoch, canonicalContent);
        }

        // Execute in TransactionTemplate so DB commit completes before returning MessageAck.
        return tx.execute(
                status -> {
                    RoomRow room = ensureAndLockRoom(spaceId);
                    MessageAck existing = findExistingMessageAck(
                            room.roomId(), userId, request.clientMsgId(), payloadDigest);
                    if (existing != null) {
                        return existing;
                    }

                    if (resolvedAnchor != null) {
                        requireSourceStillActiveInTx(spaceId, resolvedAnchor);
                    }

                    String effectiveReplyToMsgId = request.replyToMsgId();
                    if (effectiveReplyToMsgId != null) {
                        boolean parentExists = Boolean.TRUE.equals(db.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM chat_messages WHERE id = ? AND room_id = ?)",
                                Boolean.class,
                                effectiveReplyToMsgId,
                                room.roomId()));
                        if (!parentExists) {
                            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                        }
                    } else if (resolvedAnchor != null
                            && resolvedAnchor.discussionThreadId() != null) {
                        ThreadSummary existingThread =
                                findThreadByRootMsgId(
                                        room.roomId(), resolvedAnchor.discussionThreadId());
                        if (existingThread != null) {
                            effectiveReplyToMsgId = existingThread.rootMessageId();
                        }
                    }

                    long nextSeq = room.maxSeq() + 1;
                    long nextEventSeq = room.maxEventSeq() + 1;
                    db.update(
                            "UPDATE chat_rooms SET max_msg_seq = ?, max_event_seq = ? WHERE id = ?",
                            nextSeq,
                            nextEventSeq,
                            room.roomId());

                    String messageId = ChatSecrets.id();
                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    Timestamp createdAt = Timestamp.from(now);
                    boolean isRoot = effectiveReplyToMsgId == null;
                    Long discussionVersion = isRoot ? 1L : null;

                    String bodyText = null;
                    String refProjectId = null;
                    String refTargetId = null;
                    String refGrantType = null;
                    String refPurpose = null;
                    switch (canonicalContent) {
                        case TextContent text -> bodyText = text.text();
                        case CommitRefContent commit -> {
                            refProjectId = commit.projectId();
                            refTargetId = commit.commitOid();
                            refPurpose = commit.purpose();
                        }
                        case WipRefContent wip -> {
                            refProjectId = wip.projectId();
                            refTargetId = wip.snapshotId();
                            refPurpose = wip.purpose();
                        }
                        case FileRefContent file -> {
                            refTargetId = file.targetId();
                            refGrantType = file.grantType().name();
                            refPurpose = file.purpose();
                        }
                        case CodeCommentRefContent comment -> {
                            refProjectId = comment.projectId();
                            refTargetId = comment.commentId();
                            refPurpose = comment.purpose();
                        }
                        case DocRefContent doc -> {
                            refTargetId = doc.commentId();
                            refPurpose = doc.purpose();
                        }
                        case PdfRefContent pdf -> {
                            refTargetId = pdf.commentId();
                            refPurpose = pdf.purpose();
                        }
                    }

                    db.update(
                            """
                            INSERT INTO chat_messages(
                                id, room_id, seq, sender_id, client_msg_id, kind,
                                body_text, ref_project_id, ref_target_id, ref_grant_type, ref_purpose,
                                reply_to_msg_id, payload_digest, discussion_version, created_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            messageId,
                            room.roomId(),
                            nextSeq,
                            userId,
                            request.clientMsgId(),
                            canonicalContent.messageKind().name(),
                            bodyText,
                            refProjectId,
                            refTargetId,
                            refGrantType,
                            refPurpose,
                            effectiveReplyToMsgId,
                            payloadDigest,
                            discussionVersion,
                            createdAt);

                    Discussion discussion = null;
                    if (isRoot) {
                        db.update(
                                """
                                INSERT INTO discussion_threads(
                                    root_msg_id, room_id, status, conclusion_msg_id, version, updated_by_user_id, updated_at
                                )
                                VALUES (?, ?, 'OPEN', NULL, 1, ?, ?)
                                """,
                                messageId,
                                room.roomId(),
                                userId,
                                createdAt);
                        discussion = new Discussion(DiscussionStatus.OPEN, null, 1L);
                    }

                    if (resolvedAnchor != null) {
                        ThreadSummary sharedThread =
                                findThreadByRootMsgId(
                                        room.roomId(), resolvedAnchor.discussionThreadId());
                        String cardThreadId =
                                sharedThread != null
                                        ? sharedThread.rootMessageId()
                                        : (isRoot
                                                ? messageId
                                                : null);
                        if (cardThreadId == null) {
                            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
                        }
                        bindDiscussionThreadAnchor(
                                room.roomId(),
                                cardThreadId,
                                resolvedAnchor.kind(),
                                resolvedAnchor.commentId());
                        insertDocPdfCardMetadata(
                                messageId, room.roomId(), cardThreadId, resolvedAnchor, createdAt);
                    }

                    ChatMessage chatMessage = new ChatMessage(
                            room.roomId(),
                            messageId,
                            nextSeq,
                            userId,
                            request.clientMsgId(),
                            now,
                            effectiveReplyToMsgId,
                            discussion,
                            canonicalContent);
                    String payloadJson = ChatSecrets.toJson(
                            mapper, new MessageCreatedPayload(chatMessage));
                    String eventId = ChatSecrets.id();

                    db.update(
                            """
                            INSERT INTO chat_events(
                                id, room_id, event_seq, event_type, target_id, target_version, payload_json, created_at
                            )
                            VALUES (?, ?, ?, 'MESSAGE_CREATED', ?, 1, CAST(? AS JSON), ?)
                            """,
                            eventId,
                            room.roomId(),
                            nextEventSeq,
                            messageId,
                            payloadJson,
                            createdAt);

                    RoomEvent roomEvent = new RoomEvent(
                            RoomEventType.MESSAGE_CREATED,
                            room.roomId(),
                            nextEventSeq,
                            messageId,
                            1L,
                            readTree(payloadJson));
                    outbox.publish(new OutboxPublisherPort.Event(
                            eventId,
                            "CHAT_MESSAGE_CREATED",
                            "CHAT_ROOM",
                            room.roomId(),
                            ChatSecrets.toJson(mapper, roomEvent),
                            now));

                    String wsEnvelopeJson = ChatSecrets.toJson(
                            mapper, new WsEnvelope("ROOM_EVENT", roomEvent));
                    afterCommit(() -> broadcastRoomEventSafely(wsEnvelopeJson));

                    return new MessageAck(
                            room.roomId(),
                            request.clientMsgId(),
                            messageId,
                            nextSeq,
                            nextEventSeq);
                });
    }

    public HistoryPage getMessageHistory(
            String spaceId,
            String userId,
            long membershipEpoch,
            Long beforeSeq,
            Long throughSeq,
            Integer limit) {
        authorization.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.VIEWER);
        if (beforeSeq != null && (beforeSeq < 1 || beforeSeq > ChatDtos.MAX_SAFE_SEQUENCE)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (throughSeq != null && (throughSeq < 0 || throughSeq > ChatDtos.MAX_SAFE_SEQUENCE)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        int pageSize = resolvePageLimit(limit);

        return readOnlyTx.execute(
                status -> {
                    RoomRow room = findRoomBySpaceId(spaceId);
                    if (room == null) {
                        return new HistoryPage(
                                List.of(), null, new SyncBoundary(0L, 0L, 0L));
                    }
                    SyncBoundary boundary = room.toBoundary();
                    long upperInclusive = throughSeq != null
                            ? Math.min(throughSeq, boundary.maxSeq())
                            : boundary.maxSeq();
                    if (beforeSeq != null) {
                        upperInclusive = Math.min(upperInclusive, beforeSeq - 1);
                    }
                    if (upperInclusive <= 0) {
                        return new HistoryPage(List.of(), null, boundary);
                    }

                    List<ChatMessage> rows = db.query(
                            """
                            SELECT m.id, m.room_id, m.seq, m.sender_id, m.client_msg_id, m.kind,
                                   m.body_text, m.ref_project_id, m.ref_target_id, m.ref_grant_type, m.ref_purpose,
                                   m.reply_to_msg_id, m.created_at,
                                   dt.root_msg_id, dt.status, dt.conclusion_msg_id, dt.version,
                                   c.document_id, c.persisted_version_id, c.relative_start, c.relative_end,
                                   c.file_id, c.file_version_id, c.page_number, c.rectangles_json, c.lifecycle_state
                            FROM chat_messages m
                            LEFT JOIN discussion_threads dt ON dt.root_msg_id = m.id
                            LEFT JOIN chat_doc_pdf_cards c ON c.message_id = m.id
                            WHERE m.room_id = ? AND m.seq <= ?
                            ORDER BY m.seq DESC
                            LIMIT ?
                            """,
                            (rs, i) -> mapChatMessage(rs),
                            room.roomId(),
                            upperInclusive,
                            pageSize + 1);

                    if (rows.size() > pageSize) {
                        List<ChatMessage> page = List.copyOf(rows.subList(0, pageSize));
                        return new HistoryPage(page, page.getLast().seq(), boundary);
                    }
                    return new HistoryPage(List.copyOf(rows), null, boundary);
                });
    }

    public EventPage getRoomEvents(
            String spaceId,
            String userId,
            long membershipEpoch,
            Long afterEventSeq,
            Long throughEventSeq,
            Integer limit) {
        authorization.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.VIEWER);
        if (afterEventSeq == null
                || afterEventSeq < 0
                || afterEventSeq > ChatDtos.MAX_SAFE_SEQUENCE) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (throughEventSeq != null
                && (throughEventSeq < 0 || throughEventSeq > ChatDtos.MAX_SAFE_SEQUENCE)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        int pageSize = resolvePageLimit(limit);

        return readOnlyTx.execute(
                status -> {
                    RoomRow room = findRoomBySpaceId(spaceId);
                    SyncBoundary boundary = room != null
                            ? room.toBoundary()
                            : new SyncBoundary(0L, 0L, 0L);

                    if (afterEventSeq < boundary.minAfterEventSeq()) {
                        throw new DomainException(
                                KernelErrorCode.WS_CURSOR_EXPIRED,
                                Map.of(
                                        "boundary", boundary.toMap(),
                                        "resumeAfterEventSeq", boundary.maxEventSeq()));
                    }

                    long capturedUpper = throughEventSeq != null
                            ? Math.min(throughEventSeq, boundary.maxEventSeq())
                            : boundary.maxEventSeq();
                    long upperBound = Math.max(afterEventSeq, capturedUpper);
                    if (room == null || upperBound <= afterEventSeq) {
                        return new EventPage(List.of(), upperBound, false, boundary);
                    }

                    List<RoomEvent> rows = db.query(
                            """
                            SELECT room_id, event_seq, event_type, target_id, target_version, payload_json
                            FROM chat_events
                            WHERE room_id = ? AND event_seq > ? AND event_seq <= ?
                            ORDER BY event_seq ASC
                            LIMIT ?
                            """,
                            (rs, i) -> new RoomEvent(
                                    RoomEventType.valueOf(rs.getString(3)),
                                    rs.getString(1),
                                    rs.getLong(2),
                                    rs.getString(4),
                                    rs.getLong(5),
                                    readTree(rs.getString(6))),
                            room.roomId(),
                            afterEventSeq,
                            upperBound,
                            pageSize + 1);

                    boolean hasMore = rows.size() > pageSize;
                    List<RoomEvent> events = hasMore
                            ? List.copyOf(rows.subList(0, pageSize))
                            : List.copyOf(rows);
                    long nextAfterEventSeq = events.isEmpty()
                            ? upperBound
                            : (hasMore ? events.getLast().eventSeq() : upperBound);
                    return new EventPage(events, nextAfterEventSeq, hasMore, boundary);
                });
    }

    public ReadCursorResponse advanceLastRead(
            String spaceId, String userId, long membershipEpoch, AdvanceReadRequest request) {
        authorization.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.VIEWER);
        if (request == null
                || request.lastReadSeq() == null
                || request.lastReadSeq() < 0
                || request.lastReadSeq() > ChatDtos.MAX_SAFE_SEQUENCE) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        return tx.execute(
                status -> {
                    RoomRow room = ensureRoomRow(spaceId);
                    ensureReadState(
                            room.roomId(), spaceId, userId, membershipEpoch, room.maxSeq(), true);

                    db.update(
                            """
                            UPDATE chat_read_states
                            SET last_read_seq = GREATEST(last_read_seq, LEAST(?, ?)),
                                updated_at = CURRENT_TIMESTAMP(6)
                            WHERE room_id = ? AND user_id = ? AND membership_epoch = ?
                            """,
                            request.lastReadSeq(),
                            room.maxSeq(),
                            room.roomId(),
                            userId,
                            membershipEpoch);

                    long persisted = db.queryForObject(
                            """
                            SELECT last_read_seq
                            FROM chat_read_states
                            WHERE room_id = ? AND user_id = ? AND membership_epoch = ?
                            """,
                            Long.class,
                            room.roomId(),
                            userId,
                            membershipEpoch);
                    return new ReadCursorResponse(persisted);
                });
    }

    public Discussion changeDiscussion(
            String spaceId,
            String userId,
            long membershipEpoch,
            String messageId,
            String idempotencyKey,
            ChangeDiscussionRequest request) {
        SpaceAuthorizationPort.Membership actor = authorization.requireMembership(
                spaceId, userId, membershipEpoch, SpaceRole.MEMBER);
        validateId(messageId, 64);
        if (request == null || request.status() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (request.conclusionMsgId() != null) {
            validateId(request.conclusionMsgId(), 64);
        }
        ChatIdempotencyStore.validateKey(idempotencyKey);

        var canonicalInput = new CanonicalChangeDiscussion(
                messageId, request.status(), request.conclusionMsgId());

        return tx.execute(
                status -> {
                    RoomRow room = ensureAndLockRoom(spaceId);
                    return idempotency.execute(
                            "CHANGE_DISCUSSION",
                            room.roomId(),
                            userId,
                            idempotencyKey,
                            canonicalInput,
                            Discussion.class,
                            () -> changeDiscussionInternal(room, actor, messageId, request));
                });
    }

    private Discussion changeDiscussionInternal(
            RoomRow room,
            SpaceAuthorizationPort.Membership actor,
            String messageId,
            ChangeDiscussionRequest request) {
        LockedRootMessage rootMessage = db.query(
                        """
                        SELECT sender_id, reply_to_msg_id
                        FROM chat_messages
                        WHERE id = ? AND room_id = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> new LockedRootMessage(rs.getString(1), rs.getString(2)),
                        messageId,
                        room.roomId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));

        if (rootMessage.replyToMsgId() != null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        LockedThread thread = db.query(
                        """
                        SELECT status, conclusion_msg_id, version
                        FROM discussion_threads
                        WHERE root_msg_id = ? AND room_id = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> new LockedThread(
                                DiscussionStatus.valueOf(rs.getString(1)),
                                rs.getString(2),
                                rs.getLong(3)),
                        messageId,
                        room.roomId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.INVALID_REQUEST));

        boolean authorized = actor.role().atLeast(SpaceRole.ADMIN)
                || actor.userId().equals(rootMessage.senderId());
        if (!authorized) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }

        String conclusionMsgId = request.conclusionMsgId();
        if (conclusionMsgId != null) {
            ReplyParentRow conclusionRow = db.query(
                            """
                            SELECT id, reply_to_msg_id
                            FROM chat_messages
                            WHERE id = ? AND room_id = ?
                            """,
                            (rs, i) -> new ReplyParentRow(rs.getString(1), rs.getString(2)),
                            conclusionMsgId,
                            room.roomId())
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new DomainException(KernelErrorCode.INVALID_REQUEST));
            if (!messageId.equals(conclusionRow.id())
                    && !messageId.equals(conclusionRow.replyToMsgId())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }

        long newVersion = thread.version() + 1;
        long nextEventSeq = room.maxEventSeq() + 1;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Timestamp updatedAt = Timestamp.from(now);

        db.update(
                "UPDATE chat_rooms SET max_event_seq = ? WHERE id = ?",
                nextEventSeq,
                room.roomId());
        db.update(
                """
                UPDATE discussion_threads
                SET status = ?, conclusion_msg_id = ?, version = ?, updated_by_user_id = ?, updated_at = ?
                WHERE root_msg_id = ? AND room_id = ?
                """,
                request.status().name(),
                conclusionMsgId,
                newVersion,
                actor.userId(),
                updatedAt,
                messageId,
                room.roomId());
        db.update(
                "UPDATE chat_messages SET discussion_version = ? WHERE id = ?",
                newVersion,
                messageId);

        Discussion updated = new Discussion(request.status(), conclusionMsgId, newVersion);
        String payloadJson = ChatSecrets.toJson(
                mapper, new DiscussionStatusChangedPayload(updated));
        String eventId = ChatSecrets.id();

        db.update(
                """
                INSERT INTO chat_events(
                    id, room_id, event_seq, event_type, target_id, target_version, payload_json, created_at
                )
                VALUES (?, ?, ?, 'DISCUSSION_STATUS_CHANGED', ?, ?, CAST(? AS JSON), ?)
                """,
                eventId,
                room.roomId(),
                nextEventSeq,
                messageId,
                newVersion,
                payloadJson,
                updatedAt);

        RoomEvent roomEvent = new RoomEvent(
                RoomEventType.DISCUSSION_STATUS_CHANGED,
                room.roomId(),
                nextEventSeq,
                messageId,
                newVersion,
                readTree(payloadJson));
        outbox.publish(new OutboxPublisherPort.Event(
                eventId,
                "CHAT_DISCUSSION_STATUS_CHANGED",
                "CHAT_ROOM",
                room.roomId(),
                ChatSecrets.toJson(mapper, roomEvent),
                now));

        String wsEnvelopeJson = ChatSecrets.toJson(mapper, new WsEnvelope("ROOM_EVENT", roomEvent));
        afterCommit(() -> broadcastRoomEventSafely(wsEnvelopeJson));

        return updated;
    }

    public List<ResourceCardResult> resolveResourceCards(
            String spaceId, String userId, long membershipEpoch, ResolveCardsRequest request) {
        authorization.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.VIEWER);
        if (request == null
                || request.messageIds() == null
                || request.messageIds().isEmpty()
                || request.messageIds().size() > 100) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        Set<String> uniqueIds = new HashSet<>(request.messageIds().size());
        for (String messageId : request.messageIds()) {
            validateId(messageId, 160);
            if (!uniqueIds.add(messageId)) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }

        Map<String, CardReferenceRow> refsByMsgId = readOnlyTx.execute(
                status -> {
                    RoomRow room = findRoomBySpaceId(spaceId);
                    if (room == null) {
                        return Map.<String, CardReferenceRow>of();
                    }
                    String placeholders = String.join(
                            ",", Collections.nCopies(request.messageIds().size(), "?"));
                    List<Object> params = new ArrayList<>(1 + request.messageIds().size());
                    params.add(room.roomId());
                    params.addAll(request.messageIds());

                    Map<String, CardReferenceRow> found = new HashMap<>();
                    db.query(
                            """
                            SELECT m.id, m.kind, m.ref_project_id, m.ref_target_id, m.ref_grant_type, c.lifecycle_state
                            FROM chat_messages m
                            LEFT JOIN chat_doc_pdf_cards c ON c.message_id = m.id
                            WHERE m.room_id = ?
                              AND m.kind IN ('COMMIT_REF', 'WIP_REF', 'FILE_REF', 'CODE_COMMENT_REF', 'DOC_REF', 'PDF_REF')
                              AND m.id IN (%s)
                            """.formatted(placeholders),
                            rs -> {
                                String id = rs.getString(1);
                                MessageKind kind = MessageKind.valueOf(rs.getString(2));
                                String projectId = rs.getString(3);
                                String targetId = rs.getString(4);
                                String grantTypeRaw = rs.getString(5);
                                String cardLifecycleState = rs.getString(6);
                                if (targetId == null) {
                                    return;
                                }
                                if (kind == MessageKind.FILE_REF) {
                                    GrantType grantType =
                                            grantTypeRaw != null
                                                    ? GrantType.valueOf(grantTypeRaw)
                                                    : GrantType.SPACE_FILE;
                                    found.put(
                                            id,
                                            new CardReferenceRow(
                                                    new Reference(kind, null, targetId, grantType),
                                                    cardLifecycleState));
                                } else if (kind == MessageKind.DOC_REF
                                        || kind == MessageKind.PDF_REF) {
                                    found.put(
                                            id,
                                            new CardReferenceRow(
                                                    new Reference(kind, null, targetId),
                                                    cardLifecycleState));
                                } else if (projectId != null) {
                                    found.put(
                                            id,
                                            new CardReferenceRow(
                                                    new Reference(kind, projectId, targetId),
                                                    cardLifecycleState));
                                }
                            },
                            params.toArray());
                    return found;
                });

        if (refsByMsgId.isEmpty()) {
            return List.of();
        }

        List<ResourceCardResult> results = new ArrayList<>(refsByMsgId.size());
        for (String messageId : request.messageIds()) {
            CardReferenceRow cardRow = refsByMsgId.get(messageId);
            if (cardRow != null) {
                CardView view =
                        resolveCardView(
                                userId,
                                spaceId,
                                membershipEpoch,
                                cardRow.reference(),
                                cardRow.cardLifecycleState());
                results.add(new ResourceCardResult(messageId, view));
            }
        }
        return List.copyOf(results);
    }

    private CardView resolveCardView(
            String userId,
            String spaceId,
            long membershipEpoch,
            Reference reference,
            String cardLifecycleState) {
        ResourceCardVerifierPort verifier = findVerifier(reference.kind());
        if (verifier == null) {
            return CardView.forbidden();
        }
        boolean forcePurged = "FORCE_PURGED".equals(cardLifecycleState);
        boolean expiredOrDeleted = "EXPIRED_OR_DELETED".equals(cardLifecycleState);
        try {
            Preview preview = verifier.verify(userId, spaceId, membershipEpoch, reference);
            if (preview == null) {
                return CardView.forbidden();
            }
            return switch (preview.state()) {
                case ACTIVE -> {
                    if (forcePurged) {
                        yield CardView.forcePurged();
                    }
                    if (expiredOrDeleted) {
                        yield CardView.expiredOrDeleted();
                    }
                    yield CardView.available(preview.label());
                }
                case REVOKED_FOR_ACTOR -> CardView.forbidden();
                case EXPIRED_OR_DELETED ->
                        forcePurged ? CardView.forcePurged() : CardView.expiredOrDeleted();
                case FORCE_PURGED -> CardView.forcePurged();
            };
        } catch (DomainException ex) {
            return switch (ex.errorCode()) {
                case KernelErrorCode.AUTH_FORBIDDEN, KernelErrorCode.SHARE_REVOKED ->
                        CardView.forbidden();
                case KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED,
                        KernelErrorCode.RESOURCE_NOT_FOUND ->
                        forcePurged ? CardView.forcePurged() : CardView.expiredOrDeleted();
                default -> throw ex;
            };
        }
    }

    private void requireResourceActive(
            String userId, String spaceId, long membershipEpoch, ClientContent content) {
        Reference reference = switch (content) {
            case CommitRefContent commit ->
                    new Reference(MessageKind.COMMIT_REF, commit.projectId(), commit.commitOid());
            case WipRefContent wip ->
                    new Reference(MessageKind.WIP_REF, wip.projectId(), wip.snapshotId());
            case FileRefContent file ->
                    new Reference(MessageKind.FILE_REF, null, file.targetId(), file.grantType());
            case CodeCommentRefContent comment ->
                    new Reference(
                            MessageKind.CODE_COMMENT_REF, comment.projectId(), comment.commentId());
            case DocRefContent doc ->
                    new Reference(MessageKind.DOC_REF, null, doc.commentId());
            case PdfRefContent pdf ->
                    new Reference(MessageKind.PDF_REF, null, pdf.commentId());
            case TextContent ignored -> throw new IllegalStateException();
        };
        requireResourceActive(userId, spaceId, membershipEpoch, reference);
    }

    private void requireResourceActive(
            String userId, String spaceId, long membershipEpoch, Reference reference) {
        ResourceCardVerifierPort verifier = findVerifier(reference.kind());
        if (verifier == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        Preview preview = verifier.verify(userId, spaceId, membershipEpoch, reference);
        if (preview == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        switch (preview.state()) {
            case ACTIVE -> {}
            case REVOKED_FOR_ACTOR -> throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
            case EXPIRED_OR_DELETED, FORCE_PURGED ->
                    throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
    }

    private ResourceCardVerifierPort findVerifier(MessageKind kind) {
        for (ResourceCardVerifierPort verifier : verifiers) {
            if (verifier.supports(kind)) {
                return verifier;
            }
        }
        return null;
    }

    private ExistingMessageRow findExistingMessageRow(
            String roomId, String senderId, String clientMsgId) {
        return db.query(
                        """
                        SELECT id, seq, payload_digest
                        FROM chat_messages
                        WHERE room_id = ? AND sender_id = ? AND client_msg_id = ?
                        """,
                        (rs, i) -> new ExistingMessageRow(
                                rs.getString(1), rs.getLong(2), rs.getString(3)),
                        roomId,
                        senderId,
                        clientMsgId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private MessageAck findExistingMessageAck(
            String roomId, String senderId, String clientMsgId, String payloadDigest) {
        ExistingMessageRow existing = findExistingMessageRow(roomId, senderId, clientMsgId);
        if (existing == null) {
            return null;
        }
        if (!payloadDigest.equals(existing.payloadDigest())) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }
        Long eventSeq = db.query(
                        """
                        SELECT event_seq
                        FROM chat_events
                        WHERE room_id = ? AND event_type = 'MESSAGE_CREATED' AND target_id = ?
                        """,
                        (rs, i) -> rs.getLong(1),
                        roomId,
                        existing.id())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing MESSAGE_CREATED event"));
        return new MessageAck(roomId, clientMsgId, existing.id(), existing.seq(), eventSeq);
    }

    private ThreadSummary findThreadByCodeComment(
            String roomId, String projectId, String commentId) {
        return db.query(
                        """
                        SELECT root_msg_id, room_id, status, conclusion_msg_id, version
                        FROM discussion_threads
                        WHERE room_id = ?
                          AND source_kind = 'CODE_COMMENT'
                          AND source_project_id = ?
                          AND source_anchor_id = ?
                        ORDER BY root_msg_id ASC
                        LIMIT 1
                        """,
                        (rs, i) -> new ThreadSummary(
                                rs.getString(1),
                                rs.getString(2),
                                DiscussionStatus.valueOf(rs.getString(3)),
                                rs.getString(4),
                                rs.getLong(5)),
                        roomId,
                        projectId,
                        commentId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private ThreadSummary findThreadByRootMsgId(String roomId, String rootMsgId) {
        return db.query(
                        """
                        SELECT root_msg_id, room_id, status, conclusion_msg_id, version
                        FROM discussion_threads
                        WHERE root_msg_id = ? AND room_id = ?
                        """,
                        (rs, i) -> new ThreadSummary(
                                rs.getString(1),
                                rs.getString(2),
                                DiscussionStatus.valueOf(rs.getString(3)),
                                rs.getString(4),
                                rs.getLong(5)),
                        rootMsgId,
                        roomId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private RoomRow findRoomBySpaceId(String spaceId) {
        return db.query(
                        """
                        SELECT id, max_msg_seq, max_event_seq, min_retained_event_seq
                        FROM chat_rooms
                        WHERE space_id = ?
                        """,
                        (rs, i) -> new RoomRow(
                                rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                        spaceId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private RoomRow lockRoomBySpaceId(String spaceId) {
        return db.query(
                        """
                        SELECT id, max_msg_seq, max_event_seq, min_retained_event_seq
                        FROM chat_rooms
                        WHERE space_id = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> new RoomRow(
                                rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                        spaceId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private RoomRow lockRoomById(String roomId) {
        return db.query(
                        """
                        SELECT id, max_msg_seq, max_event_seq, min_retained_event_seq
                        FROM chat_rooms
                        WHERE id = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> new RoomRow(
                                rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                        roomId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private RoomRow ensureRoomRow(String spaceId) {
        RoomRow room = findRoomBySpaceId(spaceId);
        if (room != null) {
            return room;
        }
        ensureMainRoom(spaceId);
        return findRoomBySpaceId(spaceId);
    }

    private RoomRow ensureAndLockRoom(String spaceId) {
        RoomRow room = lockRoomBySpaceId(spaceId);
        if (room != null) {
            return room;
        }
        ensureMainRoom(spaceId);
        return lockRoomBySpaceId(spaceId);
    }

    private ReadStateRow ensureReadState(
            String roomId,
            String spaceId,
            String userId,
            long membershipEpoch,
            long fallbackMaxSeq,
            boolean forUpdate) {
        String lockClause = forUpdate ? " FOR UPDATE" : "";
        String selectSql =
                """
                SELECT joined_baseline_seq, last_read_seq
                FROM chat_read_states
                WHERE room_id = ? AND user_id = ? AND membership_epoch = ?
                """
                        + lockClause;
        ReadStateRow existing = db.query(
                        selectSql,
                        (rs, i) -> new ReadStateRow(rs.getLong(1), rs.getLong(2)),
                        roomId,
                        userId,
                        membershipEpoch)
                .stream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        long baseline = db.query(
                        """
                        SELECT joined_baseline_seq
                        FROM space_members
                        WHERE space_id = ? AND user_id = ? AND membership_epoch = ?
                        """,
                        (rs, i) -> rs.getLong(1),
                        spaceId,
                        userId,
                        membershipEpoch)
                .stream()
                .findFirst()
                .orElse(fallbackMaxSeq);

        db.update(
                """
                INSERT INTO chat_read_states(
                    room_id, user_id, membership_epoch, joined_baseline_seq, last_read_seq
                )
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE room_id = room_id
                """,
                roomId,
                userId,
                membershipEpoch,
                baseline,
                baseline);

        return db.queryForObject(
                selectSql,
                (rs, i) -> new ReadStateRow(rs.getLong(1), rs.getLong(2)),
                roomId,
                userId,
                membershipEpoch);
    }

    private ChatMessage mapChatMessage(java.sql.ResultSet rs) throws java.sql.SQLException {
        String messageId = rs.getString(1);
        String roomId = rs.getString(2);
        long seq = rs.getLong(3);
        String senderId = rs.getString(4);
        String clientMsgId = rs.getString(5);
        MessageKind kind = MessageKind.valueOf(rs.getString(6));
        String bodyText = rs.getString(7);
        String refProjectId = rs.getString(8);
        String refTargetId = rs.getString(9);
        String refGrantType = rs.getString(10);
        String refPurpose = rs.getString(11);
        String replyToMsgId = rs.getString(12);
        Instant createdAt = rs.getTimestamp(13).toInstant();

        String rootMsgId = rs.getString(14);
        Discussion discussion = rootMsgId != null
                ? new Discussion(
                        DiscussionStatus.valueOf(rs.getString(15)),
                        rs.getString(16),
                        rs.getLong(17))
                : null;

        String cardDocumentId = rs.getString(18);
        String cardPersistedVersionId = rs.getString(19);
        String cardRelativeStart = rs.getString(20);
        String cardRelativeEnd = rs.getString(21);
        String cardFileId = rs.getString(22);
        String cardFileVersionId = rs.getString(23);
        Integer cardPageNumber = rs.getObject(24, Integer.class);
        String cardRectanglesJson = rs.getString(25);
        String cardLifecycleState = rs.getString(26);
        boolean forcePurged = "FORCE_PURGED".equals(cardLifecycleState);

        MessageContent content = switch (kind) {
            case TEXT -> new TextContent(bodyText);
            case COMMIT_REF -> new CommitRefContent(refProjectId, refTargetId, refPurpose);
            case WIP_REF -> new WipRefContent(refProjectId, refTargetId, refPurpose);
            case FILE_REF -> {
                GrantType grantType =
                        refGrantType != null
                                ? GrantType.valueOf(refGrantType)
                                : GrantType.SPACE_FILE;
                yield new FileRefContent(refTargetId, grantType, refPurpose);
            }
            case CODE_COMMENT_REF ->
                    new CodeCommentRefContent(refProjectId, refTargetId, refPurpose);
            case DOC_REF ->
                    new DocRefContent(
                            cardDocumentId,
                            refTargetId,
                            cardPersistedVersionId,
                            forcePurged ? null : cardRelativeStart,
                            forcePurged ? null : cardRelativeEnd,
                            refPurpose);
            case PDF_REF ->
                    new PdfRefContent(
                            cardFileId,
                            cardFileVersionId,
                            refTargetId,
                            cardPageNumber != null ? cardPageNumber : 1,
                            forcePurged ? null : parseRectanglesOrNull(cardRectanglesJson),
                            refPurpose);
            case SYSTEM -> new SystemContent(bodyText);
        };

        return new ChatMessage(
                roomId,
                messageId,
                seq,
                senderId,
                clientMsgId,
                createdAt,
                replyToMsgId,
                discussion,
                content);
    }

    static void validateSendMessage(SendMessageRequest request) {
        if (request == null || request.content() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        validateId(request.clientMsgId(), 64);
        if (request.replyToMsgId() != null) {
            validateId(request.replyToMsgId(), 64);
        }
        switch (request.content()) {
            case TextContent text -> {
                if (text.text() == null
                        || text.text().isEmpty()
                        || text.text().length() > 8000) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
            }
            case CommitRefContent commit -> {
                validateId(commit.projectId(), 64);
                if (commit.commitOid() == null || !GIT_OID.matcher(commit.commitOid()).matches()) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                validatePurpose(commit.purpose());
            }
            case WipRefContent wip -> {
                validateId(wip.projectId(), 64);
                validateId(wip.snapshotId(), 128);
                validatePurpose(wip.purpose());
            }
            case FileRefContent file -> {
                validateId(file.targetId(), 128);
                if (file.grantType() == null) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                validatePurpose(file.purpose());
            }
            case CodeCommentRefContent comment -> {
                validateId(comment.projectId(), 64);
                validateId(comment.commentId(), 128);
                validatePurpose(comment.purpose());
            }
            case DocRefContent doc -> {
                validateId(doc.documentId(), 64);
                validateId(doc.commentId(), 64);
                validateId(doc.persistedVersionId(), 64);
                if (doc.relativeStart() != null || doc.relativeEnd() != null) {
                    validateRelativePosition(doc.relativeStart());
                    validateRelativePosition(doc.relativeEnd());
                }
                validatePurpose(doc.purpose());
            }
            case PdfRefContent pdf -> {
                validateId(pdf.fileId(), 64);
                validateId(pdf.fileVersionId(), 64);
                validateId(pdf.commentId(), 64);
                if (pdf.pageNumber() == null || pdf.pageNumber() < 1) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                if (pdf.rectangles() != null) {
                    validatePdfRectangles(pdf.rectangles());
                }
                validatePurpose(pdf.purpose());
            }
        }
    }

    private DocumentCommentAnchorRow loadDocumentCommentAnchor(
            String spaceId, String documentId, String commentId) {
        return db.query(
                        """
                        SELECT id, document_id, version_id, anchor_json, discussion_thread_id
                        FROM document_comments
                        WHERE id = ?
                          AND space_id = ?
                          AND kind = 'DOC_REF'
                          AND document_id = ?
                        """,
                        (rs, i) -> {
                            JsonNode anchor = readTree(rs.getString(4));
                            String relativeStart =
                                    anchor.hasNonNull("relativeStart")
                                            ? anchor.get("relativeStart").asText()
                                            : null;
                            String relativeEnd =
                                    anchor.hasNonNull("relativeEnd")
                                            ? anchor.get("relativeEnd").asText()
                                            : null;
                            String quotedText =
                                    anchor.hasNonNull("quotedText")
                                            ? anchor.get("quotedText").asText()
                                            : null;
                            String contextBefore =
                                    anchor.hasNonNull("contextBefore")
                                            ? anchor.get("contextBefore").asText()
                                            : null;
                            String contextAfter =
                                    anchor.hasNonNull("contextAfter")
                                            ? anchor.get("contextAfter").asText()
                                            : null;
                            validateRelativePosition(relativeStart);
                            validateRelativePosition(relativeEnd);
                            return new DocumentCommentAnchorRow(
                                    rs.getString(1),
                                    MessageKind.DOC_REF,
                                    rs.getString(2),
                                    rs.getString(3),
                                    relativeStart,
                                    relativeEnd,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    quotedText,
                                    contextBefore,
                                    contextAfter,
                                    rs.getString(5));
                        },
                        commentId,
                        spaceId,
                        documentId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private DocumentCommentAnchorRow loadPdfCommentAnchor(
            String spaceId, String fileId, String fileVersionId, String commentId) {
        return db.query(
                        """
                        SELECT id, file_id, file_version_id, anchor_json, discussion_thread_id
                        FROM document_comments
                        WHERE id = ?
                          AND space_id = ?
                          AND kind = 'PDF_REF'
                          AND file_id = ?
                          AND file_version_id = ?
                        """,
                        (rs, i) -> {
                            JsonNode anchor = readTree(rs.getString(4));
                            int pageNumber = anchor.path("pageNumber").asInt(0);
                            if (pageNumber < 1) {
                                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                            }
                            List<PdfSelectionRect> rectangles =
                                    parseRectanglesNode(anchor.path("rectangles"));
                            validatePdfRectangles(rectangles);
                            String rectanglesJson = ChatSecrets.toJson(mapper, rectangles);
                            String quotedText =
                                    anchor.hasNonNull("quotedText")
                                            ? anchor.get("quotedText").asText()
                                            : null;
                            String contextBefore =
                                    anchor.hasNonNull("contextBefore")
                                            ? anchor.get("contextBefore").asText()
                                            : null;
                            String contextAfter =
                                    anchor.hasNonNull("contextAfter")
                                            ? anchor.get("contextAfter").asText()
                                            : null;
                            return new DocumentCommentAnchorRow(
                                    rs.getString(1),
                                    MessageKind.PDF_REF,
                                    null,
                                    null,
                                    null,
                                    null,
                                    rs.getString(2),
                                    rs.getString(3),
                                    pageNumber,
                                    rectangles,
                                    rectanglesJson,
                                    quotedText,
                                    contextBefore,
                                    contextAfter,
                                    rs.getString(5));
                        },
                        commentId,
                        spaceId,
                        fileId,
                        fileVersionId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireSourceStillActiveInTx(String spaceId, DocumentCommentAnchorRow anchor) {
        if (anchor.kind() == MessageKind.DOC_REF) {
            List<String> states =
                    db.queryForList(
                            "SELECT lifecycle_state FROM documents WHERE id = ? AND space_id = ?",
                            String.class,
                            anchor.documentId(),
                            spaceId);
            if (states.isEmpty() || !"ACTIVE".equals(states.getFirst())) {
                throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
            }
        } else if (anchor.kind() == MessageKind.PDF_REF) {
            List<String> states =
                    db.queryForList(
                            """
                            SELECT lifecycle_state
                            FROM files
                            WHERE id = ?
                              AND space_id = ?
                              AND scope = 'SPACE_FILE'
                              AND sha256_hex = ?
                            """,
                            String.class,
                            anchor.fileId(),
                            spaceId,
                            anchor.fileVersionId());
            if (states.isEmpty() || !"ACTIVE".equals(states.getFirst())) {
                throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
            }
        }
    }

    private void bindDiscussionThreadAnchor(
            String roomId, String rootMsgId, MessageKind kind, String commentId) {
        String sourceKind = kind == MessageKind.DOC_REF ? "DOC_COMMENT" : "PDF_COMMENT";
        db.update(
                """
                UPDATE discussion_threads
                SET source_kind = ?, source_anchor_id = ?
                WHERE root_msg_id = ?
                  AND room_id = ?
                  AND source_kind = 'CHAT_MESSAGE'
                """,
                sourceKind,
                commentId,
                rootMsgId,
                roomId);
    }

    private void insertDocPdfCardMetadata(
            String messageId,
            String roomId,
            String discussionThreadId,
            DocumentCommentAnchorRow anchor,
            Timestamp createdAt) {
        db.update(
                """
                INSERT INTO chat_doc_pdf_cards(
                    message_id, room_id, kind, comment_id, discussion_thread_id,
                    document_id, persisted_version_id, relative_start, relative_end,
                    file_id, file_version_id, page_number, rectangles_json,
                    quoted_text, context_before, context_after,
                    lifecycle_state, lifecycle_version, invalidated_at, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, NULL, ?)
                """,
                messageId,
                roomId,
                anchor.kind().name(),
                anchor.commentId(),
                discussionThreadId,
                anchor.documentId(),
                anchor.persistedVersionId(),
                anchor.relativeStart(),
                anchor.relativeEnd(),
                anchor.fileId(),
                anchor.fileVersionId(),
                anchor.pageNumber(),
                anchor.rectanglesJson(),
                anchor.quotedText(),
                anchor.contextBefore(),
                anchor.contextAfter(),
                createdAt);
    }

    private static void verifySubmittedDocAnchor(
            DocRefContent submitted, DocumentCommentAnchorRow authoritative) {
        if (!authoritative.persistedVersionId().equals(submitted.persistedVersionId())) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (submitted.relativeStart() != null
                && !authoritative.relativeStart().equals(submitted.relativeStart())) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (submitted.relativeEnd() != null
                && !authoritative.relativeEnd().equals(submitted.relativeEnd())) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static void verifySubmittedPdfAnchor(
            PdfRefContent submitted, DocumentCommentAnchorRow authoritative) {
        if (!authoritative.pageNumber().equals(submitted.pageNumber())) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (submitted.rectangles() != null) {
            List<PdfSelectionRect> expected = authoritative.rectangles();
            if (expected == null || submitted.rectangles().size() != expected.size()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            for (int idx = 0; idx < expected.size(); idx++) {
                PdfSelectionRect a = submitted.rectangles().get(idx);
                PdfSelectionRect b = expected.get(idx);
                if (Math.abs(a.left() - b.left()) > 1e-9
                        || Math.abs(a.top() - b.top()) > 1e-9
                        || Math.abs(a.right() - b.right()) > 1e-9
                        || Math.abs(a.bottom() - b.bottom()) > 1e-9) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
            }
        }
    }

    private List<PdfSelectionRect> parseRectanglesOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return parseRectanglesNode(readTree(json));
    }

    private static List<PdfSelectionRect> parseRectanglesNode(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<PdfSelectionRect> rects = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            rects.add(
                    new PdfSelectionRect(
                            item.path("left").asDouble(Double.NaN),
                            item.path("top").asDouble(Double.NaN),
                            item.path("right").asDouble(Double.NaN),
                            item.path("bottom").asDouble(Double.NaN)));
        }
        return List.copyOf(rects);
    }

    private static void validateRelativePosition(String encoded) {
        if (encoded == null || encoded.length() < 4 || encoded.length() > 1024) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length == 0 || decoded.length > 768) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        } catch (IllegalArgumentException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static void validatePdfRectangles(List<PdfSelectionRect> rectangles) {
        if (rectangles == null || rectangles.isEmpty() || rectangles.size() > 128) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        for (PdfSelectionRect rect : rectangles) {
            if (rect == null
                    || rect.left() == null
                    || rect.top() == null
                    || rect.right() == null
                    || rect.bottom() == null
                    || !Double.isFinite(rect.left())
                    || !Double.isFinite(rect.top())
                    || !Double.isFinite(rect.right())
                    || !Double.isFinite(rect.bottom())
                    || rect.left() < 0.0
                    || rect.top() < 0.0
                    || rect.right() > 1.0
                    || rect.bottom() > 1.0
                    || !(rect.left() < rect.right())
                    || !(rect.top() < rect.bottom())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }
    }

    private static String normalizeOptionalPurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return null;
        }
        if (purpose.length() > 4000) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return purpose;
    }

    private static String normalizeOptionalClientMsgId(String rawClientMsgId) {
        if (rawClientMsgId == null || rawClientMsgId.isBlank()) {
            return ChatSecrets.id();
        }
        return normalizeStrictClientMsgId(rawClientMsgId);
    }

    private static String normalizeStrictClientMsgId(String rawClientMsgId) {
        if (rawClientMsgId == null || rawClientMsgId.isBlank() || rawClientMsgId.length() > 160) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String trimmed = rawClientMsgId.strip();
        if (trimmed.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return trimmed.length() <= 64 ? trimmed : ChatSecrets.hash(trimmed);
    }

    private static String requireBlankCheckedStringField(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof String str) || str.isBlank()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return str;
    }

    private static String requireNonEmptyStringField(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (!(value instanceof String str) || str.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return str;
    }

    private static long requireLongField(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.strip());
            } catch (NumberFormatException ex) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }
        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
    }

    private static void validatePurpose(String purpose) {
        if (purpose != null && (purpose.isEmpty() || purpose.length() > 4000)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static void validateId(String value, int maxLen) {
        if (value == null || value.isBlank() || value.length() > maxLen) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static int resolvePageLimit(Integer limit) {
        int pageSize = limit != null ? limit : DEFAULT_PAGE_LIMIT;
        if (pageSize < 1 || pageSize > MAX_PAGE_LIMIT) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return pageSize;
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Corrupt chat event payload JSON", ex);
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    private void broadcastRoomEventSafely(String wsEnvelopeJson) {
        try {
            redis.convertAndSend(ROOM_EVENTS_CHANNEL, wsEnvelopeJson);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Failed to broadcast chat room event (cause={})",
                    ex.getClass().getSimpleName());
        }
    }
}
