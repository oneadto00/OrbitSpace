package com.orbitspace.modules.file;

import com.orbitspace.kernel.GrantType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class FileDtos {
    private FileDtos() {}

    public enum UploadSessionStatus {
        INITIATED,
        UPLOADING,
        COMPLETED,
        EXPIRED,
        CANCELLED
    }

    public enum FileLifecycleState {
        ACTIVE,
        DELETED,
        FORCE_PURGED
    }

    public enum AirdropExpiryPreset {
        H24,
        D7,
        PERMANENT
    }

    public enum AirdropDeliveryState {
        DELIVERED,
        VIEWED,
        DOWNLOAD_STARTED
    }

    public enum AirdropRoleFilter {
        SENT,
        RECEIVED,
        ALL
    }

    public record CreateUploadSessionRequest(
            @NotNull GrantType targetScope,
            @Size(max = 160) String spaceId,
            @NotBlank @Size(max = 255) String fileName,
            @Size(max = 160) String contentType,
            @Min(0) long expectedSizeBytes,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String expectedSha256,
            @Min(1) @Max(67_108_864) Long partSizeBytes) {}

    public record UploadSessionDto(
            String uploadId,
            UploadSessionStatus status,
            GrantType targetScope,
            String spaceId,
            String fileName,
            String contentType,
            long expectedSizeBytes,
            String expectedSha256,
            long partSizeBytes,
            int totalParts,
            List<Integer> uploadedParts,
            boolean instantMatchAvailable,
            String matchedFileId,
            Instant expiresAt) {}

    public record UploadPartAck(
            String uploadId, int partNumber, long sizeBytes, String sha256) {}

    public record CompleteUploadSessionRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String expectedSha256,
            Boolean useInstantMatch) {}

    public record FileRecordDto(
            String fileId,
            String spaceId,
            String ownerUserId,
            String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            GrantType scope,
            FileLifecycleState lifecycleState,
            String thumbnailJobId,
            boolean thumbnailReady,
            Instant createdAt) {}

    public record SpaceFileListResponse(
            long quotaUsedBytes, long quotaLimitBytes, List<FileRecordDto> items) {}

    public record AttachSpaceFileRequest(@NotBlank @Size(max = 160) String uploadId) {}

    public record CreateAirdropRequest(
            @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 160) String> fileIds,
            @Size(max = 160) String sourceSpaceId,
            @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 160) String> recipientUserIds,
            @NotNull AirdropExpiryPreset expiryPreset,
            @Min(1) Integer maxDownloadsPerRecipient,
            @Size(max = 1000) String note) {}

    public record AirdropFileItem(
            String fileId, String fileName, String contentType, long sizeBytes, String sha256) {}

    public record AirdropRecipientState(
            String recipientUserId,
            AirdropDeliveryState deliveryState,
            int downloadCount,
            Integer remainingDownloads,
            boolean revoked,
            Instant updatedAt) {}

    public record AirdropShareDto(
            String shareId,
            String senderUserId,
            String title,
            AirdropExpiryPreset expiryPreset,
            Instant expiresAt,
            Integer maxDownloadsPerRecipient,
            boolean revoked,
            Instant createdAt,
            List<AirdropFileItem> files,
            List<AirdropRecipientState> recipients) {}

    public record AirdropShareListResponse(List<AirdropShareDto> items) {}

    public record CreateDownloadSessionRequest(
            @NotNull GrantType grantType,
            @NotBlank @Size(max = 160) String grantId,
            @NotBlank @Size(max = 160) String fileId) {}

    public record DownloadSessionDto(
            String downloadSessionId,
            GrantType grantType,
            String grantId,
            String fileId,
            String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            Integer remainingDownloads,
            Instant expiresAt,
            String downloadPath) {}
}
