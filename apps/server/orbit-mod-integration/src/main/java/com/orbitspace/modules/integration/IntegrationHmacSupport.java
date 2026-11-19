package com.orbitspace.modules.integration;

import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies internal worker HMAC-SHA256 signatures, timestamp freshness windows, and single-use
 * nonces stored atomically in {@code integration_callback_nonces}.
 */
public class IntegrationHmacSupport {
    static final String HEADER_TIMESTAMP = "X-Orbit-Timestamp";
    static final String HEADER_NONCE = "X-Orbit-Nonce";
    static final String HEADER_SIGNATURE = "X-Orbit-Signature";

    private final JdbcTemplate db;
    private final TransactionTemplate requiresNewTx;
    private final byte[] secretBytes;
    private final long allowedSkewSeconds;
    private final long maxBodyBytes;

    public IntegrationHmacSupport(
            JdbcTemplate db,
            PlatformTransactionManager txManager,
            @Value("${orbit.integration.hmac-secret:${ORBIT_INTERNAL_HMAC_SECRET:${ORBIT_INTEGRATION_HMAC_SECRET:${ORBIT_HMAC_SECRET:local-only-internal-hmac-secret}}}}")
                    String hmacSecret,
            @Value("${orbit.integration.hmac-skew-seconds:300}") long allowedSkewSeconds,
            @Value("${orbit.integration.max-body-bytes:16777216}") long maxBodyBytes) {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalStateException("Internal integration HMAC secret must be configured");
        }
        this.db = db;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.secretBytes = hmacSecret.strip().getBytes(StandardCharsets.UTF_8);
        this.allowedSkewSeconds = Math.max(30L, allowedSkewSeconds);
        this.maxBodyBytes = Math.max(1024L, maxBodyBytes);
    }

    public byte[] verifyAndReadBody(HttpServletRequest request) {
        if (request.getHeader("Authorization") != null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        String rawTs = request.getHeader(HEADER_TIMESTAMP);
        String rawNonce = request.getHeader(HEADER_NONCE);
        String rawSignature = request.getHeader(HEADER_SIGNATURE);

        if (rawTs == null
                || rawTs.isBlank()
                || rawNonce == null
                || rawNonce.isBlank()
                || rawSignature == null
                || rawSignature.isBlank()) {
            throw new DomainException(KernelErrorCode.AUTH_UNAUTHORIZED);
        }

        long timestampEpoch;
        try {
            timestampEpoch = Long.parseLong(rawTs.strip());
        } catch (NumberFormatException ex) {
            throw new DomainException(KernelErrorCode.AUTH_UNAUTHORIZED);
        }

        long nowEpoch = Instant.now().getEpochSecond();
        if (timestampEpoch < nowEpoch - allowedSkewSeconds || timestampEpoch > nowEpoch + allowedSkewSeconds) {
            throw new DomainException(KernelErrorCode.AUTH_UNAUTHORIZED);
        }

        String nonce = rawNonce.strip();
        if (nonce.length() < 8
                || nonce.length() > 128
                || !nonce.matches("[A-Za-z0-9._:-]+")) {
            throw new DomainException(KernelErrorCode.AUTH_UNAUTHORIZED);
        }

        String signatureHex = rawSignature.strip().toLowerCase(java.util.Locale.ROOT);
        if (!signatureHex.matches("[0-9a-f]{64}")) {
            throw new DomainException(KernelErrorCode.AUTH_UNAUTHORIZED);
        }

        byte[] bodyBytes = readBounded(request);
        String bodySha256Hex = sha256Hex(bodyBytes);
        String method = request.getMethod().toUpperCase(java.util.Locale.ROOT);
        String path = request.getRequestURI();

        String canonical =
                method + "\n" + path + "\n" + timestampEpoch + "\n" + nonce + "\n" + bodySha256Hex;
        byte[] expectedMac = hmacSha256(canonical);
        byte[] providedMac = HexFormat.of().parseHex(signatureHex);

        if (!MessageDigest.isEqual(expectedMac, providedMac)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }

        recordNonceOrRejectReplay(nonce, method, path, timestampEpoch, bodySha256Hex);
        return bodyBytes;
    }

    private void recordNonceOrRejectReplay(
            String nonce, String method, String path, long timestampEpoch, String bodyDigest) {
        try {
            requiresNewTx.executeWithoutResult(
                    status ->
                            db.update(
                                    """
                                    INSERT INTO integration_callback_nonces(
                                        nonce, method, request_path, timestamp_epoch, request_digest
                                    )
                                    VALUES (?, ?, ?, ?, ?)
                                    """,
                                    nonce,
                                    method,
                                    path.length() <= 255 ? path : path.substring(0, 255),
                                    timestampEpoch,
                                    bodyDigest));
        } catch (DuplicateKeyException ex) {
            throw new DomainException(
                    KernelErrorCode.AUTH_FORBIDDEN, Map.of("reason", "REPLAY_DETECTED"));
        }
    }

    private byte[] readBounded(HttpServletRequest request) {
        try (InputStream in = request.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            long total = 0L;
            for (int read; (read = in.read(buf)) != -1; ) {
                total += read;
                if (total > maxBodyBytes) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private byte[] hmacSha256(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("Cannot compute internal HMAC-SHA256", ex);
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
