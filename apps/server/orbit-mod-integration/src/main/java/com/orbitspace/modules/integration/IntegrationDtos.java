package com.orbitspace.modules.integration;

import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.AsyncJobStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class IntegrationDtos {
    private IntegrationDtos() {}

    public record InternalClaimJobRequest(
            @NotBlank @Size(max = 128) String workerId,
            @Min(5) @Max(3600) int leaseSeconds) {}

    public record InternalClaimedJobResponse(
            boolean claimed,
            String jobId,
            String jobType,
            String spaceId,
            String resourceId,
            String actorId,
            String inputObjectKey,
            long inputVersion,
            long resourceLifecycleVersion,
            String leaseId,
            Instant leaseUntil) {}

    public record InternalStoredOutputResponse(
            String objectKey, long sizeBytes, String sha256) {}

    public record InternalJobResultRequest(
            @NotBlank @Size(max = 160) String leaseId,
            @NotNull AsyncJobStatus expectedJobStatus,
            long inputVersion,
            long resourceLifecycleVersion,
            @Size(max = 192) String resultObjectKey,
            @Size(max = 192) String stagingObjectKey,
            @Min(0) Long expectedSizeBytes,
            @Pattern(regexp = "^[0-9a-f]{64}$") String expectedSha256) {}

    public record InternalJobResultResponse(
            AsyncJobPort.Acceptance acceptance, String jobId, String resultObjectKey) {}

    public record InternalJobFailRequest(
            @NotBlank @Size(max = 160) String leaseId,
            boolean retryable,
            @NotBlank @Size(max = 64) String failureCode,
            @Min(1) @Max(86400) Integer retryDelaySeconds) {}

    public record InternalJobFailResponse(boolean updated) {}
}
