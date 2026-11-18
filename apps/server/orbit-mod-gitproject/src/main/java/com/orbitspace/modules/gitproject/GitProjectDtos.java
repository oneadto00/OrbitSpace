package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.orbitspace.kernel.BaseKind;
import com.orbitspace.kernel.ContentManifestStatus;
import com.orbitspace.kernel.DataLevel;
import com.orbitspace.kernel.GitTrustState;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Wire DTOs matching contracts/openapi/gitproject-cli.yaml and push-state.schema.json. */
public final class GitProjectDtos {
    private GitProjectDtos() {}

    public enum CaptureState {
        CHANGES,
        NO_SHARED_CHANGES,
        CLEAN
    }

    public enum ParseState {
        NOT_REQUESTED,
        PENDING,
        RUNNING,
        SUCCEEDED,
        RETRY_WAIT,
        FAILED,
        CANCELLED
    }

    public enum WipFreshness {
        ACTIVE,
        STALE
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateGitProjectRequest(@NotBlank String name) {}

    public record GitProjectDto(String spaceId, String projectId, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RegisterWorktreeRequest(@NotBlank @Size(max = 160) String localInstanceId) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AdvanceWorktreeEpochRequest(@Min(1) long expectedWorktreeEpoch) {}

    public record WorktreeDto(
            String projectId,
            String worktreeId,
            long worktreeEpoch,
            long currentCaptureSeq,
            String activeSnapshotId) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HeadDto(BaseKind baseKind, String baseCommitOid, String branch) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CommitMetadataDto(
            String oid,
            List<String> parents,
            String subject,
            String authorName,
            @JsonInclude(JsonInclude.Include.NON_NULL) String authorEmail,
            Instant authoredAt) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RefObservationDto(String name, String targetOid, Instant observedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record StagingSummaryDto(
            List<String> staged, List<String> unstaged, List<String> untracked) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ScopeDeclarationDto(
            List<String> includedPaths, long hiddenItemCount, long excludedItemCount) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ContentManifestEntryDto(
            String path,
            ContentManifestStatus status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String sha256) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PatchDescriptorDto(String sha256, long sizeBytes) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ShareContentDto(
            DataLevel dataLevel, @JsonInclude(JsonInclude.Include.NON_NULL) PatchDescriptorDto patch) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PushStateRequest(
            int protocolVersion,
            String spaceId,
            String projectId,
            String deviceId,
            String worktreeId,
            long membershipEpoch,
            long worktreeEpoch,
            long captureSeq,
            String idempotencyKey,
            String payloadDigest,
            Instant capturedAt,
            String objectFormat,
            HeadDto head,
            List<CommitMetadataDto> commits,
            List<RefObservationDto> refs,
            CaptureState captureState,
            StagingSummaryDto stagingSummary,
            ScopeDeclarationDto scopeDeclaration,
            List<ContentManifestEntryDto> contentManifest,
            ShareContentDto content,
            @JsonInclude(JsonInclude.Include.NON_NULL) String note) {}

    public record ReportSessionDto(String reportId, Instant expiresAt, boolean patchRequired) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReportProofRequest(
            @NotBlank @Size(max = 160) String idempotencyKey, @NotBlank String payloadDigest) {}

    public record CaptureReceiptDto(
            String worktreeId,
            long worktreeEpoch,
            long captureSeq,
            String snapshotId,
            String snapshotUrl,
            ParseState parseState) {}

    public record ObservationSourceDto(String deviceId, String userId, Instant observedAt) {}

    public record CommitObservationDto(
            ObservationSourceDto source, GitTrustState trustState, CommitMetadataDto metadata) {}

    public record CommitNodeDto(
            String oid,
            GitTrustState trustState,
            List<CommitObservationDto> observations,
            List<String> missingParentOids) {}

    public record ObservedRefDto(RefObservationDto ref, ObservationSourceDto source) {}

    public record WipNodeDto(
            String snapshotId,
            String worktreeId,
            long worktreeEpoch,
            long captureSeq,
            ObservationSourceDto source,
            HeadDto head,
            Instant capturedAt,
            WipFreshness freshness,
            CaptureState captureState,
            ParseState parseState,
            DataLevel dataLevel) {}

    public record ProjectGraphDto(
            String projectId,
            List<CommitNodeDto> commits,
            List<ObservedRefDto> refs,
            List<WipNodeDto> wipNodes,
            String nextCursor) {}

    public record CommitDetailDto(
            String projectId,
            CommitNodeDto commit,
            String selectedParentOid,
            DataLevel dataLevel,
            ScopeDeclarationDto scopeDeclaration,
            List<ContentManifestEntryDto> contentManifest,
            PatchDescriptorDto patch) {}

    public record SnapshotDetailDto(
            String projectId,
            WipNodeDto wip,
            ScopeDeclarationDto scopeDeclaration,
            List<ContentManifestEntryDto> contentManifest,
            StagingSummaryDto stagingSummary,
            PatchDescriptorDto patch,
            String note,
            String parseJobId) {}

    public record ParseJobDto(String jobId, String snapshotId, ParseState state) {}
}
