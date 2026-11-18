package com.orbitspace.modules.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.orbitspace.kernel.DiscussionStatus;
import com.orbitspace.kernel.MessageKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Wire DTOs and WebSocket envelopes conforming to OpenAPI chat.yaml and WS JSON schemas. */
public final class ChatDtos {
    public static final long MAX_SAFE_SEQUENCE = 9_007_199_254_740_991L;

    private ChatDtos() {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TextContent.class, name = "TEXT"),
        @JsonSubTypes.Type(value = CommitRefContent.class, name = "COMMIT_REF"),
        @JsonSubTypes.Type(value = WipRefContent.class, name = "WIP_REF"),
        @JsonSubTypes.Type(value = SystemContent.class, name = "SYSTEM")
    })
    public sealed interface MessageContent permits ClientContent, SystemContent {
        MessageKind messageKind();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TextContent.class, name = "TEXT"),
        @JsonSubTypes.Type(value = CommitRefContent.class, name = "COMMIT_REF"),
        @JsonSubTypes.Type(value = WipRefContent.class, name = "WIP_REF")
    })
    public sealed interface ClientContent extends MessageContent
            permits TextContent, CommitRefContent, WipRefContent {}

    public record TextContent(@NotNull @Size(min = 1, max = 8000) String text)
            implements ClientContent {
        @Override
        public MessageKind messageKind() {
            return MessageKind.TEXT;
        }
    }

    public record CommitRefContent(
            @NotBlank @Size(min = 1, max = 160) String projectId,
            @NotBlank @Pattern(regexp = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$") String commitOid,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 4000) String purpose)
            implements ClientContent {
        @Override
        public MessageKind messageKind() {
            return MessageKind.COMMIT_REF;
        }
    }

    public record WipRefContent(
            @NotBlank @Size(min = 1, max = 160) String projectId,
            @NotBlank @Size(min = 1, max = 160) String snapshotId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 4000) String purpose)
            implements ClientContent {
        @Override
        public MessageKind messageKind() {
            return MessageKind.WIP_REF;
        }
    }

    public record SystemContent(@NotNull @Size(min = 1) String text) implements MessageContent {
        @Override
        public MessageKind messageKind() {
            return MessageKind.SYSTEM;
        }
    }

    public record SendMessageRequest(
            @NotBlank @Size(min = 1, max = 160) String clientMsgId,
            @Size(min = 1, max = 160) String replyToMsgId,
            @NotNull @Valid ClientContent content) {}

    public record Discussion(
            DiscussionStatus status, String conclusionMsgId, long version) {}

    public record ChatMessage(
            String roomId,
            String messageId,
            long seq,
            String senderId,
            String clientMsgId,
            Instant createdAt,
            String replyToMsgId,
            Discussion discussion,
            MessageContent content) {}

    public record MessageAck(
            String roomId, String clientMsgId, String messageId, long seq, long eventSeq) {}

    public record SyncBoundary(long maxSeq, long maxEventSeq, long minAfterEventSeq) {
        Map<String, Object> toMap() {
            return Map.of(
                    "maxSeq", maxSeq,
                    "maxEventSeq", maxEventSeq,
                    "minAfterEventSeq", minAfterEventSeq);
        }
    }

    public record RoomInfo(
            String spaceId,
            String roomId,
            long membershipEpoch,
            long joinedBaselineSeq,
            long lastReadSeq,
            SyncBoundary boundary) {}

    public record HistoryPage(
            List<ChatMessage> messages, Long nextBeforeSeq, SyncBoundary boundary) {}

    public enum RoomEventType {
        MESSAGE_CREATED,
        DISCUSSION_STATUS_CHANGED,
        RESOURCE_CARD_STATE_CHANGED
    }

    public record MessageCreatedPayload(ChatMessage message) {}

    public record DiscussionStatusChangedPayload(Discussion discussion) {}

    public record ResourceCardStateChangedPayload(boolean refreshRequired) {}

    public record RoomEvent(
            RoomEventType type,
            String roomId,
            long eventSeq,
            String targetId,
            long version,
            JsonNode payload) {}

    public record EventPage(
            List<RoomEvent> events,
            long nextAfterEventSeq,
            boolean hasMore,
            SyncBoundary boundary) {}

    public record AdvanceReadRequest(
            @NotNull @Min(0) @Max(MAX_SAFE_SEQUENCE) Long lastReadSeq) {}

    public record ReadCursorResponse(long lastReadSeq) {}

    public record ChangeDiscussionRequest(
            @NotNull DiscussionStatus status,
            @Size(min = 1, max = 160) String conclusionMsgId) {}

    public record ResolveCardsRequest(
            @NotEmpty @Size(min = 1, max = 100) List<@NotBlank @Size(min = 1, max = 160) String> messageIds) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CardView(String state, String label) {
        static CardView available(String label) {
            return new CardView("AVAILABLE", label);
        }

        static CardView forbidden() {
            return new CardView("FORBIDDEN", null);
        }

        static CardView expiredOrDeleted() {
            return new CardView("EXPIRED_OR_DELETED", null);
        }

        static CardView forcePurged() {
            return new CardView("FORCE_PURGED", null);
        }
    }

    public record ResourceCardResult(String messageId, CardView view) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WsEnvelope(String type, Object payload) {}
}
