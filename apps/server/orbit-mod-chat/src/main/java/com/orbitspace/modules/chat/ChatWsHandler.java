package com.orbitspace.modules.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.ApiErrorResponse;
import com.orbitspace.kernel.AtomicTokenStorePort.TicketGrant;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.ErrorCode;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.modules.chat.ChatDtos.MessageAck;
import com.orbitspace.modules.chat.ChatDtos.SendMessageRequest;
import com.orbitspace.modules.chat.ChatDtos.WsEnvelope;
import com.orbitspace.security.WebSocketSecurity;

import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** WebSocket room handler for PING/PONG, post-commit SEND_MSG/MSG_ACK, and Redis room fan-out. */
public class ChatWsHandler extends TextWebSocketHandler implements MessageListener {
    private static final Logger LOG = LoggerFactory.getLogger(ChatWsHandler.class);

    private final ChatService chat;
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final ObjectProvider<Validator> validator;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>
            sessionsByRoom = new ConcurrentHashMap<>();

    public ChatWsHandler(
            ChatService chat, ObjectMapper mapper, ObjectProvider<Validator> validator) {
        this.chat = chat;
        this.mapper = mapper;
        this.strictMapper =
                mapper.copy()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                        .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        this.validator = validator;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        TicketGrant grant = WebSocketSecurity.grant(session);
        sessionsByRoom.compute(
                grant.roomId(),
                (roomId, existing) -> {
                    ConcurrentHashMap<String, WebSocketSession> map =
                            existing != null ? existing : new ConcurrentHashMap<>();
                    map.put(session.getId(), session);
                    return map;
                });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        TicketGrant grant = WebSocketSecurity.grant(session);
        String requestId = ChatSecrets.requestId();
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            processEnvelope(session, grant, message.getPayload(), requestId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LOG.debug(
                "Chat WebSocket transport error (sessionId={}, cause={})",
                session.getId(),
                exception.getClass().getSimpleName());
        if (!session.isOpen()) {
            detachSession(session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        detachSession(session);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String rawJson = new String(message.getBody(), StandardCharsets.UTF_8);
        String roomId;
        try {
            JsonNode envelope = mapper.readTree(rawJson);
            if (!envelope.isObject() || !"ROOM_EVENT".equals(envelope.path("type").asText())) {
                return;
            }
            roomId = envelope.path("payload").path("roomId").asText(null);
            if (roomId == null || roomId.isBlank()) {
                return;
            }
        } catch (JsonProcessingException ex) {
            LOG.warn(
                    "Ignoring malformed Redis room event (cause={})",
                    ex.getClass().getSimpleName());
            return;
        }

        Map<String, WebSocketSession> roomSessions = sessionsByRoom.get(roomId);
        if (roomSessions == null || roomSessions.isEmpty()) {
            return;
        }

        TextMessage outbound = new TextMessage(rawJson);
        for (WebSocketSession session : roomSessions.values()) {
            if (!session.isOpen()) {
                removeSession(roomId, session);
                continue;
            }
            try {
                // session is wrapped by WebSocketSecurity.guarded(), which reauthorizes outgoing writes.
                session.sendMessage(outbound);
            } catch (DomainException denied) {
                handleOutgoingRevocation(roomId, session, safeGrant(session), denied);
            } catch (IOException | IllegalStateException ex) {
                removeSession(roomId, session);
                LOG.debug(
                        "Dropping failed room WebSocket session (sessionId={}, cause={})",
                        session.getId(),
                        ex.getClass().getSimpleName());
            }
        }
    }

    private void processEnvelope(
            WebSocketSession session, TicketGrant grant, String rawPayload, String requestId) {
        try {
            JsonNode root = strictMapper.readTree(rawPayload);
            if (root == null
                    || !root.isObject()
                    || !root.has("type")
                    || !root.get("type").isTextual()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            String type = root.get("type").asText();
            switch (type) {
                case "PING" -> {
                    if (root.size() != 1) {
                        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                    }
                    sendEnvelope(session, grant, new WsEnvelope("PONG", null));
                }
                case "SEND_MSG" -> {
                    if (root.size() != 2
                            || !root.hasNonNull("payload")
                            || !root.get("payload").isObject()) {
                        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                    }
                    SendMessageRequest request = decodeSendMessage(root.get("payload"));
                    MessageAck ack =
                            chat.sendMessage(
                                    grant.userId(),
                                    grant.spaceId(),
                                    grant.membershipEpoch(),
                                    request);
                    sendEnvelope(session, grant, new WsEnvelope("MSG_ACK", ack));
                }
                default -> throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        } catch (DomainException ex) {
            LOG.warn(
                    "requestId={} code={} exception={}",
                    requestId,
                    ex.errorCode().code(),
                    ex.getClass().getSimpleName());
            sendError(session, grant, ex.errorCode(), requestId, ex.details());
        } catch (JsonProcessingException ex) {
            LOG.warn(
                    "requestId={} code={} exception={}",
                    requestId,
                    KernelErrorCode.INVALID_REQUEST.code(),
                    ex.getClass().getSimpleName());
            sendError(session, grant, KernelErrorCode.INVALID_REQUEST, requestId, Map.of());
        } catch (RuntimeException ex) {
            LOG.error(
                    "requestId={} code={} exception={}",
                    requestId,
                    KernelErrorCode.INTERNAL_ERROR.code(),
                    ex.getClass().getSimpleName());
            sendError(session, grant, KernelErrorCode.INTERNAL_ERROR, requestId, Map.of());
        }
    }

    private SendMessageRequest decodeSendMessage(JsonNode payloadNode)
            throws JsonProcessingException {
        SendMessageRequest request =
                strictMapper.treeToValue(payloadNode, SendMessageRequest.class);
        if (request == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        Validator beanValidator = validator.getIfAvailable();
        if (beanValidator != null && !beanValidator.validate(request).isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        ChatService.validateSendMessage(request);
        return request;
    }

    private void sendError(
            WebSocketSession session,
            TicketGrant grant,
            ErrorCode code,
            String requestId,
            Map<String, ?> details) {
        ApiErrorResponse error =
                new ApiErrorResponse(code.code(), code.defaultMessage(), requestId, details);
        sendEnvelope(session, grant, new WsEnvelope("ERROR", error));
    }

    private void sendEnvelope(WebSocketSession session, TicketGrant grant, WsEnvelope envelope) {
        String json = ChatSecrets.toJson(mapper, envelope);
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            } else {
                removeSession(grant.roomId(), session);
            }
        } catch (DomainException denied) {
            handleOutgoingRevocation(grant.roomId(), session, grant, denied);
        } catch (IOException | IllegalStateException ex) {
            removeSession(grant.roomId(), session);
            LOG.debug(
                    "Failed to send WebSocket envelope (sessionId={}, cause={})",
                    session.getId(),
                    ex.getClass().getSimpleName());
        }
    }

    private void handleOutgoingRevocation(
            String roomId, WebSocketSession session, TicketGrant grant, DomainException denied) {
        removeSession(roomId, session);
        KernelErrorCode closeCode =
                denied.errorCode() == KernelErrorCode.MEMBERSHIP_EPOCH_INVALID
                        ? KernelErrorCode.MEMBERSHIP_EPOCH_INVALID
                        : KernelErrorCode.AUTH_REVOKED;
        try {
            if (grant != null
                    && session instanceof WebSocketSessionDecorator decorator
                    && decorator.getDelegate().isOpen()) {
                Map<String, Object> payload =
                        Map.of(
                                "spaceId",
                                grant.spaceId(),
                                "membershipEpoch",
                                grant.membershipEpoch(),
                                "code",
                                closeCode.code());
                String closeFrame =
                        ChatSecrets.toJson(mapper, new WsEnvelope("AUTH_REVOKED_CLOSE", payload));
                decorator.getDelegate().sendMessage(new TextMessage(closeFrame));
            }
        } catch (IOException | RuntimeException ignored) {
            // Best-effort advisory frame before enforcing socket close.
        } finally {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                }
            } catch (IOException ignored) {
                // Already disconnected.
            }
        }
    }

    private void detachSession(WebSocketSession session) {
        TicketGrant grant = safeGrant(session);
        if (grant != null) {
            removeSession(grant.roomId(), session);
        } else {
            sessionsByRoom.forEach((roomId, ignored) -> removeSession(roomId, session));
        }
    }

    private void removeSession(String roomId, WebSocketSession session) {
        sessionsByRoom.computeIfPresent(
                roomId,
                (key, map) -> {
                    map.remove(session.getId(), session);
                    return map.isEmpty() ? null : map;
                });
    }

    private static TicketGrant safeGrant(WebSocketSession session) {
        try {
            return WebSocketSecurity.grant(session);
        } catch (DomainException ex) {
            return null;
        }
    }
}
