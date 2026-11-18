package com.orbitspace.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

public final class RefreshCsrfTokens implements CsrfTokenRepository {
    public static final String COOKIE = "orbit_refresh";
    public static final String HEADER = "X-CSRF-Token";

    public static String cookie(HttpServletRequest request) {
        String value = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName())) {
                    if (value != null) return null;
                    value = cookie.getValue();
                }
            }
        }
        return value;
    }

    // A domain-separated derivative of the 256-bit HttpOnly refresh secret.
    // Rotation changes both values. Browsers can read the derivative only through the same-origin GET.
    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        String raw = cookie(request);
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("orbitspace-csrf-v1/" + raw).getBytes(StandardCharsets.US_ASCII));
            return token(Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return token(UUID.randomUUID().toString());
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // Derived from the refresh cookie; no session storage.
    }

    private static CsrfToken token(String value) {
        return new DefaultCsrfToken(HEADER, "_csrf", value);
    }
}
