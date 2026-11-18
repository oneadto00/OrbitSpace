package com.orbitspace.identity;

import static com.orbitspace.kernel.KernelErrorCode.INVALID_REQUEST;
import com.orbitspace.kernel.DomainException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

final class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXYZ23456789";
    static String id() { return UUID.randomUUID().toString(); }

    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String userCode() {
        var code = new StringBuilder(9);
        for (int i=0; i<8; i++) {
            if (i == 4) code.append('-');
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String password(String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 72
                || value.indexOf(0) >= 0) throw new DomainException(INVALID_REQUEST);
        return value;
    }
}
