package com.orbitspace.modules.file;

import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class FileSecuritySupport {
    private static final Set<String> SAFE_THUMBNAIL_MIMES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private static final Set<String> DANGEROUS_ACTIVE_MIMES =
            Set.of(
                    "text/html",
                    "application/xhtml+xml",
                    "image/svg+xml",
                    "application/x-msdownload",
                    "application/x-dosexec",
                    "application/x-executable",
                    "application/x-sh");

    private FileSecuritySupport() {}

    static String sanitizeFileName(String raw) {
        if (raw == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (trimmed.startsWith(".")
                || trimmed.endsWith(".")
                || trimmed.contains("/")
                || trimmed.contains("\\")
                || trimmed.contains("..")) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "UNSAFE_FILENAME"));
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch < 0x20 || ch == 0x7f || ch == ':' || ch == '*' || ch == '?' || ch == '"'
                    || ch == '<' || ch == '>' || ch == '|') {
                throw new DomainException(
                        KernelErrorCode.INVALID_REQUEST, Map.of("reason", "UNSAFE_FILENAME"));
            }
        }
        return trimmed;
    }

    static String normalizeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "application/octet-stream";
        }
        String base = raw.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (base.isEmpty() || base.length() > 160 || !base.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (DANGEROUS_ACTIVE_MIMES.contains(base)) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "DISALLOWED_MIME_TYPE"));
        }
        return base;
    }

    static void verifyMagicBytes(String declaredContentType, byte[] headerBytes) {
        if (headerBytes == null || headerBytes.length == 0) {
            if (SAFE_THUMBNAIL_MIMES.contains(declaredContentType)
                    || "application/pdf".equals(declaredContentType)) {
                throw new DomainException(
                        KernelErrorCode.INVALID_REQUEST, Map.of("reason", "MIME_SPOOFING"));
            }
            return;
        }
        String detected = sniffMagicMime(headerBytes);
        if (detected != null && DANGEROUS_ACTIVE_MIMES.contains(detected)) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "DANGEROUS_MAGIC_BYTES"));
        }
        if (SAFE_THUMBNAIL_MIMES.contains(declaredContentType)
                && !declaredContentType.equals(detected)) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "MIME_SPOOFING"));
        }
        if ("application/pdf".equals(declaredContentType) && !"application/pdf".equals(detected)) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "MIME_SPOOFING"));
        }
        if ("application/zip".equals(declaredContentType) && !"application/zip".equals(detected)) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "MIME_SPOOFING"));
        }
    }

    static boolean isSafeThumbnailImage(String declaredContentType, byte[] headerBytes) {
        if (!SAFE_THUMBNAIL_MIMES.contains(declaredContentType)) {
            return false;
        }
        String detected = sniffMagicMime(headerBytes);
        return declaredContentType.equals(detected);
    }

    static String sniffMagicMime(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 6) {
            String head6 = new String(bytes, 0, 6, StandardCharsets.US_ASCII);
            if ("GIF87a".equals(head6) || "GIF89a".equals(head6)) {
                return "image/gif";
            }
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        if (bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-') {
            return "application/pdf";
        }
        if (bytes.length >= 4
                && bytes[0] == 'P'
                && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4)
                        || (bytes[2] == 5 && bytes[3] == 6)
                        || (bytes[2] == 7 && bytes[3] == 8))) {
            return "application/zip";
        }
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x7F
                && bytes[1] == 'E'
                && bytes[2] == 'L'
                && bytes[3] == 'F') {
            return "application/x-executable";
        }
        if (bytes.length >= 2 && bytes[0] == 'M' && bytes[1] == 'Z') {
            return "application/x-msdownload";
        }
        String textHead =
                new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8)
                        .stripLeading()
                        .toLowerCase(Locale.ROOT);
        if (textHead.startsWith("<!doctype html")
                || textHead.startsWith("<html")
                || textHead.startsWith("<script")) {
            return "text/html";
        }
        if (textHead.startsWith("<svg")
                || (textHead.startsWith("<?xml") && textHead.contains("<svg"))) {
            return "image/svg+xml";
        }
        return null;
    }

    static String safeDownloadContentDisposition(String fileName) {
        String safe = fileName.replace("\"", "_").replace("\r", "").replace("\n", "");
        return "attachment; filename=\"" + safe + "\"";
    }
}
