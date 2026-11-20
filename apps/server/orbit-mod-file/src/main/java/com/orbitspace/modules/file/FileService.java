package com.orbitspace.modules.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.AuditLogPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.FileQuotaAndGrantPort;
import com.orbitspace.kernel.FriendshipQueryPort;
import com.orbitspace.kernel.GrantType;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.MembershipRevocationHook;
import com.orbitspace.kernel.MessageKind;
import com.orbitspace.kernel.ObjectStoragePort;
import com.orbitspace.kernel.ResourceCardVerifierPort;
import com.orbitspace.kernel.ResourceLifecycleHook;
import com.orbitspace.kernel.ResourceLifecycleState;
import com.orbitspace.kernel.SpaceAuthorizationPort;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.file.FileDtos.AirdropDeliveryState;
import com.orbitspace.modules.file.FileDtos.AirdropExpiryPreset;
import com.orbitspace.modules.file.FileDtos.AirdropFileItem;
import com.orbitspace.modules.file.FileDtos.AirdropRecipientState;
import com.orbitspace.modules.file.FileDtos.AirdropRoleFilter;
import com.orbitspace.modules.file.FileDtos.AirdropShareDto;
import com.orbitspace.modules.file.FileDtos.AirdropShareListResponse;
import com.orbitspace.modules.file.FileDtos.CompleteUploadSessionRequest;
import com.orbitspace.modules.file.FileDtos.CreateAirdropRequest;
import com.orbitspace.modules.file.FileDtos.CreateDownloadSessionRequest;
import com.orbitspace.modules.file.FileDtos.CreateUploadSessionRequest;
import com.orbitspace.modules.file.FileDtos.DownloadSessionDto;
import com.orbitspace.modules.file.FileDtos.FileLifecycleState;
import com.orbitspace.modules.file.FileDtos.FileRecordDto;
import com.orbitspace.modules.file.FileDtos.SpaceFileListResponse;
import com.orbitspace.modules.file.FileDtos.UploadPartAck;
import com.orbitspace.modules.file.FileDtos.UploadSessionDto;
import com.orbitspace.modules.file.FileDtos.UploadSessionStatus;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class FileService
        implements FileQuotaAndGrantPort, MembershipRevocationHook, ResourceCardVerifierPort {
    private static final long DEFAULT_PART_SIZE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_PARTS_PER_UPLOAD = 10_000;
    private static final int HEADER_SNIFF_LIMIT = 512;
    private static final Duration UPLOAD_SESSION_TTL = Duration.ofHours(24);
    private static final Duration DOWNLOAD_SESSION_TTL = Duration.ofHours(1);
    private static final String IMAGE_THUMBNAIL_JOB_TYPE = "IMAGE_THUMBNAIL";

    record OpenedDownloadContent(
            DownloadSessionRow session,
            FileRow file,
            long startByte,
            long endByte,
            boolean partial,
            InputStream stream) {}

    private record CreateUploadInput(
            GrantType targetScope,
            String spaceId,
            String fileName,
            String contentType,
            long expectedSizeBytes,
            String expectedSha256,
            long partSizeBytes) {}

    private record CompleteUploadInput(
            String uploadId, String expectedSha256, boolean useInstantMatch) {}

    private record AttachSpaceFileInput(String spaceId, String uploadId) {}

    private record CreateAirdropInput(
            List<String> fileIds,
            String sourceSpaceId,
            List<String> recipientUserIds,
            AirdropExpiryPreset expiryPreset,
            Integer maxDownloadsPerRecipient,
            String note) {}

    private record RevokeRecipientInput(String shareId, String recipientUserId) {}

    private record RevokeShareInput(String shareId) {}

    private record CreateDownloadInput(GrantType grantType, String grantId, String fileId) {}

    private record UploadSessionRow(
            String uploadId,
            String actorUserId,
            GrantType targetScope,
            String spaceId,
            Long membershipEpoch,
            String fileName,
            String contentType,
            long expectedSizeBytes,
            String expectedSha256,
            long partSizeBytes,
            int totalParts,
            UploadSessionStatus status,
            String matchedFileId,
            String completedFileId,
            String quotaReservationId,
            Instant expiresAt) {}

    private record UploadPartRow(
            int partNumber, long sizeBytes, String sha256, String objectKey) {}

    record FileRow(
            String fileId,
            GrantType scope,
            String spaceId,
            String ownerUserId,
            String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            String objectKey,
            FileLifecycleState lifecycleState,
            long lifecycleVersion,
            String thumbnailJobId,
            boolean thumbnailReady,
            Instant createdAt) {
        FileRecordDto toDto() {
            return new FileRecordDto(
                    fileId,
                    spaceId,
                    ownerUserId,
                    fileName,
                    contentType,
                    sizeBytes,
                    sha256,
                    scope,
                    lifecycleState,
                    thumbnailJobId,
                    thumbnailReady,
                    createdAt);
        }

        AirdropFileItem toAirdropFileItem() {
            return new AirdropFileItem(fileId, fileName, contentType, sizeBytes, sha256);
        }
    }

    private record ShareRow(
            String shareId,
            String senderUserId,
            String sourceSpaceId,
            String title,
            String note,
            AirdropExpiryPreset expiryPreset,
            Instant expiresAt,
            Integer maxDownloadsPerRecipient,
            boolean revoked,
            Instant createdAt) {}

    private record RecipientRow(
            String shareId,
            String recipientUserId,
            AirdropDeliveryState deliveryState,
            int downloadCount,
            boolean revoked,
            Instant updatedAt) {
        AirdropRecipientState toDto(Integer maxDownloads) {
            Integer remaining =
                    maxDownloads == null ? null : Math.max(0, maxDownloads - downloadCount);
            return new AirdropRecipientState(
                    recipientUserId, deliveryState, downloadCount, remaining, revoked, updatedAt);
        }
    }

    record DownloadSessionRow(
            String downloadSessionId,
            String actorUserId,
            GrantType grantType,
            String grantId,
            String fileId,
            Long spaceMembershipEpoch,
            long bytesSent,
            Instant revokedAt,
            Instant expiresAt) {}

    private static final class HeaderCapturingInputStream extends FilterInputStream {
        private final ByteArrayOutputStream header = new ByteArrayOutputStream(HEADER_SNIFF_LIMIT);

        HeaderCapturingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && header.size() < HEADER_SNIFF_LIMIT) {
                header.write(b);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = super.read(b, off, len);
            if (count > 0 && header.size() < HEADER_SNIFF_LIMIT) {
                int toCopy = Math.min(count, HEADER_SNIFF_LIMIT - header.size());
                header.write(b, off, toCopy);
            }
            return count;
        }

        byte[] capturedHeader() {
            return header.toByteArray();
        }
    }

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final SpaceAuthorizationPort spaceAuth;
    private final ObjectStoragePort objectStorage;
    private final AsyncJobPort asyncJobs;
    private final AuditLogPort auditLog;
    private final List<ResourceLifecycleHook> lifecycleHooks;
    private final ObjectProvider<FriendshipQueryPort> friendshipQueryProvider;
    private final FileIdempotencyStore idempotency;
    private final long defaultUserQuotaBytes;
    private final long maxUploadBytes;
    private final long maxThumbnailInputBytes;

    public FileService(
            JdbcTemplate db,
            PlatformTransactionManager txManager,
            ObjectMapper mapper,
            SpaceAuthorizationPort spaceAuth,
            ObjectStoragePort objectStorage,
            AsyncJobPort asyncJobs,
            AuditLogPort auditLog,
            List<ResourceLifecycleHook> lifecycleHooks,
            ObjectProvider<FriendshipQueryPort> friendshipQueryProvider,
            @Value("${orbit.file.user-quota-bytes:1073741824}") long defaultUserQuotaBytes,
            @Value("${orbit.file.max-upload-bytes:536870912}") long maxUploadBytes,
            @Value("${orbit.file.max-thumbnail-input-bytes:20971520}") long maxThumbnailInputBytes) {
        this.db = db;
        this.tx = new TransactionTemplate(txManager);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
        this.spaceAuth = spaceAuth;
        this.objectStorage = objectStorage;
        this.asyncJobs = asyncJobs;
        this.auditLog = auditLog;
        this.lifecycleHooks = List.copyOf(lifecycleHooks);
        this.friendshipQueryProvider = friendshipQueryProvider;
        this.idempotency = new FileIdempotencyStore(db, mapper);
        this.defaultUserQuotaBytes = defaultUserQuotaBytes;
        this.maxUploadBytes = maxUploadBytes;
        this.maxThumbnailInputBytes = maxThumbnailInputBytes;
    }

    @Transactional
    public UploadSessionDto createUploadSession(
            String actorUserId,
            Long membershipEpoch,
            String idempotencyKey,
            CreateUploadSessionRequest req) {
        if (req.expectedSizeBytes() < 0 || req.expectedSizeBytes() > maxUploadBytes) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String fileName = FileSecuritySupport.sanitizeFileName(req.fileName());
        String contentType = FileSecuritySupport.normalizeContentType(req.contentType());
        long partSizeBytes =
                req.partSizeBytes() != null ? req.partSizeBytes() : DEFAULT_PART_SIZE_BYTES;
        if (partSizeBytes < 1) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        int totalParts =
                req.expectedSizeBytes() == 0
                        ? 1
                        : (int) ((req.expectedSizeBytes() + partSizeBytes - 1) / partSizeBytes);
        if (totalParts < 1 || totalParts > MAX_PARTS_PER_UPLOAD) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        String normalizedSpaceId = null;
        String ownerScopeId;
        if (req.targetScope() == GrantType.SPACE_FILE) {
            normalizedSpaceId = normalizeId(req.spaceId());
            if (membershipEpoch == null) {
                throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
            }
            spaceAuth.requireMembership(
                    normalizedSpaceId, actorUserId, membershipEpoch, SpaceRole.MEMBER);
            ownerScopeId = normalizedSpaceId;
        } else {
            if (req.spaceId() != null && !req.spaceId().isBlank()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            ownerScopeId = actorUserId;
        }

        String spaceIdFinal = normalizedSpaceId;
        Long epochFinal = req.targetScope() == GrantType.SPACE_FILE ? membershipEpoch : null;
        CreateUploadInput canonicalInput =
                new CreateUploadInput(
                        req.targetScope(),
                        spaceIdFinal,
                        fileName,
                        contentType,
                        req.expectedSizeBytes(),
                        req.expectedSha256(),
                        partSizeBytes);

        return idempotency.execute(
                        "CREATE_UPLOAD_SESSION",
                        actorUserId,
                        idempotencyKey,
                        canonicalInput,
                        201,
                        UploadSessionDto.class,
                        () ->
                                createUploadSessionInternal(
                                        actorUserId,
                                        req.targetScope(),
                                        spaceIdFinal,
                                        epochFinal,
                                        ownerScopeId,
                                        fileName,
                                        contentType,
                                        req.expectedSizeBytes(),
                                        req.expectedSha256(),
                                        partSizeBytes,
                                        totalParts))
                .body();
    }

    private UploadSessionDto createUploadSessionInternal(
            String actorUserId,
            GrantType targetScope,
            String spaceId,
            Long membershipEpoch,
            String ownerScopeId,
            String fileName,
            String contentType,
            long expectedSizeBytes,
            String expectedSha256,
            long partSizeBytes,
            int totalParts) {
        QuotaReservation reservation =
                reserveQuota(targetScope, ownerScopeId, actorUserId, expectedSizeBytes);

        String matchedFileId =
                findUserScopedInstantMatch(actorUserId, expectedSha256, expectedSizeBytes);
        String uploadId = UUID.randomUUID().toString();
        Instant expiresAt =
                Instant.now().plus(UPLOAD_SESSION_TTL).truncatedTo(ChronoUnit.MICROS);

        db.update(
                """
                INSERT INTO upload_sessions(
                    upload_id, actor_user_id, target_scope, space_id, membership_epoch,
                    file_name, content_type, expected_size_bytes, expected_sha256,
                    part_size_bytes, total_parts, status, matched_file_id,
                    quota_reservation_id, expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INITIATED', ?, ?, ?)
                """,
                uploadId,
                actorUserId,
                targetScope.name(),
                spaceId,
                membershipEpoch,
                fileName,
                contentType,
                expectedSizeBytes,
                expectedSha256,
                partSizeBytes,
                totalParts,
                matchedFileId,
                reservation.reservationId(),
                Timestamp.from(expiresAt));

        return toUploadSessionDto(loadUploadSession(uploadId, false), List.of());
    }

    @Transactional(readOnly = true)
    public UploadSessionDto getUploadSession(String actorUserId, String uploadId) {
        UploadSessionRow session = loadUploadSession(normalizeId(uploadId), false);
        if (!session.actorUserId().equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (session.targetScope() == GrantType.SPACE_FILE) {
            spaceAuth.requireMembership(
                    session.spaceId(), actorUserId, session.membershipEpoch(), SpaceRole.MEMBER);
        }
        List<Integer> parts = loadUploadedPartNumbers(session.uploadId());
        return toUploadSessionDto(session, parts);
    }

    public UploadPartAck uploadPart(
            String actorUserId,
            String rawUploadId,
            int partNumber,
            String expectedPartSha256,
            InputStream body) {
        String uploadId = normalizeId(rawUploadId);
        if (expectedPartSha256 != null && !expectedPartSha256.matches("^[0-9a-f]{64}$")) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        UploadSessionRow session = loadUploadSession(uploadId, false);
        validateUploadSessionWritable(session, actorUserId);
        if (partNumber < 1 || partNumber > session.totalParts()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        long expectedPartSize = expectedPartSize(session, partNumber);
        HeaderCapturingInputStream capturing = new HeaderCapturingInputStream(body);
        ObjectStoragePort.StoredObject tempObj;
        ObjectStoragePort.StoredObject promoted;
        try {
            tempObj =
                    objectStorage.writeTemporary(
                            "file-part:" + uploadId + ":" + partNumber,
                            capturing,
                            expectedPartSize);
            if (tempObj.sizeBytes() != expectedPartSize) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            if (expectedPartSha256 != null && !expectedPartSha256.equals(tempObj.sha256())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            if (partNumber == 1) {
                FileSecuritySupport.verifyMagicBytes(
                        session.contentType(), capturing.capturedHeader());
            }
            promoted =
                    objectStorage.promote(
                            tempObj.objectKey(), tempObj.sizeBytes(), tempObj.sha256());
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        tx.executeWithoutResult(
                status -> {
                    UploadSessionRow locked = loadUploadSession(uploadId, true);
                    validateUploadSessionWritable(locked, actorUserId);
                    UploadPartRow existing = findUploadPart(uploadId, partNumber, true);
                    if (existing != null) {
                        if (existing.sizeBytes() != promoted.sizeBytes()
                                || !existing.sha256().equals(promoted.sha256())) {
                            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                        }
                        return;
                    }
                    pinStorageReference(
                            "UPLOAD_PART",
                            uploadPartOwnerId(uploadId, partNumber),
                            promoted.objectKey());
                    db.update(
                            """
                            INSERT INTO upload_parts(upload_id, part_number, size_bytes, sha256_hex, object_key)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                            uploadId,
                            partNumber,
                            promoted.sizeBytes(),
                            promoted.sha256(),
                            promoted.objectKey());
                    db.update(
                            """
                            UPDATE upload_sessions
                            SET status = 'UPLOADING', updated_at = CURRENT_TIMESTAMP(6)
                            WHERE upload_id = ? AND status = 'INITIATED'
                            """,
                            uploadId);
                });

        return new UploadPartAck(uploadId, partNumber, promoted.sizeBytes(), promoted.sha256());
    }

    public FileRecordDto completeUploadSession(
            String actorUserId,
            String rawUploadId,
            String idempotencyKey,
            CompleteUploadSessionRequest req) {
        String uploadId = normalizeId(rawUploadId);
        boolean useInstantMatch = Boolean.TRUE.equals(req.useInstantMatch());
        String keyNormalized = FileIdempotencyStore.validateKey(idempotencyKey);

        UploadSessionRow preSession = loadUploadSession(uploadId, false);
        if (!preSession.actorUserId().equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (!preSession.expectedSha256().equals(req.expectedSha256())) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (preSession.targetScope() == GrantType.SPACE_FILE) {
            spaceAuth.requireMembership(
                    preSession.spaceId(),
                    actorUserId,
                    preSession.membershipEpoch(),
                    SpaceRole.MEMBER);
        }

        CompleteUploadInput canonicalInput =
                new CompleteUploadInput(uploadId, req.expectedSha256(), useInstantMatch);

        String preparedObjectKey = null;
        byte[] preparedHeader = null;
        if (preSession.status() != UploadSessionStatus.COMPLETED) {
            if (preSession.status() == UploadSessionStatus.EXPIRED
                    || preSession.status() == UploadSessionStatus.CANCELLED
                    || !preSession.expiresAt().isAfter(Instant.now())) {
                throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
            }
            if (!useInstantMatch) {
                List<UploadPartRow> parts = loadUploadParts(uploadId);
                if (parts.size() != preSession.totalParts()) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                for (int i = 0; i < parts.size(); i++) {
                    if (parts.get(i).partNumber() != i + 1) {
                        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                    }
                }
                PreparedObject merged = mergeAndPromoteParts(preSession, parts);
                preparedObjectKey = merged.objectKey();
                preparedHeader = merged.headerBytes();
            }
        }

        final String mergedObjectKey = preparedObjectKey;
        final byte[] mergedHeader = preparedHeader;

        return tx.execute(
                status ->
                        idempotency.execute(
                                        "COMPLETE_UPLOAD_SESSION",
                                        actorUserId,
                                        keyNormalized,
                                        canonicalInput,
                                        200,
                                        FileRecordDto.class,
                                        () ->
                                                completeUploadInTx(
                                                        actorUserId,
                                                        uploadId,
                                                        req.expectedSha256(),
                                                        useInstantMatch,
                                                        mergedObjectKey,
                                                        mergedHeader))
                                .body());
    }

    private FileRecordDto completeUploadInTx(
            String actorUserId,
            String uploadId,
            String expectedSha256,
            boolean useInstantMatch,
            String mergedObjectKey,
            byte[] mergedHeader) {
        UploadSessionRow session = loadUploadSession(uploadId, true);
        if (!session.actorUserId().equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (!session.expectedSha256().equals(expectedSha256)) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (session.targetScope() == GrantType.SPACE_FILE) {
            spaceAuth.requireMembership(
                    session.spaceId(), actorUserId, session.membershipEpoch(), SpaceRole.MEMBER);
        }

        if (session.status() == UploadSessionStatus.COMPLETED && session.completedFileId() != null) {
            return loadFileRow(session.completedFileId(), false).toDto();
        }
        if (session.status() == UploadSessionStatus.EXPIRED
                || session.status() == UploadSessionStatus.CANCELLED
                || !session.expiresAt().isAfter(Instant.now())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }

        String finalObjectKey;
        byte[] headerBytes;
        if (useInstantMatch) {
            String matchedFileId =
                    findUserScopedInstantMatch(
                            actorUserId, session.expectedSha256(), session.expectedSizeBytes());
            if (matchedFileId == null) {
                throw new DomainException(
                        KernelErrorCode.AUTH_FORBIDDEN,
                        Map.of("reason", "INSTANT_MATCH_NOT_AVAILABLE"));
            }
            FileRow matched = loadFileRow(matchedFileId, false);
            if (!matched.ownerUserId().equals(actorUserId)
                    || matched.lifecycleState() != FileLifecycleState.ACTIVE
                    || !isImmutableObjectLive(matched.objectKey())) {
                throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
            }
            finalObjectKey = matched.objectKey();
            headerBytes = readObjectHeaderBytes(finalObjectKey);
            FileSecuritySupport.verifyMagicBytes(session.contentType(), headerBytes);
        } else {
            if (mergedObjectKey == null || !isImmutableObjectLive(mergedObjectKey)) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            finalObjectKey = mergedObjectKey;
            headerBytes = mergedHeader != null ? mergedHeader : new byte[0];
        }

        String fileId = UUID.randomUUID().toString();
        boolean enqueueThumbnail =
                session.expectedSizeBytes() > 0
                        && session.expectedSizeBytes() <= maxThumbnailInputBytes
                        && FileSecuritySupport.isSafeThumbnailImage(
                                session.contentType(), headerBytes);
        String thumbnailJobId = enqueueThumbnail ? UUID.randomUUID().toString() : null;

        consumeQuotaReservationAndCharge(
                session.quotaReservationId(),
                session.targetScope(),
                session.targetScope() == GrantType.SPACE_FILE ? session.spaceId() : actorUserId,
                session.expectedSizeBytes());

        db.update(
                """
                INSERT INTO files(
                    id, scope, space_id, owner_user_id, file_name, content_type,
                    size_bytes, sha256_hex, object_key, lifecycle_state, lifecycle_version,
                    thumbnail_job_id, thumbnail_ready
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, FALSE)
                """,
                fileId,
                session.targetScope().name(),
                session.spaceId(),
                actorUserId,
                session.fileName(),
                session.contentType(),
                session.expectedSizeBytes(),
                session.expectedSha256(),
                finalObjectKey,
                thumbnailJobId);

        pinStorageReference("FILE_CONTENT", fileId, finalObjectKey);
        detachUploadPartReferences(uploadId, session.totalParts());

        db.update(
                """
                UPDATE upload_sessions
                SET status = 'COMPLETED', completed_file_id = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE upload_id = ?
                """,
                fileId,
                uploadId);

        if (thumbnailJobId != null) {
            String jobSpaceId =
                    session.targetScope() == GrantType.SPACE_FILE
                            ? session.spaceId()
                            : "PERSONAL:" + actorUserId;
            asyncJobs.enqueue(
                    new AsyncJobPort.Job(
                            thumbnailJobId,
                            IMAGE_THUMBNAIL_JOB_TYPE,
                            jobSpaceId,
                            fileId,
                            actorUserId,
                            finalObjectKey,
                            1L,
                            1L));
        }

        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        "FILE_UPLOAD_COMPLETE",
                        fileId,
                        "SUCCESS",
                        Instant.now()));

        return loadFileRow(fileId, false).toDto();
    }

    @Transactional(readOnly = true)
    public SpaceFileListResponse listSpaceFiles(String spaceId, String actorUserId, long epoch) {
        spaceAuth.requireMembership(spaceId, actorUserId, epoch, SpaceRole.VIEWER);
        SpaceQuotaRow quota =
                db.queryForObject(
                        "SELECT used_bytes, quota_bytes FROM spaces WHERE id = ?",
                        (rs, i) -> new SpaceQuotaRow(rs.getLong(1), rs.getLong(2)),
                        spaceId);
        List<FileRow> rows =
                db.query(
                        fileSelectSql()
                                + """
                                 WHERE scope = 'SPACE_FILE' AND space_id = ? AND lifecycle_state = 'ACTIVE'
                                 ORDER BY created_at DESC, id ASC
                                """,
                        this::mapFileRow,
                        spaceId);
        return new SpaceFileListResponse(
                quota != null ? quota.usedBytes() : 0L,
                quota != null ? quota.quotaBytes() : 0L,
                rows.stream().map(FileRow::toDto).toList());
    }

    @Transactional
    public FileRecordDto attachSpaceFile(
            String spaceId,
            String actorUserId,
            long epoch,
            String idempotencyKey,
            String rawUploadId) {
        spaceAuth.requireMembership(spaceId, actorUserId, epoch, SpaceRole.MEMBER);
        String uploadId = normalizeId(rawUploadId);

        return idempotency.execute(
                        "ATTACH_SPACE_FILE",
                        actorUserId,
                        idempotencyKey,
                        new AttachSpaceFileInput(spaceId, uploadId),
                        201,
                        FileRecordDto.class,
                        () -> attachSpaceFileInternal(spaceId, actorUserId, epoch, uploadId))
                .body();
    }

    private FileRecordDto attachSpaceFileInternal(
            String spaceId, String actorUserId, long epoch, String uploadId) {
        spaceAuth.requireMembership(spaceId, actorUserId, epoch, SpaceRole.MEMBER);
        UploadSessionRow session = loadUploadSession(uploadId, true);
        if (!session.actorUserId().equals(actorUserId)
                || session.targetScope() != GrantType.SPACE_FILE
                || !spaceId.equals(session.spaceId())) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (session.status() != UploadSessionStatus.COMPLETED || session.completedFileId() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        FileRow file = loadFileRow(session.completedFileId(), true);
        if (file.lifecycleState() != FileLifecycleState.ACTIVE
                || file.scope() != GrantType.SPACE_FILE
                || !spaceId.equals(file.spaceId())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        return file.toDto();
    }

    @Transactional
    public void deleteSpaceFile(
            String spaceId, String fileId, String actorUserId, long epoch) {
        SpaceAuthorizationPort.Membership membership =
                spaceAuth.requireMembership(spaceId, actorUserId, epoch, SpaceRole.MEMBER);
        FileRow file = loadFileRow(normalizeId(fileId), true);
        if (file.scope() != GrantType.SPACE_FILE || !spaceId.equals(file.spaceId())) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!membership.role().atLeast(SpaceRole.ADMIN)
                && !file.ownerUserId().equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (file.lifecycleState() != FileLifecycleState.ACTIVE) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }

        long nextVersion = file.lifecycleVersion() + 1;
        db.update(
                """
                UPDATE files
                SET lifecycle_state = 'DELETED',
                    lifecycle_version = ?,
                    thumbnail_ready = FALSE,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND lifecycle_state = 'ACTIVE'
                """,
                nextVersion,
                file.fileId());

        db.update(
                """
                UPDATE spaces
                SET used_bytes = GREATEST(0, CAST(used_bytes AS SIGNED) - ?)
                WHERE id = ?
                """,
                file.sizeBytes(),
                spaceId);

        db.update(
                """
                DELETE FROM storage_object_references
                WHERE owner_type IN ('FILE_CONTENT', 'FILE_THUMBNAIL') AND owner_id = ?
                """,
                file.fileId());

        cancelActiveFileJobs(file.fileId());

        db.update(
                """
                UPDATE download_sessions
                SET revoked_at = CURRENT_TIMESTAMP(6)
                WHERE file_id = ? AND revoked_at IS NULL
                """,
                file.fileId());

        notifyFileLifecycle(
                spaceId,
                file.fileId(),
                GrantType.SPACE_FILE,
                ResourceLifecycleState.EXPIRED_OR_DELETED,
                nextVersion);

        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        "SPACE_FILE_DELETE",
                        file.fileId(),
                        "SUCCESS",
                        Instant.now()));
    }

    @Transactional
    public AirdropShareDto createAirdrop(
            String actorUserId,
            Long membershipEpoch,
            String idempotencyKey,
            CreateAirdropRequest req) {
        List<String> fileIds =
                req.fileIds().stream().map(FileService::normalizeId).distinct().toList();
        List<String> recipientUserIds =
                req.recipientUserIds().stream().map(FileService::normalizeId).distinct().toList();
        if (fileIds.isEmpty() || recipientUserIds.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (recipientUserIds.contains(actorUserId)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        FriendshipQueryPort friendshipPort = friendshipQueryProvider.getIfAvailable();
        if (friendshipPort == null) {
            throw new DomainException(KernelErrorCode.NOT_FRIENDS_FOR_AIRDROP);
        }
        Set<String> validFriends = friendshipPort.filterFriends(actorUserId, recipientUserIds);
        if (!validFriends.containsAll(recipientUserIds)) {
            throw new DomainException(KernelErrorCode.NOT_FRIENDS_FOR_AIRDROP);
        }

        String sourceSpaceId =
                req.sourceSpaceId() != null && !req.sourceSpaceId().isBlank()
                        ? normalizeId(req.sourceSpaceId())
                        : null;
        if (sourceSpaceId != null) {
            if (membershipEpoch == null) {
                throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
            }
            spaceAuth.requireMembership(
                    sourceSpaceId, actorUserId, membershipEpoch, SpaceRole.MEMBER);
        }

        String note =
                req.note() != null && !req.note().isBlank() ? req.note().strip() : null;
        CreateAirdropInput canonicalInput =
                new CreateAirdropInput(
                        fileIds,
                        sourceSpaceId,
                        recipientUserIds,
                        req.expiryPreset(),
                        req.maxDownloadsPerRecipient(),
                        note);

        return idempotency.execute(
                        "CREATE_AIRDROP",
                        actorUserId,
                        idempotencyKey,
                        canonicalInput,
                        201,
                        AirdropShareDto.class,
                        () ->
                                createAirdropInternal(
                                        actorUserId,
                                        sourceSpaceId,
                                        membershipEpoch,
                                        fileIds,
                                        recipientUserIds,
                                        req.expiryPreset(),
                                        req.maxDownloadsPerRecipient(),
                                        note))
                .body();
    }

    private AirdropShareDto createAirdropInternal(
            String actorUserId,
            String sourceSpaceId,
            Long membershipEpoch,
            List<String> fileIds,
            List<String> recipientUserIds,
            AirdropExpiryPreset expiryPreset,
            Integer maxDownloadsPerRecipient,
            String note) {
        if (sourceSpaceId != null) {
            spaceAuth.requireMembership(
                    sourceSpaceId, actorUserId, membershipEpoch, SpaceRole.MEMBER);
        }

        List<FileRow> targetFiles = new ArrayList<>(fileIds.size());
        if (sourceSpaceId != null) {
            List<FileRow> sourceFiles = new ArrayList<>(fileIds.size());
            long extraBytes = 0L;
            for (String fid : fileIds) {
                FileRow source = loadFileRow(fid, true);
                if (source.scope() != GrantType.SPACE_FILE
                        || !sourceSpaceId.equals(source.spaceId())) {
                    throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
                }
                if (source.lifecycleState() != FileLifecycleState.ACTIVE
                        || !isImmutableObjectLive(source.objectKey())) {
                    throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                }
                sourceFiles.add(source);
                extraBytes = Math.addExact(extraBytes, source.sizeBytes());
            }
            QuotaReservation reservation =
                    reserveQuota(GrantType.AIRDROP_SHARE, actorUserId, actorUserId, extraBytes);
            consumeQuotaReservationAndCharge(
                    reservation.reservationId(), GrantType.AIRDROP_SHARE, actorUserId, extraBytes);

            for (FileRow src : sourceFiles) {
                String clonedFileId = UUID.randomUUID().toString();
                db.update(
                        """
                        INSERT INTO files(
                            id, scope, space_id, owner_user_id, file_name, content_type,
                            size_bytes, sha256_hex, object_key, lifecycle_state, lifecycle_version,
                            thumbnail_ready
                        )
                        VALUES (?, 'AIRDROP_SHARE', NULL, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, FALSE)
                        """,
                        clonedFileId,
                        actorUserId,
                        src.fileName(),
                        src.contentType(),
                        src.sizeBytes(),
                        src.sha256(),
                        src.objectKey());
                pinStorageReference("FILE_CONTENT", clonedFileId, src.objectKey());
                targetFiles.add(loadFileRow(clonedFileId, false));
            }
        } else {
            for (String fid : fileIds) {
                FileRow file = loadFileRow(fid, true);
                if (file.scope() != GrantType.AIRDROP_SHARE
                        || !file.ownerUserId().equals(actorUserId)) {
                    throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
                }
                if (file.lifecycleState() != FileLifecycleState.ACTIVE
                        || !isImmutableObjectLive(file.objectKey())) {
                    throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                }
                targetFiles.add(file);
            }
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt =
                switch (expiryPreset) {
                    case H24 -> now.plus(24, ChronoUnit.HOURS);
                    case D7 -> now.plus(7, ChronoUnit.DAYS);
                    case PERMANENT -> null;
                };
        String title =
                targetFiles.size() == 1
                        ? targetFiles.getFirst().fileName()
                        : targetFiles.getFirst().fileName()
                                + " 等 "
                                + targetFiles.size()
                                + " 个文件";

        String shareId = UUID.randomUUID().toString();
        db.update(
                """
                INSERT INTO file_shares(
                    share_id, sender_user_id, source_space_id, title, note,
                    expiry_preset, expires_at, max_downloads_per_recipient, revoked
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                """,
                shareId,
                actorUserId,
                sourceSpaceId,
                title,
                note,
                expiryPreset.name(),
                expiresAt != null ? Timestamp.from(expiresAt) : null,
                maxDownloadsPerRecipient);

        for (int i = 0; i < targetFiles.size(); i++) {
            db.update(
                    "INSERT INTO file_share_files(share_id, file_id, sort_order) VALUES (?, ?, ?)",
                    shareId,
                    targetFiles.get(i).fileId(),
                    i);
        }
        for (String recipientId : recipientUserIds) {
            db.update(
                    """
                    INSERT INTO file_share_recipients(
                        share_id, recipient_user_id, delivery_state, download_count, revoked
                    )
                    VALUES (?, ?, 'DELIVERED', 0, FALSE)
                    """,
                    shareId,
                    recipientId);
        }

        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        "AIRDROP_CREATE",
                        shareId,
                        "SUCCESS",
                        Instant.now()));

        return buildAirdropShareDto(loadShare(shareId, false), actorUserId);
    }

    @Transactional(readOnly = true)
    public AirdropShareListResponse listAirdrops(String actorUserId, AirdropRoleFilter role) {
        AirdropRoleFilter filter = role == null ? AirdropRoleFilter.ALL : role;
        String sql =
                switch (filter) {
                    case SENT ->
                            """
                            SELECT share_id, sender_user_id, source_space_id, title, note,
                                   expiry_preset, expires_at, max_downloads_per_recipient,
                                   revoked, created_at
                            FROM file_shares
                            WHERE sender_user_id = ?
                            ORDER BY created_at DESC, share_id ASC
                            """;
                    case RECEIVED ->
                            """
                            SELECT s.share_id, s.sender_user_id, s.source_space_id, s.title, s.note,
                                   s.expiry_preset, s.expires_at, s.max_downloads_per_recipient,
                                   s.revoked, s.created_at
                            FROM file_shares s
                            JOIN file_share_recipients r ON r.share_id = s.share_id
                            WHERE r.recipient_user_id = ?
                            ORDER BY s.created_at DESC, s.share_id ASC
                            """;
                    case ALL ->
                            """
                            SELECT DISTINCT s.share_id, s.sender_user_id, s.source_space_id, s.title, s.note,
                                   s.expiry_preset, s.expires_at, s.max_downloads_per_recipient,
                                   s.revoked, s.created_at
                            FROM file_shares s
                            LEFT JOIN file_share_recipients r ON r.share_id = s.share_id
                            WHERE s.sender_user_id = ? OR r.recipient_user_id = ?
                            ORDER BY s.created_at DESC, s.share_id ASC
                            """;
                };
        List<ShareRow> shares =
                filter == AirdropRoleFilter.ALL
                        ? db.query(sql, this::mapShareRow, actorUserId, actorUserId)
                        : db.query(sql, this::mapShareRow, actorUserId);
        List<AirdropShareDto> dtos = new ArrayList<>(shares.size());
        for (ShareRow share : shares) {
            dtos.add(buildAirdropShareDto(share, actorUserId));
        }
        return new AirdropShareListResponse(List.copyOf(dtos));
    }

    @Transactional
    public AirdropShareDto getAirdrop(String actorUserId, String rawShareId) {
        String shareId = normalizeId(rawShareId);
        ShareRow share = loadShare(shareId, false);
        if (share.senderUserId().equals(actorUserId)) {
            return buildAirdropShareDto(share, actorUserId);
        }
        RecipientRow recipient = findRecipient(shareId, actorUserId, true);
        if (recipient == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (share.revoked() || recipient.revoked()) {
            throw new DomainException(KernelErrorCode.SHARE_REVOKED);
        }
        if (share.expiresAt() != null && !share.expiresAt().isAfter(Instant.now())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        if (recipient.deliveryState() == AirdropDeliveryState.DELIVERED) {
            db.update(
                    """
                    UPDATE file_share_recipients
                    SET delivery_state = 'VIEWED', updated_at = CURRENT_TIMESTAMP(6)
                    WHERE share_id = ? AND recipient_user_id = ? AND delivery_state = 'DELIVERED'
                    """,
                    shareId,
                    actorUserId);
        }
        return buildAirdropShareDto(share, actorUserId);
    }

    @Transactional
    public AirdropShareDto revokeAirdropRecipient(
            String actorUserId,
            String rawShareId,
            String rawRecipientUserId,
            String idempotencyKey) {
        String shareId = normalizeId(rawShareId);
        String recipientUserId = normalizeId(rawRecipientUserId);

        return idempotency.execute(
                        "REVOKE_AIRDROP_RECIPIENT",
                        actorUserId,
                        idempotencyKey,
                        new RevokeRecipientInput(shareId, recipientUserId),
                        200,
                        AirdropShareDto.class,
                        () ->
                                revokeAirdropRecipientInternal(
                                        actorUserId, shareId, recipientUserId))
                .body();
    }

    private AirdropShareDto revokeAirdropRecipientInternal(
            String actorUserId, String shareId, String recipientUserId) {
        ShareRow share = loadShare(shareId, true);
        if (!share.senderUserId().equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        RecipientRow recipient = findRecipient(shareId, recipientUserId, true);
        if (recipient == null) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!recipient.revoked()) {
            db.update(
                    """
                    UPDATE file_share_recipients
                    SET revoked = TRUE, revoked_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
                    WHERE share_id = ? AND recipient_user_id = ?
                    """,
                    shareId,
                    recipientUserId);
        }
        db.update(
                """
                UPDATE download_sessions
                SET revoked_at = CURRENT_TIMESTAMP(6)
                WHERE grant_type = 'AIRDROP_SHARE'
                  AND grant_id = ?
                  AND actor_user_id = ?
                  AND revoked_at IS NULL
                """,
                shareId,
                recipientUserId);

        notifyFileLifecycle(
                share.sourceSpaceId(),
                shareId,
                GrantType.AIRDROP_SHARE,
                ResourceLifecycleState.REVOKED_FOR_ACTOR,
                1L);

        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        "AIRDROP_RECIPIENT_REVOKE",
                        shareId,
                        "SUCCESS",
                        Instant.now()));

        return buildAirdropShareDto(loadShare(shareId, false), actorUserId);
    }

    @Transactional
    public AirdropShareDto revokeAirdropShare(
            String actorUserId, String rawShareId, String idempotencyKey) {
        String shareId = normalizeId(rawShareId);
        return idempotency.execute(
                        "REVOKE_AIRDROP_SHARE",
                        actorUserId,
                        idempotencyKey,
                        new RevokeShareInput(shareId),
                        200,
                        AirdropShareDto.class,
                        () -> revokeAirdropShareInternal(actorUserId, shareId))
                .body();
    }

    private AirdropShareDto revokeAirdropShareInternal(String actorUserId, String shareId) {
        ShareRow share = loadShare(shareId, true);
        if (!share.senderUserId().equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (!share.revoked()) {
            db.update(
                    """
                    UPDATE file_shares
                    SET revoked = TRUE, revoked_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
                    WHERE share_id = ?
                    """,
                    shareId);
        }
        db.update(
                """
                UPDATE download_sessions
                SET revoked_at = CURRENT_TIMESTAMP(6)
                WHERE grant_type = 'AIRDROP_SHARE' AND grant_id = ? AND revoked_at IS NULL
                """,
                shareId);

        notifyFileLifecycle(
                share.sourceSpaceId(),
                shareId,
                GrantType.AIRDROP_SHARE,
                ResourceLifecycleState.EXPIRED_OR_DELETED,
                1L);

        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        "AIRDROP_SHARE_REVOKE",
                        shareId,
                        "SUCCESS",
                        Instant.now()));

        return buildAirdropShareDto(loadShare(shareId, false), actorUserId);
    }

    @Transactional
    public DownloadSessionDto createDownloadSession(
            String actorUserId,
            Long membershipEpoch,
            String idempotencyKey,
            CreateDownloadSessionRequest req) {
        String grantId = normalizeId(req.grantId());
        String fileId = normalizeId(req.fileId());
        return idempotency.execute(
                        "CREATE_DOWNLOAD_SESSION",
                        actorUserId,
                        idempotencyKey,
                        new CreateDownloadInput(req.grantType(), grantId, fileId),
                        201,
                        DownloadSessionDto.class,
                        () ->
                                createDownloadSessionInternal(
                                        actorUserId, membershipEpoch, req.grantType(), grantId, fileId))
                .body();
    }

    private DownloadSessionDto createDownloadSessionInternal(
            String actorUserId,
            Long membershipEpoch,
            GrantType grantType,
            String grantId,
            String fileId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plus(DOWNLOAD_SESSION_TTL);
        Integer remainingDownloads = null;
        FileRow file;
        Long recordedEpoch = null;

        if (grantType == GrantType.SPACE_FILE) {
            if (membershipEpoch == null) {
                throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
            }
            spaceAuth.requireMembership(grantId, actorUserId, membershipEpoch, SpaceRole.VIEWER);
            file = loadFileRow(fileId, false);
            if (file.scope() != GrantType.SPACE_FILE || !grantId.equals(file.spaceId())) {
                throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
            }
            if (file.lifecycleState() != FileLifecycleState.ACTIVE
                    || !isImmutableObjectLive(file.objectKey())) {
                throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
            }
            recordedEpoch = membershipEpoch;
        } else {
            ShareRow share = loadShare(grantId, true);
            boolean linked =
                    Boolean.TRUE.equals(
                            db.queryForObject(
                                    """
                                    SELECT EXISTS(
                                        SELECT 1 FROM file_share_files WHERE share_id = ? AND file_id = ?
                                    )
                                    """,
                                    Boolean.class,
                                    grantId,
                                    fileId));
            if (!linked) {
                throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
            }
            RecipientRow recipient = findRecipient(grantId, actorUserId, true);
            if (recipient == null) {
                throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
            }
            if (share.revoked() || recipient.revoked()) {
                throw new DomainException(KernelErrorCode.SHARE_REVOKED);
            }
            if (share.expiresAt() != null && !share.expiresAt().isAfter(now)) {
                throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
            }
            file = loadFileRow(fileId, false);
            if (file.lifecycleState() != FileLifecycleState.ACTIVE
                    || !isImmutableObjectLive(file.objectKey())) {
                throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
            }
            if (share.maxDownloadsPerRecipient() != null
                    && recipient.downloadCount() >= share.maxDownloadsPerRecipient()) {
                throw new DomainException(KernelErrorCode.DOWNLOAD_LIMIT_EXCEEDED);
            }
            int updatedCount = recipient.downloadCount() + 1;
            db.update(
                    """
                    UPDATE file_share_recipients
                    SET download_count = download_count + 1,
                        delivery_state = 'DOWNLOAD_STARTED',
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE share_id = ? AND recipient_user_id = ?
                    """,
                    grantId,
                    actorUserId);
            if (share.maxDownloadsPerRecipient() != null) {
                remainingDownloads = Math.max(0, share.maxDownloadsPerRecipient() - updatedCount);
            }
            if (share.expiresAt() != null && share.expiresAt().isBefore(expiresAt)) {
                expiresAt = share.expiresAt();
            }
        }

        String downloadSessionId = UUID.randomUUID().toString();
        db.update(
                """
                INSERT INTO download_sessions(
                    download_session_id, actor_user_id, grant_type, grant_id, file_id,
                    space_membership_epoch, bytes_sent, expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 0, ?)
                """,
                downloadSessionId,
                actorUserId,
                grantType.name(),
                grantId,
                fileId,
                recordedEpoch,
                Timestamp.from(expiresAt));

        auditLog.append(
                new AuditLogPort.Entry(
                        requestIdFromMdc(),
                        actorUserId,
                        "DOWNLOAD_SESSION_CREATE",
                        downloadSessionId,
                        "SUCCESS",
                        Instant.now()));

        return new DownloadSessionDto(
                downloadSessionId,
                grantType,
                grantId,
                file.fileId(),
                file.fileName(),
                file.contentType(),
                file.sizeBytes(),
                file.sha256(),
                remainingDownloads,
                expiresAt,
                "/api/v1/download-sessions/" + downloadSessionId + "/content");
    }

    public OpenedDownloadContent openDownloadContent(
            String actorUserId,
            String rawDownloadSessionId,
            Long headerEpoch,
            String rangeHeader) {
        String downloadSessionId = normalizeId(rawDownloadSessionId);
        DownloadSessionRow session = loadDownloadSession(downloadSessionId);
        FileRow file = verifyDownloadSessionLive(session, actorUserId, headerEpoch);

        long totalSize = file.sizeBytes();
        long startByte = 0L;
        long endByte = totalSize > 0 ? totalSize - 1 : -1L;
        boolean partial = false;

        if (rangeHeader != null && !rangeHeader.isBlank()) {
            long[] parsed = parseByteRange(rangeHeader, totalSize);
            startByte = parsed[0];
            endByte = parsed[1];
            partial = true;
        }

        long finalStart = startByte;
        long finalLength = totalSize == 0 ? 0L : (endByte - startByte + 1);
        InputStream rawStream = null;
        try {
            rawStream =
                    objectStorage.openRead(
                            file.objectKey(),
                            () -> verifyDownloadSessionLive(
                                    loadDownloadSession(downloadSessionId),
                                    actorUserId,
                                    headerEpoch));
            if (finalStart > 0) {
                rawStream.skipNBytes(finalStart);
            }
        } catch (IOException ex) {
            if (rawStream != null) {
                try {
                    rawStream.close();
                } catch (IOException ignored) {
                }
            }
            throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        } catch (RuntimeException ex) {
            if (rawStream != null) {
                try {
                    rawStream.close();
                } catch (IOException ignored) {
                }
            }
            throw ex;
        }

        InputStream boundedStream =
                totalSize == 0
                        ? InputStream.nullInputStream()
                        : new BoundedInputStream(rawStream, finalLength);

        return new OpenedDownloadContent(session, file, startByte, endByte, partial, boundedStream);
    }

    void recheckDownloadAccess(String downloadSessionId, String actorUserId, Long headerEpoch) {
        verifyDownloadSessionLive(loadDownloadSession(downloadSessionId), actorUserId, headerEpoch);
    }

    public void recordDownloadBytesSent(String downloadSessionId, long bytesCount) {
        if (bytesCount <= 0) {
            return;
        }
        db.update(
                """
                UPDATE download_sessions
                SET bytes_sent = bytes_sent + ?
                WHERE download_session_id = ?
                """,
                bytesCount,
                downloadSessionId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public QuotaReservation reserveQuota(
            GrantType grantType, String ownerScopeId, String actorUserId, long bytes) {
        if (grantType == null
                || ownerScopeId == null
                || ownerScopeId.isBlank()
                || actorUserId == null
                || actorUserId.isBlank()
                || bytes < 0) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        long quotaLimit;
        long usedBytes;
        if (grantType == GrantType.SPACE_FILE) {
            SpaceQuotaRow space =
                    db.query(
                                    """
                                    SELECT used_bytes, quota_bytes, status
                                    FROM spaces
                                    WHERE id = ?
                                    FOR UPDATE
                                    """,
                                    (rs, i) -> {
                                        if (!"ACTIVE".equals(rs.getString(3))) {
                                            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
                                        }
                                        return new SpaceQuotaRow(rs.getLong(1), rs.getLong(2));
                                    },
                                    ownerScopeId)
                            .stream()
                            .findFirst()
                            .orElseThrow(() -> new DomainException(KernelErrorCode.AUTH_FORBIDDEN));
            usedBytes = space.usedBytes();
            quotaLimit = space.quotaBytes();
        } else {
            db.update(
                    """
                    INSERT INTO user_storage_quotas(user_id, quota_bytes, used_bytes)
                    VALUES (?, ?, 0)
                    ON DUPLICATE KEY UPDATE user_id = user_id
                    """,
                    ownerScopeId,
                    defaultUserQuotaBytes);
            SpaceQuotaRow personal =
                    db.queryForObject(
                            """
                            SELECT used_bytes, quota_bytes
                            FROM user_storage_quotas
                            WHERE user_id = ?
                            FOR UPDATE
                            """,
                            (rs, i) -> new SpaceQuotaRow(rs.getLong(1), rs.getLong(2)),
                            ownerScopeId);
            usedBytes = personal != null ? personal.usedBytes() : 0L;
            quotaLimit = personal != null ? personal.quotaBytes() : defaultUserQuotaBytes;
        }

        Long activeReserved =
                db.queryForObject(
                        """
                        SELECT COALESCE(SUM(reserved_bytes), 0)
                        FROM quota_reservations
                        WHERE grant_type = ?
                          AND owner_scope_id = ?
                          AND status = 'ACTIVE'
                          AND expires_at > CURRENT_TIMESTAMP(6)
                        """,
                        Long.class,
                        grantType.name(),
                        ownerScopeId);
        long reservedTotal = activeReserved != null ? activeReserved : 0L;
        if (usedBytes + reservedTotal > quotaLimit
                || bytes > quotaLimit - (usedBytes + reservedTotal)) {
            throw new DomainException(KernelErrorCode.QUOTA_EXCEEDED);
        }

        String reservationId = UUID.randomUUID().toString();
        Instant expiresAt =
                Instant.now().plus(UPLOAD_SESSION_TTL).truncatedTo(ChronoUnit.MICROS);
        db.update(
                """
                INSERT INTO quota_reservations(
                    reservation_id, grant_type, owner_scope_id, actor_user_id,
                    reserved_bytes, status, expires_at
                )
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                """,
                reservationId,
                grantType.name(),
                ownerScopeId,
                actorUserId,
                bytes,
                Timestamp.from(expiresAt));
        return new QuotaReservation(reservationId, grantType, ownerScopeId, bytes);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void releaseQuota(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            return;
        }
        db.update(
                """
                UPDATE quota_reservations
                SET status = 'RELEASED'
                WHERE reservation_id = ? AND status = 'ACTIVE'
                """,
                reservationId);
    }

    @Override
    @Transactional(readOnly = true)
    public VerifiedGrant verifyGrant(
            String actorUserId,
            String spaceId,
            Long membershipEpoch,
            GrantType grantType,
            String grantId,
            String fileId) {
        if (grantType == null || grantId == null || fileId == null || actorUserId == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (grantType == GrantType.SPACE_FILE) {
            if (membershipEpoch == null) {
                throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
            }
            String effectiveSpaceId = spaceId != null ? spaceId : grantId;
            if (!grantId.equals(effectiveSpaceId)) {
                throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
            }
            spaceAuth.requireMembership(grantId, actorUserId, membershipEpoch, SpaceRole.VIEWER);
            FileRow file = loadFileRow(fileId, false);
            if (file.scope() != GrantType.SPACE_FILE || !grantId.equals(file.spaceId())) {
                throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
            }
            if (file.lifecycleState() != FileLifecycleState.ACTIVE
                    || !isImmutableObjectLive(file.objectKey())) {
                throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
            }
            return new VerifiedGrant(
                    GrantType.SPACE_FILE,
                    grantId,
                    file.fileId(),
                    file.objectKey(),
                    file.fileName(),
                    file.contentType(),
                    file.sizeBytes(),
                    file.sha256());
        }

        ShareRow share = loadShare(grantId, false);
        RecipientRow recipient = findRecipient(grantId, actorUserId, false);
        if (recipient == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (share.revoked() || recipient.revoked()) {
            throw new DomainException(KernelErrorCode.SHARE_REVOKED);
        }
        if (share.expiresAt() != null && !share.expiresAt().isAfter(Instant.now())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        boolean linked =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM file_share_files WHERE share_id = ? AND file_id = ?
                                )
                                """,
                                Boolean.class,
                                grantId,
                                fileId));
        if (!linked) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }
        FileRow file = loadFileRow(fileId, false);
        if (file.lifecycleState() != FileLifecycleState.ACTIVE
                || !isImmutableObjectLive(file.objectKey())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        return new VerifiedGrant(
                GrantType.AIRDROP_SHARE,
                grantId,
                file.fileId(),
                file.objectKey(),
                file.fileName(),
                file.contentType(),
                file.sizeBytes(),
                file.sha256());
    }

    @Override
    public void onRevoked(Revocation revocation) {
        db.update(
                """
                UPDATE download_sessions
                SET revoked_at = CURRENT_TIMESTAMP(6)
                WHERE grant_type = 'SPACE_FILE'
                  AND grant_id = ?
                  AND actor_user_id = ?
                  AND (space_membership_epoch IS NULL OR space_membership_epoch <= ?)
                  AND revoked_at IS NULL
                """,
                revocation.spaceId(),
                revocation.userId(),
                revocation.endedMembershipEpoch());

        List<String> reservationIds =
                db.queryForList(
                        """
                        SELECT quota_reservation_id
                        FROM upload_sessions
                        WHERE actor_user_id = ?
                          AND target_scope = 'SPACE_FILE'
                          AND space_id = ?
                          AND status IN ('INITIATED', 'UPLOADING')
                        """,
                        String.class,
                        revocation.userId(),
                        revocation.spaceId());

        db.update(
                """
                UPDATE upload_sessions
                SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP(6)
                WHERE actor_user_id = ?
                  AND target_scope = 'SPACE_FILE'
                  AND space_id = ?
                  AND status IN ('INITIATED', 'UPLOADING')
                """,
                revocation.userId(),
                revocation.spaceId());

        for (String resId : reservationIds) {
            releaseQuota(resId);
        }
    }

    @Override
    public boolean supports(MessageKind kind) {
        return kind == MessageKind.FILE_REF;
    }

    @Override
    @Transactional(readOnly = true)
    public Preview verify(
            String userId, String spaceId, long membershipEpoch, Reference reference) {
        if (reference == null
                || reference.kind() != MessageKind.FILE_REF
                || userId == null
                || spaceId == null) {
            return new Preview(ResourceLifecycleState.REVOKED_FOR_ACTOR, null);
        }
        try {
            spaceAuth.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.VIEWER);
        } catch (DomainException ex) {
            return new Preview(ResourceLifecycleState.REVOKED_FOR_ACTOR, null);
        }

        GrantType grantType =
                reference.grantType() != null ? reference.grantType() : GrantType.SPACE_FILE;
        if (grantType == GrantType.SPACE_FILE) {
            FileRow file =
                    db.query(
                                    fileSelectSql()
                                            + " WHERE id = ? AND scope = 'SPACE_FILE' AND space_id = ?",
                                    this::mapFileRow,
                                    reference.targetId(),
                                    spaceId)
                            .stream()
                            .findFirst()
                            .orElse(null);
            if (file == null) {
                return new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
            }
            return switch (file.lifecycleState()) {
                case ACTIVE ->
                        new Preview(
                                ResourceLifecycleState.ACTIVE,
                                file.fileName() + " (" + formatBytes(file.sizeBytes()) + ")");
                case DELETED -> new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
                case FORCE_PURGED -> new Preview(ResourceLifecycleState.FORCE_PURGED, null);
            };
        }

        // AIRDROP_SHARE: space chat visibility NEVER expands independent airdrop recipient scope.
        ShareRow share =
                db.query(
                                """
                                SELECT share_id, sender_user_id, source_space_id, title, note,
                                       expiry_preset, expires_at, max_downloads_per_recipient,
                                       revoked, created_at
                                FROM file_shares
                                WHERE share_id = ?
                                """,
                                this::mapShareRow,
                                reference.targetId())
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (share == null) {
            return new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
        }
        boolean isSender = share.senderUserId().equals(userId);
        RecipientRow recipient = findRecipient(share.shareId(), userId, false);
        if (!isSender && (recipient == null || recipient.revoked())) {
            return new Preview(ResourceLifecycleState.REVOKED_FOR_ACTOR, null);
        }
        if (share.revoked()
                || (share.expiresAt() != null && !share.expiresAt().isAfter(Instant.now()))) {
            return new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
        }
        List<FileRow> shareFiles = loadShareFiles(share.shareId());
        boolean hasActive =
                shareFiles.stream().anyMatch(f -> f.lifecycleState() == FileLifecycleState.ACTIVE);
        if (!hasActive) {
            boolean anyPurged =
                    shareFiles.stream()
                            .anyMatch(f -> f.lifecycleState() == FileLifecycleState.FORCE_PURGED);
            return new Preview(
                    anyPurged
                            ? ResourceLifecycleState.FORCE_PURGED
                            : ResourceLifecycleState.EXPIRED_OR_DELETED,
                    null);
        }
        return new Preview(ResourceLifecycleState.ACTIVE, "空投文件 · " + share.title());
    }

    private FileRow verifyDownloadSessionLive(
            DownloadSessionRow session, String actorUserId, Long headerEpoch) {
        if (!session.actorUserId().equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (session.revokedAt() != null) {
            throw new DomainException(
                    session.grantType() == GrantType.AIRDROP_SHARE
                            ? KernelErrorCode.SHARE_REVOKED
                            : KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (!session.expiresAt().isAfter(Instant.now())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }

        if (session.grantType() == GrantType.SPACE_FILE) {
            if (session.spaceMembershipEpoch() == null) {
                throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
            }
            if (headerEpoch != null && !headerEpoch.equals(session.spaceMembershipEpoch())) {
                throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
            }
            spaceAuth.requireMembership(
                    session.grantId(),
                    actorUserId,
                    session.spaceMembershipEpoch(),
                    SpaceRole.VIEWER);
            FileRow file = loadFileRow(session.fileId(), false);
            if (file.scope() != GrantType.SPACE_FILE
                    || !session.grantId().equals(file.spaceId())) {
                throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
            }
            if (file.lifecycleState() != FileLifecycleState.ACTIVE
                    || !isImmutableObjectLive(file.objectKey())) {
                throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
            }
            return file;
        }

        ShareRow share = loadShare(session.grantId(), false);
        RecipientRow recipient = findRecipient(session.grantId(), actorUserId, false);
        if (recipient == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (share.revoked() || recipient.revoked()) {
            throw new DomainException(KernelErrorCode.SHARE_REVOKED);
        }
        if (share.expiresAt() != null && !share.expiresAt().isAfter(Instant.now())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        FileRow file = loadFileRow(session.fileId(), false);
        if (file.lifecycleState() != FileLifecycleState.ACTIVE
                || !isImmutableObjectLive(file.objectKey())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        return file;
    }

    private record SpaceQuotaRow(long usedBytes, long quotaBytes) {}

    private record PreparedObject(String objectKey, byte[] headerBytes) {}

    private PreparedObject mergeAndPromoteParts(
            UploadSessionRow session, List<UploadPartRow> parts) {
        try {
            if (parts.size() == 1) {
                UploadPartRow single = parts.getFirst();
                if (single.sizeBytes() != session.expectedSizeBytes()
                        || !single.sha256().equals(session.expectedSha256())) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                byte[] headerBytes = readObjectHeaderBytes(single.objectKey());
                FileSecuritySupport.verifyMagicBytes(session.contentType(), headerBytes);
                return new PreparedObject(single.objectKey(), headerBytes);
            }

            List<InputStream> streams = new ArrayList<>(parts.size());
            try {
                for (UploadPartRow part : parts) {
                    streams.add(objectStorage.openRead(part.objectKey(), () -> {}));
                }
                Enumeration<InputStream> enumeration = Collections.enumeration(streams);
                try (SequenceInputStream seq = new SequenceInputStream(enumeration)) {
                    HeaderCapturingInputStream capturing = new HeaderCapturingInputStream(seq);
                    ObjectStoragePort.StoredObject temp =
                            objectStorage.writeTemporary(
                                    "file-merge:" + session.uploadId(),
                                    capturing,
                                    session.expectedSizeBytes());
                    if (temp.sizeBytes() != session.expectedSizeBytes()
                            || !temp.sha256().equals(session.expectedSha256())) {
                        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                    }
                    byte[] header = capturing.capturedHeader();
                    FileSecuritySupport.verifyMagicBytes(session.contentType(), header);
                    ObjectStoragePort.StoredObject promoted =
                            objectStorage.promote(
                                    temp.objectKey(), temp.sizeBytes(), temp.sha256());
                    return new PreparedObject(promoted.objectKey(), header);
                }
            } finally {
                for (InputStream in : streams) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private byte[] readObjectHeaderBytes(String objectKey) {
        try (InputStream in = objectStorage.openRead(objectKey, () -> {})) {
            return in.readNBytes(HEADER_SNIFF_LIMIT);
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
    }

    private void consumeQuotaReservationAndCharge(
            String reservationId, GrantType grantType, String ownerScopeId, long bytes) {
        int consumed =
                db.update(
                        """
                        UPDATE quota_reservations
                        SET status = 'CONSUMED'
                        WHERE reservation_id = ? AND status = 'ACTIVE'
                        """,
                        reservationId);
        if (consumed != 1) {
            throw new DomainException(KernelErrorCode.QUOTA_EXCEEDED);
        }
        if (grantType == GrantType.SPACE_FILE) {
            int updated =
                    db.update(
                            """
                            UPDATE spaces
                            SET used_bytes = used_bytes + ?
                            WHERE id = ? AND status = 'ACTIVE' AND used_bytes + ? <= quota_bytes
                            """,
                            bytes,
                            ownerScopeId,
                            bytes);
            if (updated != 1) {
                throw new DomainException(KernelErrorCode.QUOTA_EXCEEDED);
            }
        } else {
            int updated =
                    db.update(
                            """
                            UPDATE user_storage_quotas
                            SET used_bytes = used_bytes + ?, updated_at = CURRENT_TIMESTAMP(6)
                            WHERE user_id = ? AND used_bytes + ? <= quota_bytes
                            """,
                            bytes,
                            ownerScopeId,
                            bytes);
            if (updated != 1) {
                throw new DomainException(KernelErrorCode.QUOTA_EXCEEDED);
            }
        }
    }

    private String findUserScopedInstantMatch(
            String actorUserId, String sha256, long sizeBytes) {
        return db.queryForList(
                        """
                        SELECT f.id
                        FROM files f
                        JOIN storage_objects o ON o.object_key = f.object_key
                        LEFT JOIN space_members sm
                          ON f.scope = 'SPACE_FILE'
                         AND sm.space_id = f.space_id
                         AND sm.user_id = f.owner_user_id
                        LEFT JOIN spaces sp
                          ON f.scope = 'SPACE_FILE'
                         AND sp.id = f.space_id
                        WHERE f.owner_user_id = ?
                          AND f.sha256_hex = ?
                          AND f.size_bytes = ?
                          AND f.lifecycle_state = 'ACTIVE'
                          AND o.status = 'IMMUTABLE'
                          AND (
                               f.scope = 'AIRDROP_SHARE'
                               OR (f.scope = 'SPACE_FILE' AND sm.status = 'ACTIVE' AND sp.status = 'ACTIVE')
                          )
                        ORDER BY f.created_at DESC, f.id ASC
                        LIMIT 1
                        """,
                        String.class,
                        actorUserId,
                        sha256,
                        sizeBytes)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void validateUploadSessionWritable(UploadSessionRow session, String actorUserId) {
        if (!session.actorUserId().equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (session.targetScope() == GrantType.SPACE_FILE) {
            spaceAuth.requireMembership(
                    session.spaceId(), actorUserId, session.membershipEpoch(), SpaceRole.MEMBER);
        }
        if (session.status() != UploadSessionStatus.INITIATED
                && session.status() != UploadSessionStatus.UPLOADING) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        if (!session.expiresAt().isAfter(Instant.now())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
    }

    private static long expectedPartSize(UploadSessionRow session, int partNumber) {
        if (session.expectedSizeBytes() == 0) {
            return 0L;
        }
        if (partNumber < session.totalParts()) {
            return session.partSizeBytes();
        }
        return session.expectedSizeBytes() - (long) (session.totalParts() - 1) * session.partSizeBytes();
    }

    private void pinStorageReference(String ownerType, String ownerId, String objectKey) {
        boolean exists =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM storage_object_references
                                    WHERE owner_type = ? AND owner_id = ? AND object_key = ?
                                )
                                """,
                                Boolean.class,
                                ownerType,
                                ownerId,
                                objectKey));
        if (!exists) {
            db.update(
                    """
                    INSERT INTO storage_object_references(owner_type, owner_id, object_key)
                    VALUES (?, ?, ?)
                    """,
                    ownerType,
                    ownerId,
                    objectKey);
        }
    }

    private void detachUploadPartReferences(String uploadId, int totalParts) {
        if (totalParts <= 0) {
            return;
        }
        List<String> ownerIds = new ArrayList<>(totalParts);
        for (int p = 1; p <= totalParts; p++) {
            ownerIds.add(uploadPartOwnerId(uploadId, p));
        }
        String placeholders = String.join(",", Collections.nCopies(ownerIds.size(), "?"));
        db.update(
                "DELETE FROM storage_object_references WHERE owner_type = 'UPLOAD_PART' AND owner_id IN ("
                        + placeholders
                        + ")",
                ownerIds.toArray());
    }

    private void cancelActiveFileJobs(String fileId) {
        List<String> activeJobIds =
                db.queryForList(
                        """
                        SELECT job_id
                        FROM async_jobs
                        WHERE resource_id = ?
                          AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                        """,
                        String.class,
                        fileId);
        for (String jobId : activeJobIds) {
            asyncJobs.cancel(jobId);
        }
    }

    private void notifyFileLifecycle(
            String spaceId,
            String targetId,
            GrantType grantType,
            ResourceLifecycleState state,
            long lifecycleVersion) {
        if (spaceId == null || spaceId.isBlank()) {
            return;
        }
        ResourceLifecycleHook.Change change =
                new ResourceLifecycleHook.Change(
                        spaceId,
                        new ResourceCardVerifierPort.Reference(
                                MessageKind.FILE_REF, null, targetId, grantType),
                        state,
                        lifecycleVersion);
        for (ResourceLifecycleHook hook : lifecycleHooks) {
            hook.onLifecycleChanged(change);
        }
    }

    private AirdropShareDto buildAirdropShareDto(ShareRow share, String viewerUserId) {
        List<AirdropFileItem> files =
                loadShareFiles(share.shareId()).stream().map(FileRow::toAirdropFileItem).toList();
        List<RecipientRow> recipientRows = loadRecipients(share.shareId());
        boolean isSender = share.senderUserId().equals(viewerUserId);
        List<AirdropRecipientState> visibleRecipients =
                recipientRows.stream()
                        .filter(r -> isSender || r.recipientUserId().equals(viewerUserId))
                        .map(r -> r.toDto(share.maxDownloadsPerRecipient()))
                        .toList();
        return new AirdropShareDto(
                share.shareId(),
                share.senderUserId(),
                share.title(),
                share.expiryPreset(),
                share.expiresAt(),
                share.maxDownloadsPerRecipient(),
                share.revoked(),
                share.createdAt(),
                files,
                visibleRecipients);
    }

    private UploadSessionDto toUploadSessionDto(
            UploadSessionRow row, List<Integer> uploadedParts) {
        return new UploadSessionDto(
                row.uploadId(),
                row.status(),
                row.targetScope(),
                row.spaceId(),
                row.fileName(),
                row.contentType(),
                row.expectedSizeBytes(),
                row.expectedSha256(),
                row.partSizeBytes(),
                row.totalParts(),
                uploadedParts,
                row.matchedFileId() != null,
                row.matchedFileId(),
                row.expiresAt());
    }

    private UploadSessionRow loadUploadSession(String uploadId, boolean forUpdate) {
        String sql =
                """
                SELECT upload_id, actor_user_id, target_scope, space_id, membership_epoch,
                       file_name, content_type, expected_size_bytes, expected_sha256,
                       part_size_bytes, total_parts, status, matched_file_id,
                       completed_file_id, quota_reservation_id, expires_at
                FROM upload_sessions
                WHERE upload_id = ?
                """
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(sql, this::mapUploadSessionRow, uploadId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private UploadSessionRow mapUploadSessionRow(ResultSet rs, int i) throws SQLException {
        long rawEpoch = rs.getLong(5);
        Long epoch = rs.wasNull() ? null : rawEpoch;
        return new UploadSessionRow(
                rs.getString(1),
                rs.getString(2),
                GrantType.valueOf(rs.getString(3)),
                rs.getString(4),
                epoch,
                rs.getString(6),
                rs.getString(7),
                rs.getLong(8),
                rs.getString(9),
                rs.getLong(10),
                rs.getInt(11),
                UploadSessionStatus.valueOf(rs.getString(12)),
                rs.getString(13),
                rs.getString(14),
                rs.getString(15),
                rs.getTimestamp(16).toInstant());
    }

    private List<Integer> loadUploadedPartNumbers(String uploadId) {
        return db.queryForList(
                "SELECT part_number FROM upload_parts WHERE upload_id = ? ORDER BY part_number ASC",
                Integer.class,
                uploadId);
    }

    private List<UploadPartRow> loadUploadParts(String uploadId) {
        return db.query(
                """
                SELECT part_number, size_bytes, sha256_hex, object_key
                FROM upload_parts
                WHERE upload_id = ?
                ORDER BY part_number ASC
                """,
                (rs, i) ->
                        new UploadPartRow(
                                rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                uploadId);
    }

    private UploadPartRow findUploadPart(String uploadId, int partNumber, boolean forUpdate) {
        String sql =
                """
                SELECT part_number, size_bytes, sha256_hex, object_key
                FROM upload_parts
                WHERE upload_id = ? AND part_number = ?
                """
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(
                        sql,
                        (rs, i) ->
                                new UploadPartRow(
                                        rs.getInt(1),
                                        rs.getLong(2),
                                        rs.getString(3),
                                        rs.getString(4)),
                        uploadId,
                        partNumber)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private FileRow loadFileRow(String fileId, boolean forUpdate) {
        String sql = fileSelectSql() + " WHERE id = ?" + (forUpdate ? " FOR UPDATE" : "");
        return db.query(sql, this::mapFileRow, fileId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private String fileSelectSql() {
        return """
                SELECT id, scope, space_id, owner_user_id, file_name, content_type,
                       size_bytes, sha256_hex, object_key, lifecycle_state, lifecycle_version,
                       thumbnail_job_id, thumbnail_ready, created_at
                FROM files
                """;
    }

    private FileRow mapFileRow(ResultSet rs, int i) throws SQLException {
        return new FileRow(
                rs.getString(1),
                GrantType.valueOf(rs.getString(2)),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getLong(7),
                rs.getString(8),
                rs.getString(9),
                FileLifecycleState.valueOf(rs.getString(10)),
                rs.getLong(11),
                rs.getString(12),
                rs.getBoolean(13),
                rs.getTimestamp(14).toInstant());
    }

    private ShareRow loadShare(String shareId, boolean forUpdate) {
        String sql =
                """
                SELECT share_id, sender_user_id, source_space_id, title, note,
                       expiry_preset, expires_at, max_downloads_per_recipient,
                       revoked, created_at
                FROM file_shares
                WHERE share_id = ?
                """
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(sql, this::mapShareRow, shareId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private ShareRow mapShareRow(ResultSet rs, int i) throws SQLException {
        Timestamp exp = rs.getTimestamp(7);
        int maxDl = rs.getInt(8);
        Integer maxDownloads = rs.wasNull() ? null : maxDl;
        return new ShareRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                AirdropExpiryPreset.valueOf(rs.getString(6)),
                exp == null ? null : exp.toInstant(),
                maxDownloads,
                rs.getBoolean(9),
                rs.getTimestamp(10).toInstant());
    }

    private List<FileRow> loadShareFiles(String shareId) {
        return db.query(
                """
                SELECT f.id, f.scope, f.space_id, f.owner_user_id, f.file_name, f.content_type,
                       f.size_bytes, f.sha256_hex, f.object_key, f.lifecycle_state, f.lifecycle_version,
                       f.thumbnail_job_id, f.thumbnail_ready, f.created_at
                FROM file_share_files sf
                JOIN files f ON f.id = sf.file_id
                WHERE sf.share_id = ?
                ORDER BY sf.sort_order ASC, f.id ASC
                """,
                this::mapFileRow,
                shareId);
    }

    private List<RecipientRow> loadRecipients(String shareId) {
        return db.query(
                """
                SELECT share_id, recipient_user_id, delivery_state, download_count, revoked, updated_at
                FROM file_share_recipients
                WHERE share_id = ?
                ORDER BY recipient_user_id ASC
                """,
                this::mapRecipientRow,
                shareId);
    }

    private RecipientRow findRecipient(String shareId, String recipientUserId, boolean forUpdate) {
        String sql =
                """
                SELECT share_id, recipient_user_id, delivery_state, download_count, revoked, updated_at
                FROM file_share_recipients
                WHERE share_id = ? AND recipient_user_id = ?
                """
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(sql, this::mapRecipientRow, shareId, recipientUserId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private RecipientRow mapRecipientRow(ResultSet rs, int i) throws SQLException {
        return new RecipientRow(
                rs.getString(1),
                rs.getString(2),
                AirdropDeliveryState.valueOf(rs.getString(3)),
                rs.getInt(4),
                rs.getBoolean(5),
                rs.getTimestamp(6).toInstant());
    }

    private DownloadSessionRow loadDownloadSession(String downloadSessionId) {
        return db.query(
                        """
                        SELECT download_session_id, actor_user_id, grant_type, grant_id, file_id,
                               space_membership_epoch, bytes_sent, revoked_at, expires_at
                        FROM download_sessions
                        WHERE download_session_id = ?
                        """,
                        (rs, i) -> {
                            long rawEpoch = rs.getLong(6);
                            Long epoch = rs.wasNull() ? null : rawEpoch;
                            Timestamp rev = rs.getTimestamp(8);
                            return new DownloadSessionRow(
                                    rs.getString(1),
                                    rs.getString(2),
                                    GrantType.valueOf(rs.getString(3)),
                                    rs.getString(4),
                                    rs.getString(5),
                                    epoch,
                                    rs.getLong(7),
                                    rev == null ? null : rev.toInstant(),
                                    rs.getTimestamp(9).toInstant());
                        },
                        downloadSessionId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private boolean isImmutableObjectLive(String objectKey) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(
                            SELECT 1 FROM storage_objects WHERE object_key = ? AND status = 'IMMUTABLE'
                        )
                        """,
                        Boolean.class,
                        objectKey));
    }

    private static long[] parseByteRange(String header, long totalSize) {
        String trimmed = header.strip();
        if (!trimmed.startsWith("bytes=") || trimmed.contains(",") || totalSize <= 0) {
            throw new DomainException(
                    KernelErrorCode.INVALID_REQUEST, Map.of("reason", "RANGE_NOT_SATISFIABLE"));
        }
        String spec = trimmed.substring("bytes=".length()).strip();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String startPart = spec.substring(0, dash).strip();
        String endPart = spec.substring(dash + 1).strip();
        try {
            long start;
            long end;
            if (startPart.isEmpty()) {
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                start = Math.max(0L, totalSize - suffix);
                end = totalSize - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? totalSize - 1 : Math.min(totalSize - 1, Long.parseLong(endPart));
            }
            if (start < 0 || start >= totalSize || end < start) {
                throw new DomainException(
                        KernelErrorCode.INVALID_REQUEST, Map.of("reason", "RANGE_NOT_SATISFIABLE"));
            }
            return new long[] {start, end};
        } catch (NumberFormatException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static String uploadPartOwnerId(String uploadId, int partNumber) {
        return uploadId + ":" + partNumber;
    }

    private static String normalizeId(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 160) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return trimmed;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static String requestIdFromMdc() {
        String id = MDC.get("requestId");
        return id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long remaining) {
            super(in);
            this.remaining = Math.max(0L, remaining);
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int count = super.read(b, off, toRead);
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }
    }
}
