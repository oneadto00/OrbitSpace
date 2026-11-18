package com.orbitspace.modules.space;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Cryptographic and identifier utilities scoped to the space module. */
final class SpaceSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();

    private SpaceSecrets() {}

    static String id() {
        return UUID.randomUUID().toString();
    }

    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String toJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize space payload", ex);
        }
    }

    static String digestJson(ObjectMapper mapper, Object value) {
        return hash(toJson(mapper, value));
    }

    static String requestId() {
        String id = MDC.get("requestId");
        return id != null && !id.isBlank() ? id : id();
    }
}
