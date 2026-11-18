package com.orbitspace.modules.identity;

import static com.orbitspace.kernel.KernelErrorCode.*;
import com.orbitspace.kernel.*;
import com.orbitspace.security.BrowserSessionPort;
import com.orbitspace.security.WebSocketSecurity.SessionRevoked;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.sql.Timestamp;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class SessionService implements BrowserSessionPort {
    static final Duration LIFETIME = Duration.ofDays(30);
    public record AccessSession(String accessToken, String tokenType, long expiresIn, String sessionId, String userId) {}
    record Login(AccessSession access, String refresh, long maxAge) {}
    record SessionInfo(String sessionId, String deviceLabel, Instant createdAt, Instant lastUsedAt, boolean current) {}
    private record Family(String userId, String id, String currentHash, Instant expiresAt, boolean revoked) {}

    private final JdbcTemplate db;
    private final IdentityAccessTokenService tokens;
    private final ApplicationEventPublisher events;

    SessionService(JdbcTemplate db, IdentityAccessTokenService tokens, ApplicationEventPublisher events) {
        this.db = db;
        this.tokens = tokens;
        this.events = events;
    }

    @Transactional
    public Login start(String userId, String label) {
        String id = IdentitySecrets.id();
        String refresh = IdentitySecrets.token();
        String hash = IdentitySecrets.hash(refresh);
        Instant until = Instant.now().plus(LIFETIME);
        db.update("INSERT INTO auth_sessions(id,user_id,refresh_hash,device_label,expires_at) VALUES (?,?,?,?,?)",
                id, userId, hash, label, Timestamp.from(until));
        recordToken(hash, id);
        return response(userId, id, refresh, until);
    }

    private Login response(String userId, String id, String refresh, Instant expires) {
        String jwt = tokens.issue(new AuthenticatedPrincipal(userId, id));
        return new Login(new AccessSession(jwt, "Bearer", IdentityAccessTokenService.LIFETIME.toSeconds(), id, userId),
                refresh, Math.max(1, Duration.between(Instant.now(), expires).toSeconds()));
    }

    private void recordToken(String hash, String sessionId) {
        db.update("INSERT INTO auth_refresh_history(token_hash,session_id) VALUES (?,?)", hash, sessionId);
    }

    private Family family(String raw, boolean revokeOnReplay) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw new DomainException(AUTH_UNAUTHORIZED);
        String hash = IdentitySecrets.hash(raw);
        String id = db.query("SELECT session_id FROM auth_refresh_history WHERE token_hash=?",
                (rs,i) -> rs.getString(1), hash).stream().findFirst().orElseThrow(() -> new DomainException(AUTH_UNAUTHORIZED));
        var family = db.queryForObject("""
                SELECT user_id,id,refresh_hash,expires_at,revoked_at FROM auth_sessions WHERE id=? FOR UPDATE
                """, (rs,i) -> new Family(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) != null), id);
        if (family.revoked() || !family.expiresAt().isAfter(Instant.now())) throw new DomainException(AUTH_REVOKED);
        if (!hash.equals(family.currentHash())) {
            if (revokeOnReplay) revoke(family.userId(), id);
            throw new DomainException(AUTH_REVOKED);
        }
        return family;
    }

    // A replay is a security state change: persist the family revocation even when returning a rejection.
    @Override @Transactional(noRollbackFor = DomainException.class)
    public AuthenticatedPrincipal fromRefresh(String raw, boolean revokeOnReplay) {
        var family = family(raw, revokeOnReplay);
        return new AuthenticatedPrincipal(family.userId(), family.id());
    }

    @Transactional(noRollbackFor = DomainException.class)
    public Login rotate(String raw) {
        var family = family(raw, true);
        String refresh = IdentitySecrets.token();
        String hash = IdentitySecrets.hash(refresh);
        recordToken(hash, family.id());
        db.update("UPDATE auth_sessions SET refresh_hash=?,last_used_at=CURRENT_TIMESTAMP(6) WHERE id=?", hash, family.id());
        return response(family.userId(), family.id(), refresh, family.expiresAt());
    }

    @Override public void requireActive(AuthenticatedPrincipal account) {
        boolean active = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL
                AND expires_at>CURRENT_TIMESTAMP(6))
                """, Boolean.class, account.sessionId(), account.userId()));
        if (!active) throw new DomainException(AUTH_REVOKED);
    }

    public List<SessionInfo> list(AuthenticatedPrincipal account) {
        return db.query("""
                SELECT id,device_label,created_at,last_used_at FROM auth_sessions
                WHERE user_id=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP(6)
                ORDER BY last_used_at DESC,id
                """, (rs,i) -> new SessionInfo(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4).toInstant(), account.sessionId().equals(rs.getString(1))), account.userId());
    }

    @Transactional
    public void revoke(String user, String session) {
        var existing = db.query("SELECT revoked_at IS NULL FROM auth_sessions WHERE id=? AND user_id=? FOR UPDATE",
                (rs,i) -> rs.getBoolean(1), session, user).stream().findFirst();
        if (existing.isEmpty()) throw new DomainException(RESOURCE_NOT_FOUND);
        if (!existing.get()) return;
        db.update("UPDATE auth_sessions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE id=?", session);
        events.publishEvent(new SessionRevoked(user, session));
    }

    @Transactional
    public void revokeAll(String user) {
        db.update("UPDATE auth_sessions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND revoked_at IS NULL", user);
        events.publishEvent(new SessionRevoked(user,null));
    }
}
