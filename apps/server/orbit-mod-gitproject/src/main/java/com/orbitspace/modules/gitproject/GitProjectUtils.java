package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.orbitspace.kernel.BaseKind;
import com.orbitspace.kernel.DataLevel;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.modules.gitproject.GitProjectDtos.CaptureState;
import com.orbitspace.modules.gitproject.GitProjectDtos.CommitMetadataDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ContentManifestEntryDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.PushStateRequest;
import com.orbitspace.modules.gitproject.GitProjectDtos.RefObservationDto;

import org.slf4j.MDC;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Shared validation, RFC 8785 canonicalization, hashing, and JSON utilities for gitproject. */
final class GitProjectUtils {
    static final long MAX_PATCH_BYTES = 8L * 1024 * 1024;
    static final long MAX_SAFE_INT = 9_007_199_254_740_991L;
    static final Instant MIN_MYSQL_TIMESTAMP = Instant.parse("1970-01-01T00:00:01Z");
    static final Instant MAX_MYSQL_TIMESTAMP = Instant.parse("2038-01-19T03:14:07Z");

    private static final Pattern SHA1_HEX = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private GitProjectUtils() {}

    static String newId() {
        return UUID.randomUUID().toString();
    }

    static String requestId() {
        String id = MDC.get("requestId");
        return id != null && !id.isBlank() ? id : newId();
    }

    static String shortOid(String oid) {
        if (oid == null) return "";
        return oid.length() <= 8 ? oid : oid.substring(0, 8);
    }

    static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static String normalizeKey64(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > 160) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String trimmed = rawKey.strip();
        if (trimmed.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return trimmed.length() <= 64 ? trimmed : sha256Hex(trimmed);
    }

    static String normalizeInstanceId(String rawInstanceId) {
        if (rawInstanceId == null || rawInstanceId.isBlank() || rawInstanceId.length() > 160) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String trimmed = rawInstanceId.strip();
        if (trimmed.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return trimmed.length() <= 128 ? trimmed : sha256Hex(trimmed);
    }

    static boolean isValidSha256(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }

    static boolean isValidOid(String objectFormat, String oid) {
        if (oid == null) return false;
        if ("sha1".equals(objectFormat)) return SHA1_HEX.matcher(oid).matches();
        if ("sha256".equals(objectFormat)) return SHA256_HEX.matcher(oid).matches();
        return false;
    }

    static boolean isAnyValidOid(String oid) {
        return oid != null
                && (SHA1_HEX.matcher(oid).matches() || SHA256_HEX.matcher(oid).matches());
    }

    static Timestamp toMysqlTimestamp(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }

    static Instant requireMysqlInstant(Instant instant) {
        if (instant == null
                || instant.isBefore(MIN_MYSQL_TIMESTAMP)
                || instant.isAfter(MAX_MYSQL_TIMESTAMP)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    static ObjectMapper strictMapper(ObjectMapper base) {
        ObjectMapper copy =
                base.copy()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
                        .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
        copy.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        copy.coercionConfigFor(LogicalType.Enum)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return copy;
    }

    static String toJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize gitproject payload", ex);
        }
    }

    static <T> T fromJson(ObjectMapper mapper, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Cannot deserialize stored gitproject payload for " + type.getSimpleName(), ex);
        }
    }

    static <T> T parseStrict(ObjectMapper strictMapper, String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        try {
            JsonNode tree = strictMapper.readTree(rawBody);
            if (tree == null || !tree.isObject()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            return strictMapper.treeToValue(tree, type);
        } catch (JsonProcessingException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    /**
     * Exact RFC 8785 / ECMAScript canonical JSON serialization matching CLI apps/cli/src/privacy/policy.ts.
     */
    static String canonicalizeRfc8785(JsonNode node) {
        StringBuilder sb = new StringBuilder(256);
        appendCanonical(sb, node);
        return sb.toString();
    }

    private static void appendCanonical(StringBuilder sb, JsonNode node) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.asBoolean() ? "true" : "false");
            return;
        }
        if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                sb.append(node.bigIntegerValue().toString());
            } else {
                double d = node.asDouble();
                if (!Double.isFinite(d)) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                if (d == 0.0) {
                    sb.append('0');
                } else if (d == Math.rint(d)
                        && d >= -MAX_SAFE_INT
                        && d <= MAX_SAFE_INT) {
                    sb.append((long) d);
                } else {
                    sb.append(BigDecimal.valueOf(d).stripTrailingZeros().toString());
                }
            }
            return;
        }
        if (node.isTextual()) {
            appendJsonString(sb, node.asText());
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) sb.append(',');
                appendCanonical(sb, node.get(i));
            }
            sb.append(']');
            return;
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                String key = keys.get(i);
                appendJsonString(sb, key);
                sb.append(':');
                appendCanonical(sb, node.get(key));
            }
            sb.append('}');
            return;
        }
        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    record VerifiedPushState(PushStateRequest payload, String canonicalJson) {}

    static VerifiedPushState parseAndVerifyPushState(
            ObjectMapper strictMapper, String rawBody, boolean expectClean) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        JsonNode root;
        PushStateRequest req;
        try {
            root = strictMapper.readTree(rawBody);
            if (root == null || !root.isObject()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            verifyRequiredJsonStructure((ObjectNode) root);
            req = strictMapper.treeToValue(root, PushStateRequest.class);
        } catch (JsonProcessingException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        String declaredDigest = root.get("payloadDigest").asText();
        if (!isValidSha256(declaredDigest)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        ObjectNode digestInput = ((ObjectNode) root).deepCopy();
        digestInput.remove("payloadDigest");
        String computedDigest = sha256Hex(canonicalizeRfc8785(digestInput));
        if (!computedDigest.equals(declaredDigest)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        validatePushStateDomain(req, expectClean);
        return new VerifiedPushState(req, canonicalizeRfc8785(root));
    }

    private static void verifyRequiredJsonStructure(ObjectNode root) {
        requireIntegral(root, "protocolVersion");
        requireIntegral(root, "membershipEpoch");
        requireIntegral(root, "worktreeEpoch");
        requireIntegral(root, "captureSeq");
        requireText(root, "payloadDigest");

        JsonNode head = root.get("head");
        if (head == null || !head.isObject() || !head.has("baseKind") || !head.has("baseCommitOid") || !head.has("branch")) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        JsonNode scope = root.get("scopeDeclaration");
        if (scope == null || !scope.isObject()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        requireIntegral((ObjectNode) scope, "hiddenItemCount");
        requireIntegral((ObjectNode) scope, "excludedItemCount");

        JsonNode content = root.get("content");
        if (content == null || !content.isObject() || !content.hasNonNull("dataLevel")) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (content.has("patch")) {
            JsonNode patch = content.get("patch");
            if (patch == null || !patch.isObject()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            requireIntegral((ObjectNode) patch, "sizeBytes");
        }
        if (root.has("note") && (!root.get("note").isTextual())) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        JsonNode commits = root.get("commits");
        if (commits != null && commits.isArray()) {
            for (JsonNode c : commits) {
                if (c.isObject() && c.has("authorEmail") && !c.get("authorEmail").isTextual()) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
            }
        }
        JsonNode manifest = root.get("contentManifest");
        if (manifest != null && manifest.isArray()) {
            for (JsonNode m : manifest) {
                if (m.isObject() && m.has("sha256") && !m.get("sha256").isTextual()) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
            }
        }
    }

    private static void requireIntegral(ObjectNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isIntegralNumber()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static void requireText(ObjectNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isTextual()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static void validatePushStateDomain(PushStateRequest req, boolean expectClean) {
        if (req.protocolVersion() != 1) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        requireId(req.spaceId());
        requireId(req.projectId());
        requireId(req.deviceId());
        requireId(req.worktreeId());
        requireId(req.idempotencyKey());
        requirePositiveSeq(req.membershipEpoch());
        requirePositiveSeq(req.worktreeEpoch());
        requirePositiveSeq(req.captureSeq());
        requireMysqlInstant(req.capturedAt());

        String fmt = req.objectFormat();
        if (!"sha1".equals(fmt) && !"sha256".equals(fmt)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        if (req.head() == null || req.head().baseKind() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (req.head().baseKind() == BaseKind.COMMIT) {
            if (!isValidOid(fmt, req.head().baseCommitOid())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        } else if (req.head().baseCommitOid() != null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (req.head().branch() != null
                && (req.head().branch().isEmpty() || req.head().branch().length() > 300)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        if (req.commits() == null || req.refs() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        for (CommitMetadataDto c : req.commits()) {
            if (c == null || !isValidOid(fmt, c.oid()) || c.parents() == null || c.subject() == null) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            if (c.subject().length() > 1000
                    || c.authorName() == null
                    || c.authorName().isEmpty()
                    || c.authorName().length() > 200) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            if (c.authorEmail() != null
                    && (c.authorEmail().isEmpty() || c.authorEmail().length() > 254)) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            requireMysqlInstant(c.authoredAt());
            Set<String> parentSet = new HashSet<>();
            for (String p : c.parents()) {
                if (!isValidOid(fmt, p) || !parentSet.add(p)) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
            }
        }
        for (RefObservationDto r : req.refs()) {
            if (r == null
                    || r.name() == null
                    || r.name().isEmpty()
                    || r.name().length() > 300
                    || !isValidOid(fmt, r.targetOid())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            requireMysqlInstant(r.observedAt());
        }

        if (req.scopeDeclaration() == null || req.scopeDeclaration().includedPaths() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        requireSeq(req.scopeDeclaration().hiddenItemCount());
        requireSeq(req.scopeDeclaration().excludedItemCount());

        Set<String> includedPaths = new HashSet<>();
        for (String path : req.scopeDeclaration().includedPaths()) {
            requireSafeRelativePath(path);
            if (!includedPaths.add(path)) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }

        if (req.stagingSummary() == null
                || req.stagingSummary().staged() == null
                || req.stagingSummary().unstaged() == null
                || req.stagingSummary().untracked() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        for (String path : req.stagingSummary().staged()) {
            requireScopedPath(path, includedPaths);
        }
        for (String path : req.stagingSummary().unstaged()) {
            requireScopedPath(path, includedPaths);
        }
        for (String path : req.stagingSummary().untracked()) {
            requireScopedPath(path, includedPaths);
        }

        if (req.contentManifest() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        for (ContentManifestEntryDto entry : req.contentManifest()) {
            if (entry == null || entry.status() == null) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            requireScopedPath(entry.path(), includedPaths);
            if (entry.sha256() != null && !isValidSha256(entry.sha256())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }

        if (req.content() == null || req.content().dataLevel() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (req.content().dataLevel() == DataLevel.METADATA_ONLY) {
            if (req.content().patch() != null) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        } else if (req.content().dataLevel() == DataLevel.PATCH_ONLY) {
            if (req.content().patch() == null
                    || !isValidSha256(req.content().patch().sha256())
                    || req.content().patch().sizeBytes() < 0
                    || req.content().patch().sizeBytes() > MAX_PATCH_BYTES) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        } else {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        if (req.captureState() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (expectClean) {
            if (req.captureState() != CaptureState.CLEAN
                    || req.content().dataLevel() != DataLevel.METADATA_ONLY
                    || !req.stagingSummary().staged().isEmpty()
                    || !req.stagingSummary().unstaged().isEmpty()
                    || !req.stagingSummary().untracked().isEmpty()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        } else if (req.captureState() == CaptureState.CLEAN) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        if (req.note() != null) {
            int cp = req.note().codePointCount(0, req.note().length());
            if (cp < 1 || cp > 4000) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }
    }

    static void requireId(String value) {
        if (value == null || value.isEmpty() || value.length() > 160) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    static void requirePositiveSeq(long value) {
        if (value < 1 || value > MAX_SAFE_INT) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    static void requireSeq(long value) {
        if (value < 0 || value > MAX_SAFE_INT) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static void requireScopedPath(String path, Set<String> includedPaths) {
        requireSafeRelativePath(path);
        if (!includedPaths.contains(path)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    static void requireSafeRelativePath(String path) {
        if (!isSafeRelativePath(path)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    static boolean isSafeRelativePath(String path) {
        if (path == null
                || path.isEmpty()
                || path.length() > 1024
                || path.startsWith("/")
                || path.endsWith("/")
                || path.contains("\0")
                || path.contains("//")
                || path.contains("\\")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()
                    || ".".equals(segment)
                    || "..".equals(segment)
                    || ".git".equalsIgnoreCase(segment)) {
                return false;
            }
        }
        return true;
    }

    static String requireBoundedText(String raw, int maxCodePoints) {
        if (raw == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > maxCodePoints) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return trimmed;
    }
}
