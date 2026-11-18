package com.orbitspace.kernel;

/** Cross-module SPI for space/user storage quota reservations and live file grant verification. */
public interface FileQuotaAndGrantPort {
    record QuotaReservation(
            String reservationId, GrantType grantType, String ownerScopeId, long reservedBytes) {}

    record VerifiedGrant(
            GrantType grantType,
            String grantId,
            String fileId,
            String objectKey,
            String fileName,
            String contentType,
            long sizeBytes,
            String sha256) {}

    QuotaReservation reserveQuota(
            GrantType grantType, String ownerScopeId, String actorUserId, long bytes);

    void releaseQuota(String reservationId);

    VerifiedGrant verifyGrant(
            String actorUserId,
            String spaceId,
            Long membershipEpoch,
            GrantType grantType,
            String grantId,
            String fileId);
}
