package com.orbitspace.security;

import com.orbitspace.kernel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Room handlers register this handshake interceptor and wrap their handler with guarded(). */
public class WebSocketSecurity implements HandshakeInterceptor, MembershipRevocationHook {
    private static final String GRANT = WebSocketSecurity.class.getName() + ".grant";
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final Duration TTL = Duration.ofSeconds(30);
    private final AtomicTokenStorePort tokens;
    private final ObjectProvider<BrowserSessionPort> sessions;
    private final ObjectProvider<SpaceRoomPort> rooms;
    private final SpaceAccess access;
    private final RateLimitPort limits;
    private final String origin;
    private final ObjectMapper json;
    private record Connection(AtomicTokenStorePort.TicketGrant grant, WebSocketSession writer, WebSocketSession handlerSession) {}
    private final Map<WebSocketSession, Connection> active = new ConcurrentHashMap<>();

    public WebSocketSecurity(AtomicTokenStorePort tokens, ObjectProvider<BrowserSessionPort> sessions,
            ObjectProvider<SpaceRoomPort> rooms, SpaceAccess access, RateLimitPort limits, ObjectMapper json,
            @Value("${orbit.public-origin}") String origin) {
        this.json = json;
        this.tokens = tokens;
        this.sessions = sessions;
        this.rooms = rooms;
        this.access = access;
        this.limits = limits;
        this.origin = origin;
    }

    public record Ticket(String ticket, Instant expiresAt, String wsPath) {}

    public Ticket issue(AuthenticatedPrincipal account, String spaceId, long membershipEpoch) {
        sessions.getObject().requireActive(account);
        access.member(account.userId(), spaceId, membershipEpoch, SpaceRole.VIEWER);
        var room = rooms.getIfAvailable();
        if (room == null) throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        String roomId = room.currentPosition(spaceId).roomId();
        var grant = new AtomicTokenStorePort.TicketGrant(account.userId(), account.sessionId(), spaceId, roomId, membershipEpoch);
        return new Ticket(tokens.issueTicket(grant, TTL), Instant.now().plus(TTL), "/ws/chat");
    }

    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String,Object> attributes) {
        String query = request.getURI().getRawQuery();
        if (!origin.equals(request.getHeaders().getOrigin()) || !"/ws/chat".equals(request.getURI().getPath())
                || query == null || !query.matches("ticket=[A-Za-z0-9_-]{20,256}")) {
            response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
            return false;
        }
        var grant = tokens.consumeTicket(query.substring(7)).orElse(null);
        if (grant == null) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            check(grant);
        } catch (DomainException rejected) {
            response.setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(GRANT, grant);
        return true;
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception ex) {}

    private void check(AtomicTokenStorePort.TicketGrant grant) {
        sessions.getObject().requireActive(new AuthenticatedPrincipal(grant.userId(), grant.sessionId()));
        access.member(grant.userId(), grant.spaceId(), grant.membershipEpoch(), SpaceRole.VIEWER);
        var room = rooms.getIfAvailable();
        if (room == null || !grant.roomId().equals(room.currentPosition(grant.spaceId()).roomId()))
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
    }

    public static AtomicTokenStorePort.TicketGrant grant(WebSocketSession session) {
        var value = session.getAttributes().get(GRANT);
        if (value instanceof AtomicTokenStorePort.TicketGrant grant) return grant;
        throw new DomainException(KernelErrorCode.WS_TICKET_INVALID);
    }

    public WebSocketHandler guarded(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.setTextMessageSizeLimit(MAX_FRAME_BYTES);
                session.setBinaryMessageSizeLimit(MAX_FRAME_BYTES);
                var grant = grant(session);
                var writer = new ConcurrentWebSocketSessionDecorator(session, 5_000, MAX_FRAME_BYTES * 4);
                // A chat broadcast can reach a socket without an inbound frame. Reauthorize outgoing data too.
                var guarded = new WebSocketSessionDecorator(writer) {
                    @Override public void sendMessage(WebSocketMessage<?> message) throws IOException {
                        check(grant);
                        super.sendMessage(message);
                    }
                };
                active.put(session, new Connection(grant, writer, guarded));
                try {
                    check(grant(session));
                    super.afterConnectionEstablished(guarded);
                } catch (Exception ex) {
                    active.remove(session);
                    session.close(CloseStatus.POLICY_VIOLATION);
                    throw ex;
                }
            }

            @Override public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
                var grant = grant(session);
                if (message.getPayloadLength() > MAX_FRAME_BYTES || !message.isLast()) {
                    session.close(CloseStatus.TOO_BIG_TO_PROCESS);
                    return;
                }
                try {
                    check(grant);
                    if (!limits.tryAcquire("ws:frame:" + grant.sessionId(), 60, Duration.ofSeconds(10)).allowed()) {
                        session.close(CloseStatus.POLICY_VIOLATION);
                        return;
                    }
                } catch (DomainException denied) {
                    revokeSocket(session, denied.errorCode() == KernelErrorCode.MEMBERSHIP_EPOCH_INVALID
                            ? KernelErrorCode.MEMBERSHIP_EPOCH_INVALID : KernelErrorCode.AUTH_REVOKED);
                    return;
                }
                var connection = active.get(session);
                if (connection != null) super.handleMessage(connection.handlerSession(), message);
            }

            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                var connection = active.remove(session);
                super.afterConnectionClosed(connection == null ? session : connection.handlerSession(), status);
            }
        };
    }

    public record SessionRevoked(String userId, String sessionId) {}
    @TransactionalEventListener
    public void onSessionRevoked(SessionRevoked event) {
        active.forEach((socket, connection) -> {
            var grant = connection.grant();
            if (event.userId().equals(grant.userId()) && (event.sessionId() == null
                    || event.sessionId().equals(grant.sessionId()))) revokeSocket(socket, KernelErrorCode.AUTH_REVOKED);
        });
    }

    @Override public void onRevoked(Revocation revocation) {
        var synchronization = new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCommit() {
                active.forEach((socket, connection) -> {
                    var grant = connection.grant();
                    if (grant.userId().equals(revocation.userId()) && grant.spaceId().equals(revocation.spaceId())
                            && grant.membershipEpoch() == revocation.endedMembershipEpoch()) revokeSocket(socket, KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
                });
            }
        };
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(synchronization);
    }

    private void revokeSocket(WebSocketSession socket, KernelErrorCode code) {
        var connection = active.get(socket);
        if (connection == null) return;
        try {
            var payload = Map.of("spaceId", connection.grant().spaceId(), "membershipEpoch",
                    connection.grant().membershipEpoch(), "code", code.code());
            var envelope = Map.of("type", "AUTH_REVOKED_CLOSE", "payload", payload);
            connection.writer().sendMessage(new TextMessage(json.writeValueAsString(envelope)));
        } catch (IOException | RuntimeException ignored) {
            // A disconnected or slow client may miss the advisory frame. Still enforce the close.
        } finally {
            try { socket.close(CloseStatus.POLICY_VIOLATION); }
            catch (IOException ignored) { /* Already disconnected. */ }
        }
    }
}
