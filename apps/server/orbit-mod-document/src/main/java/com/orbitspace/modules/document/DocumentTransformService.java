package com.orbitspace.modules.document;

import static com.orbitspace.modules.document.DocumentStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;

class DocumentTransformService {
    private final DocumentStore store;
    private final byte[] secret;
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    DocumentTransformService(DocumentStore store,
            @Value("${ORBIT_INTERNAL_HMAC_SECRET:local-only-internal-hmac-secret}") String secret,
            @Value("${orbit.document.transform-url:http://collab-hocuspocus:1234/internal/v1/"
                   + "document-transform}") URI endpoint) {
        this.store = store;
        this.secret = secret.strip().getBytes(StandardCharsets.UTF_8);
        this.endpoint = endpoint;
        if (this.secret.length == 0)
            throw new IllegalArgumentException("Missing internal HMAC secret");
    }
    String sign(String message) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
    JsonNode transform(String op, List<String> updates, Map<String, ?> options) {
        var body = new HashMap<String, Object>(options);
        body.put("operation", op);
        body.put("updates", updates);
        var payload = store.json(body).getBytes(StandardCharsets.UTF_8);
        check(payload.length <= 100 * 1024 * 1024);
        String nonce = id(), time = Long.toString(Instant.now().getEpochSecond());
        String signature = sign(String.join("\n", "POST", endpoint.getPath(), time, nonce, hash(payload)));
        var req = HttpRequest.newBuilder(endpoint)
                          .timeout(Duration.ofSeconds(20))
                          .header("Content-Type", "application/json")
                          .header("X-Orbit-Timestamp", time)
                          .header("X-Orbit-Nonce", nonce)
                          .header("X-Orbit-Signature", signature)
                          .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                          .build();
        try {
            var response = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (var in = response.body()) {
                if (response.statusCode() == 400 || response.statusCode() == 422)
                    throw error(com.orbitspace.kernel.KernelErrorCode.INVALID_REQUEST);
                if (response.statusCode() != 200)
                    throw new IOException("Collaboration transform unavailable");
                var result = in.readNBytes(100 * 1024 * 1024 + 1);
                check(result.length <= 100 * 1024 * 1024);
                return store.parse(new String(result, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted collaboration transform", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
