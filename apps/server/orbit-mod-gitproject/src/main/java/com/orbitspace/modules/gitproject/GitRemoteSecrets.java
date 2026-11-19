package com.orbitspace.modules.gitproject;

import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import org.springframework.core.env.Environment;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Versioned AES-256-GCM keys are provisioned outside the database. Old keys remain readable during rotation. */
class GitRemoteSecrets {
    record Sealed(int keyVersion, byte[] ciphertext) {}
    private static final int NONCE_SIZE = 12;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    GitRemoteSecrets(Environment environment) {
        this.environment = environment;
    }

    int activeVersion() {
        int version = environment.getProperty("ORBIT_GIT_KEY_VERSION", Integer.class, 1);
        if (version < 1) throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        return version;
    }

    Sealed seal(String sourceId, String url, String token) {
        byte[] nonce = new byte[NONCE_SIZE];
        random.nextBytes(nonce);
        int version = activeVersion();
        byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, version, nonce, sourceId, url).doFinal(plaintext);
            return new Sealed(version, ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    String open(String sourceId, String url, int version, byte[] stored) {
        if (stored.length < NONCE_SIZE + 16) throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        try {
            byte[] plaintext = cipher(Cipher.DECRYPT_MODE, version, Arrays.copyOf(stored, NONCE_SIZE), sourceId, url)
                    .doFinal(stored, NONCE_SIZE, stored.length - NONCE_SIZE);
            try {
                return new String(plaintext, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (GeneralSecurityException ex) {
            throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        }
    }

    private Cipher cipher(int mode, int version, byte[] nonce, String sourceId, String url)
            throws GeneralSecurityException {
        String encoded = environment.getProperty("ORBIT_GIT_KEY_V" + version);
        if (encoded == null) throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        }
        try {
            if (key.length != 32) throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD((sourceId + "\0" + url + "\0" + version).getBytes(StandardCharsets.UTF_8));
            return cipher;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
