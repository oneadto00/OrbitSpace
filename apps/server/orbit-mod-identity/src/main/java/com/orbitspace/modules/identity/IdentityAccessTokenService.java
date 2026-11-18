package com.orbitspace.modules.identity;

import java.time.Instant;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import com.orbitspace.kernel.*;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;

final class IdentityAccessTokenService {
    static final Duration LIFETIME = Duration.ofMinutes(10);
    private final SecretKey key;
    private final JwtParser parser;

    IdentityAccessTokenService(@Value("${orbit.auth.jwt-key:${ORBIT_AUTH_JWT_KEY:}}") String base64Key) {
        // A local single-process server can rotate its ephemeral signing key on restart.
        // Set a shared Base64-encoded >=256-bit key for multiple replicas.
        key = base64Key.isEmpty() ? Jwts.SIG.HS256.key().build() : Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Key));
        parser = Jwts.parser().verifyWith(key).requireIssuer("orbitspace").require("use", "account").build();
    }

    String issue(AuthenticatedPrincipal account) {
        Instant now = Instant.now();
        return Jwts.builder().issuer("orbitspace").subject(account.userId()).claim("sid", account.sessionId())
                .claim("use", "account").issuedAt(Date.from(now)).expiration(Date.from(now.plus(LIFETIME)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    AuthenticatedPrincipal parse(String token) {
        try {
            var claims = parser.parseSignedClaims(token).getPayload();
            if (claims.getExpiration() == null || claims.getSubject() == null || claims.get("sid", String.class) == null)
                throw new DomainException(KernelErrorCode.AUTH_UNAUTHORIZED);
            return new AuthenticatedPrincipal(claims.getSubject(), claims.get("sid", String.class));
        } catch (JwtException | IllegalArgumentException err) { throw new DomainException(KernelErrorCode.AUTH_UNAUTHORIZED); }
    }
}
