package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.orbitspace.kernel.ContentManifestStatus;
import com.orbitspace.kernel.DiscussionStatus;
import com.orbitspace.modules.gitproject.GitProjectDtos.ScopeDeclarationDto;

import java.time.Instant;
import java.util.List;

/** Wire DTOs matching contracts/openapi/p2-files-diff.yaml for backfill, two-point diffs, and code comments. */
public final class GitDiffCommentDtos {
    private GitDiffCommentDtos() {}

    public enum DiffEndpointKind {
        COMMIT,
        WIP
    }

    public enum BackfillMissingReason {
        MISSING,
        NOT_SHARED
    }

    public enum TwoPointDiffStatus {
        PENDING,
        READY,
        FAILED
    }

    public enum TwoPointComparisonState {
        ADDED,
        DELETED,
        MODIFIED,
        UNCHANGED,
        INCOMPARABLE_NOT_SHARED,
        INCOMPARABLE_MISSING,
        INCOMPARABLE_UNSUPPORTED
    }

    public enum CodeCommentSide {
        OLD,
        NEW
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiffEndpointRef(DiffEndpointKind kind, String id) {}

    public record BackfillMissingItem(
            String path,
            DiffEndpointKind endpointKind,
            String endpointId,
            String expectedBlobOid,
            BackfillMissingReason reason) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BackfillCheckRequest(
            DiffEndpointRef baseEndpoint, DiffEndpointRef targetEndpoint, List<String> paths) {}

    public record BackfillCheckResponse(
            String projectId,
            List<BackfillMissingItem> missingItems,
            List<String> unsupportedPaths) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BackfillManifestItem(
            String path, String mode, String blobOid, Long sizeBytes, String sha256) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateBackfillSessionRequest(
            DiffEndpointKind targetKind,
            String targetId,
            ScopeDeclarationDto scopeDeclaration,
            List<BackfillManifestItem> items) {}

    public record BackfillSessionDto(
            String backfillId, List<String> requiredPaths, Instant expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CompleteBackfillRequest(String payloadDigest, List<String> uploadedPaths) {}

    public record BackfillReceiptDto(
            String backfillId,
            String projectId,
            DiffEndpointKind targetKind,
            String targetId,
            long acceptedCount,
            Instant completedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateTwoPointDiffRequest(DiffEndpointRef base, DiffEndpointRef target) {}

    public record TwoPointDiffSummary(
            long comparableFiles,
            long unsharedOrMissingFiles,
            long unsupportedFiles,
            boolean hasInsufficientData) {}

    public record DiffSymbolChange(
            String name, String kind, Integer oldStartLine, Integer newStartLine) {}

    public record TwoPointDiffFileEntry(
            String path,
            String oldPath,
            ContentManifestStatus baseStatus,
            ContentManifestStatus targetStatus,
            TwoPointComparisonState comparisonState,
            boolean deterministic,
            String reasonMessage,
            String patchText,
            List<DiffSymbolChange> symbols) {}

    public record TwoPointDiffDto(
            String diffId,
            String projectId,
            DiffEndpointRef base,
            DiffEndpointRef target,
            TwoPointDiffStatus status,
            String enrichmentJobId,
            TwoPointDiffSummary summary,
            List<TwoPointDiffFileEntry> files,
            Instant createdAt) {}

    public record CodeCommentReplyDto(
            String messageId, String senderId, String bodyText, Instant createdAt) {}

    public record CodeCommentDto(
            String commentId,
            String spaceId,
            String projectId,
            String diffId,
            DiffEndpointRef base,
            DiffEndpointRef target,
            String filePath,
            CodeCommentSide side,
            long lineNumber,
            String authorUserId,
            String bodyText,
            String rootChatMessageId,
            DiscussionStatus discussionStatus,
            long discussionVersion,
            List<CodeCommentReplyDto> replies,
            Instant createdAt,
            Instant updatedAt) {}

    public record CodeCommentListResponse(List<CodeCommentDto> items) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateCodeCommentRequest(
            String diffId,
            DiffEndpointRef base,
            DiffEndpointRef target,
            String filePath,
            CodeCommentSide side,
            Integer lineNumber,
            String bodyText,
            Boolean forwardToChat) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReplyCodeCommentRequest(String bodyText) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ChangeCodeCommentStatusRequest(
            DiscussionStatus status, String conclusionMsgId) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ForwardCodeCommentRequest(String purpose) {}
}
