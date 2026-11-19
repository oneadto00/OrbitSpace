package com.orbitspace.modules.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.DocumentCollabPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/collab")
public class IntegrationCollabController {
    private final ObjectProvider<DocumentCollabPort> documents;
    private final IntegrationHmacSupport hmac;
    private final ObjectMapper json;

    public IntegrationCollabController(ObjectProvider<DocumentCollabPort> documents, IntegrationHmacSupport hmac, ObjectMapper json) {
        this.documents = documents;
        this.hmac = hmac;
        this.json = json;
    }

    private JsonNode body(HttpServletRequest request, String... fields) {
        byte[] bytes = hmac.verifyAndReadBody(request);
        try {
            JsonNode body = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
            if (body == null || !body.isObject()) throw invalid();
            var allowed = Set.of(fields);
            body.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid(); });
            return body;
        } catch (IOException e) {
            throw invalid();
        }
    }

    private static String value(JsonNode body, String key, int limit) {
        String value = body.path(key).textValue();
        if (value == null || value.isBlank() || value.length() > limit) throw invalid();
        return value;
    }

    private static DomainException invalid() { return new DomainException(KernelErrorCode.INVALID_REQUEST); }

    @PostMapping("/authenticate")
    public DocumentCollabPort.Session authenticate(HttpServletRequest request) {
        var body = body(request, "documentId", "ticket");
        return documents.getObject().authenticate(value(body,"documentId",160), value(body,"ticket",512));
    }

    @PostMapping("/load")
    public DocumentCollabPort.StatePage load(HttpServletRequest request) {
        var body = body(request, "documentId", "sessionId", "cursor");
        return documents.getObject().load(value(body,"documentId",160), value(body,"sessionId",160),
                body.has("cursor") ? value(body,"cursor",160) : null);
    }

    @PostMapping("/store-batch")
    public DocumentCollabPort.BatchReceipt store(HttpServletRequest request) {
        var body = body(request, "documentId","sessionId","batchId","expectedPersistedBatchSeq","update");
        var seq = body.path("expectedPersistedBatchSeq");
        if (!seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 0
                || seq.longValue() > 9007199254740991L) throw invalid();
        return documents.getObject().storeBatch(value(body,"documentId",160), value(body,"sessionId",160),
                value(body,"batchId",160), seq.longValue(), value(body,"update",8388608));
    }

    @PostMapping("/revoke-check")
    public DocumentCollabPort.Access check(HttpServletRequest request) {
        var body = body(request, "documentId","sessionId");
        return documents.getObject().revokeCheck(value(body,"documentId",160), value(body,"sessionId",160));
    }
}
