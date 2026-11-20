package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AuditLogPort;
import com.orbitspace.kernel.ContentManifestStatus;
import com.orbitspace.kernel.DataLevel;
import com.orbitspace.kernel.DevicePrincipal;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.ObjectStoragePort;
import com.orbitspace.kernel.ProjectScopePort;
import com.orbitspace.kernel.SpaceAuthorizationPort;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.BackfillCheckRequest;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.BackfillCheckResponse;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.BackfillManifestItem;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.BackfillMissingItem;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.BackfillMissingReason;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.BackfillReceiptDto;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.BackfillSessionDto;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.CompleteBackfillRequest;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.CreateBackfillSessionRequest;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.DiffEndpointKind;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.DiffEndpointRef;
import com.orbitspace.modules.gitproject.GitProjectDtos.ScopeDeclarationDto;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class GitBackfillService {
    private static final Duration BACKFILL_SESSION_TTL = Duration.ofMinutes(15);
    private static final int MAX_BACKFILL_FILES = 512;
    private static final TypeReference<List<BackfillManifestItem>> MANIFEST_LIST_TYPE =
            new TypeReference<>() {};

    private record CompleteBackfillInput(
            String projectId,
            String backfillId,
            String payloadDigest,
            List<String> uploadedPaths) {}

    private record WipCheckRow(
            String id,
            String baseKind,
            String baseCommitOid,
            DataLevel dataLevel,
            String patchObjectKey,
            String lifecycleState) {}

    private record BackfillSessionRow(
            String backfillId,
            String projectId,
            String spaceId,
            String userId,
            String deviceId,
            long membershipEpoch,
            DiffEndpointKind targetKind,
            String targetId,
            String payloadDigest,
            String manifestJson,
            String status,
            long acceptedCount,
            Instant expiresAt,
            Instant completedAt) {}

    private record StagedUploadRow(
            String pathSha256,
            String filePath,
            String tempObjectKey,
            long sizeBytes,
            String sha256Hex) {}

    private record ExistingBackfillObjectRow(
            long id, String objectKey, long sizeBytes, String sha256Hex) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final TransactionTemplate tx;
    private final SpaceAuthorizationPort spaceAuth;
    private final ProjectScopePort projectScope;
    private final ObjectStoragePort objectStorage;
    private final AuditLogPort auditLog;
    private final GitProjectIdempotencyStore idempotency;

    public GitBackfillService(
            JdbcTemplate db,
            ObjectMapper mapper,
            PlatformTransactionManager txManager,
            SpaceAuthorizationPort spaceAuth,
            ProjectScopePort projectScope,
            ObjectStoragePort objectStorage,
            AuditLogPort auditLog,
            GitProjectIdempotencyStore idempotency) {
        this.db = db;
        this.mapper = mapper;
        this.strictMapper = GitProjectUtils.strictMapper(mapper);
        this.tx = new TransactionTemplate(txManager);
        this.spaceAuth = spaceAuth;
        this.projectScope = projectScope;
        this.objectStorage = objectStorage;
        this.auditLog = auditLog;
        this.idempotency = idempotency;
    }

    @Transactional(readOnly = true)
    public BackfillCheckResponse checkBackfill(
            DevicePrincipal device, String projectId, String rawBody) {
        requireDeviceProjectScope(device, projectId, "metadata:write");
        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        BackfillCheckRequest req =
                GitProjectUtils.parseStrict(strictMapper, rawBody, BackfillCheckRequest.class);
        validateEndpointRef(req.baseEndpoint());
        validateEndpointRef(req.targetEndpoint());

        WipCheckRow baseWip = requireEndpointAccessible(device.spaceId(), projectId, req.baseEndpoint());
        WipCheckRow targetWip =
                requireEndpointAccessible(device.spaceId(), projectId, req.targetEndpoint());

        Map<String, String> baseBackfills = loadLiveBackfilledPaths(projectId, req.baseEndpoint());
        Map<String, String> targetBackfills =
                loadLiveBackfilledPaths(projectId, req.targetEndpoint());
        Map<String, ContentManifestStatus> baseManifest =
                baseWip != null ? loadWipManifestStatuses(baseWip.id()) : Map.of();
        Map<String, ContentManifestStatus> targetManifest =
                targetWip != null ? loadWipManifestStatuses(targetWip.id()) : Map.of();

        Set<String> candidatePaths = new TreeSet<>();
        if (req.paths() != null && !req.paths().isEmpty()) {
            for (String path : req.paths()) {
                GitProjectUtils.requireSafeRelativePath(path);
                candidatePaths.add(path);
            }
        } else {
            candidatePaths.addAll(baseManifest.keySet());
            candidatePaths.addAll(targetManifest.keySet());
            candidatePaths.addAll(baseBackfills.keySet());
            candidatePaths.addAll(targetBackfills.keySet());
        }

        boolean sameEndpoint =
                req.baseEndpoint().kind() == req.targetEndpoint().kind()
                        && req.baseEndpoint().id().equals(req.targetEndpoint().id());
        boolean directTargetOnBase =
                req.baseEndpoint().kind() == DiffEndpointKind.COMMIT
                        && targetWip != null
                        && "COMMIT".equals(targetWip.baseKind())
                        && req.baseEndpoint().id().equals(targetWip.baseCommitOid())
                        && targetWip.dataLevel() == DataLevel.PATCH_ONLY
                        && targetWip.patchObjectKey() != null;
        boolean directBaseOnTarget =
                req.targetEndpoint().kind() == DiffEndpointKind.COMMIT
                        && baseWip != null
                        && "COMMIT".equals(baseWip.baseKind())
                        && req.targetEndpoint().id().equals(baseWip.baseCommitOid())
                        && baseWip.dataLevel() == DataLevel.PATCH_ONLY
                        && baseWip.patchObjectKey() != null;

        List<BackfillMissingItem> missingItems = new ArrayList<>();
        List<String> unsupportedPaths = new ArrayList<>();

        for (String path : candidatePaths) {
            if (!GitProjectUtils.isSafeRelativePath(path)) {
                continue;
            }
            ContentManifestStatus baseMs = baseManifest.get(path);
            ContentManifestStatus targetMs = targetManifest.get(path);
            if (baseMs == ContentManifestStatus.UNSUPPORTED
                    || targetMs == ContentManifestStatus.UNSUPPORTED) {
                unsupportedPaths.add(path);
                continue;
            }

            BackfillMissingReason baseMissing =
                    evaluateMissingReason(
                            req.baseEndpoint(), path, baseBackfills, baseMs, directBaseOnTarget);
            if (baseMissing != null) {
                missingItems.add(
                        new BackfillMissingItem(
                                path,
                                req.baseEndpoint().kind(),
                                req.baseEndpoint().id(),
                                null,
                                baseMissing));
            }

            if (!sameEndpoint) {
                BackfillMissingReason targetMissing =
                        evaluateMissingReason(
                                req.targetEndpoint(),
                                path,
                                targetBackfills,
                                targetMs,
                                directTargetOnBase);
                if (targetMissing != null) {
                    missingItems.add(
                            new BackfillMissingItem(
                                    path,
                                    req.targetEndpoint().kind(),
                                    req.targetEndpoint().id(),
                                    null,
                                    targetMissing));
                }
            }
        }

        return new BackfillCheckResponse(
                projectId, List.copyOf(missingItems), List.copyOf(unsupportedPaths));
    }

    @Transactional
    public BackfillSessionDto createBackfillSession(
            DevicePrincipal device, String projectId, String idempotencyKey, String rawBody) {
        requireDeviceProjectScope(device, projectId, "patch:write");
        GitProjectUtils.normalizeKey64(idempotencyKey);
        if (rawBody == null || rawBody.isBlank()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        JsonNode root;
        CreateBackfillSessionRequest req;
        try {
            root = strictMapper.readTree(rawBody);
            if (root == null || !root.isObject()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            req = strictMapper.treeToValue(root, CreateBackfillSessionRequest.class);
        } catch (JsonProcessingException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        String payloadDigest = GitProjectUtils.sha256Hex(GitProjectUtils.canonicalizeRfc8785(root));
        validateCreateSessionRequest(req);

        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        DiffEndpointRef target = new DiffEndpointRef(req.targetKind(), req.targetId());
        requireEndpointAccessible(device.spaceId(), projectId, target);

        return idempotency.executeWithDigest(
                "CREATE_BACKFILL_SESSION",
                projectId + ":" + device.deviceId(),
                device.userId(),
                idempotencyKey,
                payloadDigest,
                BackfillSessionDto.class,
                () -> {
                    spaceAuth.requireMembership(
                            device.spaceId(),
                            device.userId(),
                            device.membershipEpoch(),
                            SpaceRole.MEMBER);
                    projectScope.requireProjectInSpace(projectId, device.spaceId());
                    requireEndpointAccessible(device.spaceId(), projectId, target);

                    List<String> requiredPaths = new ArrayList<>();
                    for (BackfillManifestItem item : req.items()) {
                        String pathSha = GitProjectUtils.sha256Hex(item.path());
                        if (!hasLiveBackfillObject(
                                projectId,
                                req.targetKind(),
                                req.targetId(),
                                pathSha,
                                item.sizeBytes(),
                                item.sha256())) {
                            requiredPaths.add(item.path());
                        }
                    }

                    String backfillId = GitProjectUtils.newId();
                    Instant expiresAt =
                            Instant.now().plus(BACKFILL_SESSION_TTL).truncatedTo(ChronoUnit.MICROS);

                    db.update(
                            """
                            INSERT INTO git_backfill_sessions(
                                backfill_id, project_id, space_id, user_id, device_id,
                                membership_epoch, target_kind, target_id, payload_digest,
                                scope_declaration_json, manifest_json, status, accepted_count, expires_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), 'OPEN', 0, ?)
                            """,
                            backfillId,
                            projectId,
                            device.spaceId(),
                            device.userId(),
                            device.deviceId(),
                            device.membershipEpoch(),
                            req.targetKind().name(),
                            req.targetId(),
                            payloadDigest,
                            GitProjectUtils.toJson(mapper, req.scopeDeclaration()),
                            GitProjectUtils.toJson(mapper, req.items()),
                            Timestamp.from(expiresAt));

                    auditLog.append(
                            new AuditLogPort.Entry(
                                    GitProjectUtils.requestId(),
                                    device.userId(),
                                    "GIT_BACKFILL_SESSION_CREATE",
                                    backfillId,
                                    "SUCCESS",
                                    Instant.now()));
                    return new BackfillSessionDto(
                            backfillId, List.copyOf(requiredPaths), expiresAt);
                });
    }

    public void uploadBackfillObject(
            DevicePrincipal device,
            String projectId,
            String backfillId,
            String pathParam,
            String sha256Header,
            InputStream bytes) {
        requireDeviceProjectScope(device, projectId, "patch:write");
        GitProjectUtils.requireId(backfillId);
        String expectedSha256 = sha256Header != null ? sha256Header.strip() : null;
        if (!GitProjectUtils.isValidSha256(expectedSha256)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        BackfillSessionRow session = loadAndVerifySession(backfillId, projectId, device, false);
        if ("EXPIRED".equals(session.status())
                || ("OPEN".equals(session.status())
                        && !session.expiresAt().isAfter(Instant.now()))) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        requireEndpointAccessible(
                device.spaceId(),
                projectId,
                new DiffEndpointRef(session.targetKind(), session.targetId()));

        List<BackfillManifestItem> manifest = parseManifest(session.manifestJson());
        BackfillManifestItem targetItem = resolveUploadManifestItem(manifest, pathParam);
        if (!expectedSha256.equals(targetItem.sha256())) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }

        byte[] verifiedBytes = readAndVerifyUploadBytes(bytes, targetItem.sizeBytes(), targetItem.sha256());

        String pathSha256 = GitProjectUtils.sha256Hex(targetItem.path());
        String uploadId = "backfill:" + backfillId + ":" + pathSha256;
        ObjectStoragePort.StoredObject stored;
        try {
            stored =
                    objectStorage.writeTemporary(
                            uploadId,
                            new ByteArrayInputStream(verifiedBytes),
                            targetItem.sizeBytes());
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        if (stored.sizeBytes() != targetItem.sizeBytes()
                || !stored.sha256().equals(targetItem.sha256())) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }

        tx.executeWithoutResult(
                status -> {
                    BackfillSessionRow locked =
                            loadAndVerifySession(backfillId, projectId, device, true);
                    if ("OPEN".equals(locked.status())) {
                        if (!locked.expiresAt().isAfter(Instant.now())) {
                            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                        }
                        db.update(
                                """
                                INSERT INTO git_backfill_staged_uploads(
                                    backfill_id, path_sha256, file_path, temp_object_key, size_bytes, sha256_hex
                                )
                                VALUES (?, ?, ?, ?, ?, ?)
                                ON DUPLICATE KEY UPDATE
                                    temp_object_key = VALUES(temp_object_key),
                                    size_bytes = VALUES(size_bytes),
                                    sha256_hex = VALUES(sha256_hex)
                                """,
                                backfillId,
                                pathSha256,
                                targetItem.path(),
                                stored.objectKey(),
                                stored.sizeBytes(),
                                stored.sha256());
                    } else if ("EXPIRED".equals(locked.status())) {
                        throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                    }
                });
    }

    public BackfillReceiptDto completeBackfillSession(
            DevicePrincipal device,
            String projectId,
            String backfillId,
            String idempotencyKey,
            String rawBody) {
        requireDeviceProjectScope(device, projectId, "patch:write");
        GitProjectUtils.requireId(backfillId);
        GitProjectUtils.normalizeKey64(idempotencyKey);

        CompleteBackfillRequest req =
                GitProjectUtils.parseStrict(strictMapper, rawBody, CompleteBackfillRequest.class);
        if (!GitProjectUtils.isValidSha256(req.payloadDigest()) || req.uploadedPaths() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        Set<String> uploadedSet = new HashSet<>();
        for (String p : req.uploadedPaths()) {
            GitProjectUtils.requireSafeRelativePath(p);
            if (!uploadedSet.add(p)) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }

        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        BackfillSessionRow preSession = loadAndVerifySession(backfillId, projectId, device, false);
        if (!req.payloadDigest().equals(preSession.payloadDigest())) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }

        boolean alreadyCompleted = "COMPLETED".equals(preSession.status());
        Map<String, String> promotedKeysByPath = new HashMap<>();

        if (!alreadyCompleted) {
            if ("EXPIRED".equals(preSession.status())
                    || !preSession.expiresAt().isAfter(Instant.now())) {
                throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
            }
            requireEndpointAccessible(
                    device.spaceId(),
                    projectId,
                    new DiffEndpointRef(preSession.targetKind(), preSession.targetId()));

            List<BackfillManifestItem> manifest = parseManifest(preSession.manifestJson());
            Map<String, BackfillManifestItem> manifestByPath = new LinkedHashMap<>();
            for (BackfillManifestItem item : manifest) {
                manifestByPath.put(item.path(), item);
            }
            Map<String, StagedUploadRow> stagedByPath = loadStagedUploadsByPath(backfillId);

            for (String uploadedPath : req.uploadedPaths()) {
                if (!manifestByPath.containsKey(uploadedPath)
                        || !stagedByPath.containsKey(uploadedPath)) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
            }

            for (BackfillManifestItem item : manifest) {
                StagedUploadRow staged = stagedByPath.get(item.path());
                if (staged != null) {
                    if (staged.sizeBytes() != item.sizeBytes()
                            || !staged.sha256Hex().equals(item.sha256())) {
                        throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                    }
                    try {
                        ObjectStoragePort.StoredObject promoted =
                                objectStorage.promote(
                                        staged.tempObjectKey(), item.sizeBytes(), item.sha256());
                        promotedKeysByPath.put(item.path(), promoted.objectKey());
                    } catch (IOException ex) {
                        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                    }
                } else {
                    String pathSha = GitProjectUtils.sha256Hex(item.path());
                    ExistingBackfillObjectRow existing =
                            findBackfillObject(
                                    projectId,
                                    preSession.targetKind(),
                                    preSession.targetId(),
                                    pathSha,
                                    false);
                    if (existing == null
                            || existing.sizeBytes() != item.sizeBytes()
                            || !existing.sha256Hex().equals(item.sha256())
                            || !isImmutableObjectLive(existing.objectKey())) {
                        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                    }
                    promotedKeysByPath.put(item.path(), existing.objectKey());
                }
            }
        }

        return tx.execute(
                status -> {
                    spaceAuth.requireMembership(
                            device.spaceId(),
                            device.userId(),
                            device.membershipEpoch(),
                            SpaceRole.MEMBER);
                    projectScope.requireProjectInSpace(projectId, device.spaceId());

                    return idempotency.execute(
                            "COMPLETE_BACKFILL",
                            backfillId,
                            device.userId(),
                            idempotencyKey,
                            new CompleteBackfillInput(
                                    projectId,
                                    backfillId,
                                    req.payloadDigest(),
                                    req.uploadedPaths()),
                            BackfillReceiptDto.class,
                            () -> {
                                BackfillSessionRow session =
                                        loadAndVerifySession(backfillId, projectId, device, true);
                                if (!req.payloadDigest().equals(session.payloadDigest())) {
                                    throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                                }
                                if ("COMPLETED".equals(session.status())) {
                                    Instant completedAt =
                                            session.completedAt() != null
                                                    ? session.completedAt()
                                                    : Instant.now().truncatedTo(ChronoUnit.MICROS);
                                    return new BackfillReceiptDto(
                                            session.backfillId(),
                                            projectId,
                                            session.targetKind(),
                                            session.targetId(),
                                            session.acceptedCount(),
                                            completedAt);
                                }
                                if (!"OPEN".equals(session.status())
                                        || !session.expiresAt().isAfter(Instant.now())) {
                                    throw new DomainException(
                                            KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                                }

                                if (session.targetKind() == DiffEndpointKind.WIP) {
                                    lockAndRequireActiveWip(
                                            device.spaceId(), projectId, session.targetId());
                                } else {
                                    requireCommitKnown(projectId, session.targetId());
                                }

                                List<BackfillManifestItem> manifest =
                                        new ArrayList<>(parseManifest(session.manifestJson()));
                                manifest.sort(
                                        Comparator.comparing(
                                                item -> GitProjectUtils.sha256Hex(item.path())));

                                long acceptedCount = 0;
                                for (BackfillManifestItem item : manifest) {
                                    String objectKey = promotedKeysByPath.get(item.path());
                                    if (objectKey == null || !isImmutableObjectLive(objectKey)) {
                                        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                                    }
                                    upsertBackfillObjectAndPinRef(
                                            projectId,
                                            session.targetKind(),
                                            session.targetId(),
                                            backfillId,
                                            item,
                                            objectKey);
                                    if (session.targetKind() == DiffEndpointKind.WIP) {
                                        markWipManifestEntryAvailable(
                                                session.targetId(), item.path(), item.sha256());
                                    }
                                    acceptedCount++;
                                }

                                Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
                                db.update(
                                        """
                                        UPDATE git_backfill_sessions
                                        SET status = 'COMPLETED',
                                            accepted_count = ?,
                                            completed_at = ?
                                        WHERE backfill_id = ?
                                        """,
                                        acceptedCount,
                                        Timestamp.from(completedAt),
                                        backfillId);

                                auditLog.append(
                                        new AuditLogPort.Entry(
                                                GitProjectUtils.requestId(),
                                                device.userId(),
                                                "GIT_BACKFILL_COMPLETE",
                                                backfillId,
                                                "SUCCESS",
                                                Instant.now()));
                                return new BackfillReceiptDto(
                                        backfillId,
                                        projectId,
                                        session.targetKind(),
                                        session.targetId(),
                                        acceptedCount,
                                        completedAt);
                            });
                });
    }

    private BackfillMissingReason evaluateMissingReason(
            DiffEndpointRef endpoint,
            String path,
            Map<String, String> liveBackfills,
            ContentManifestStatus wipManifestStatus,
            boolean coveredByDirectPatch) {
        if (liveBackfills.containsKey(path)) {
            return null;
        }
        if (endpoint.kind() == DiffEndpointKind.COMMIT) {
            return BackfillMissingReason.MISSING;
        }
        if (wipManifestStatus == ContentManifestStatus.ABSENT_CONFIRMED) {
            return null;
        }
        if (wipManifestStatus == ContentManifestStatus.NOT_SHARED) {
            return BackfillMissingReason.NOT_SHARED;
        }
        if (wipManifestStatus == ContentManifestStatus.AVAILABLE && coveredByDirectPatch) {
            return null;
        }
        return BackfillMissingReason.MISSING;
    }

    private void upsertBackfillObjectAndPinRef(
            String projectId,
            DiffEndpointKind targetKind,
            String targetId,
            String backfillId,
            BackfillManifestItem item,
            String objectKey) {
        String pathSha256 = GitProjectUtils.sha256Hex(item.path());
        ExistingBackfillObjectRow existing =
                findBackfillObject(projectId, targetKind, targetId, pathSha256, true);
        if (existing == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            db.update(
                    con -> {
                        PreparedStatement ps =
                                con.prepareStatement(
                                        """
                                        INSERT INTO git_backfill_objects(
                                            project_id, target_kind, target_id, file_path, path_sha256,
                                            file_mode, blob_oid, size_bytes, sha256_hex, object_key, backfill_id
                                        )
                                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                        """,
                                        Statement.RETURN_GENERATED_KEYS);
                        ps.setString(1, projectId);
                        ps.setString(2, targetKind.name());
                        ps.setString(3, targetId);
                        ps.setString(4, item.path());
                        ps.setString(5, pathSha256);
                        ps.setString(6, item.mode());
                        ps.setString(7, item.blobOid());
                        ps.setLong(8, item.sizeBytes());
                        ps.setString(9, item.sha256());
                        ps.setString(10, objectKey);
                        ps.setString(11, backfillId);
                        return ps;
                    },
                    keyHolder);
            Number key = keyHolder.getKey();
            long objId =
                    key != null
                            ? key.longValue()
                            : Objects.requireNonNull(
                                            findBackfillObject(
                                                    projectId,
                                                    targetKind,
                                                    targetId,
                                                    pathSha256,
                                                    false))
                                    .id();
            pinStorageReference("GIT_BACKFILL_OBJECT", Long.toString(objId), objectKey);
            return;
        }

        String ownerId = Long.toString(existing.id());
        pinStorageReference("GIT_BACKFILL_OBJECT", ownerId, objectKey);
        if (!objectKey.equals(existing.objectKey())) {
            db.update(
                    """
                    DELETE FROM storage_object_references
                    WHERE owner_type = 'GIT_BACKFILL_OBJECT' AND owner_id = ? AND object_key = ?
                    """,
                    ownerId,
                    existing.objectKey());
        }
        db.update(
                """
                UPDATE git_backfill_objects
                SET file_mode = ?, blob_oid = ?, size_bytes = ?, sha256_hex = ?,
                    object_key = ?, backfill_id = ?
                WHERE id = ?
                """,
                item.mode(),
                item.blobOid(),
                item.sizeBytes(),
                item.sha256(),
                objectKey,
                backfillId,
                existing.id());
    }

    private void markWipManifestEntryAvailable(String snapshotId, String filePath, String sha256) {
        List<String> existingStatuses =
                db.queryForList(
                        """
                        SELECT status
                        FROM content_manifests
                        WHERE snapshot_id = ? AND file_path = ?
                        """,
                        String.class,
                        snapshotId,
                        filePath);
        if (existingStatuses.isEmpty()) {
            db.update(
                    """
                    INSERT INTO content_manifests(snapshot_id, file_path, status, sha256_hex)
                    VALUES (?, ?, 'AVAILABLE', ?)
                    """,
                    snapshotId,
                    filePath,
                    sha256);
            return;
        }
        db.update(
                """
                UPDATE content_manifests
                SET status = 'AVAILABLE',
                    sha256_hex = ?
                WHERE snapshot_id = ? AND file_path = ?
                  AND status IN ('MISSING', 'NOT_SHARED')
                """,
                sha256,
                snapshotId,
                filePath);
        db.update(
                """
                UPDATE content_manifests
                SET sha256_hex = ?
                WHERE snapshot_id = ? AND file_path = ?
                  AND status = 'AVAILABLE' AND sha256_hex IS NULL
                """,
                sha256,
                snapshotId,
                filePath);
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

    private Map<String, String> loadLiveBackfilledPaths(
            String projectId, DiffEndpointRef endpoint) {
        Map<String, String> map = new HashMap<>();
        db.query(
                """
                SELECT o.file_path, o.sha256_hex
                FROM git_backfill_objects o
                JOIN storage_objects s ON s.object_key = o.object_key
                WHERE o.project_id = ?
                  AND o.target_kind = ?
                  AND o.target_id = ?
                  AND s.status = 'IMMUTABLE'
                """,
                rs -> {
                    map.put(rs.getString(1), rs.getString(2));
                },
                projectId,
                endpoint.kind().name(),
                endpoint.id());
        return map;
    }

    private Map<String, ContentManifestStatus> loadWipManifestStatuses(String snapshotId) {
        Map<String, ContentManifestStatus> map = new LinkedHashMap<>();
        db.query(
                """
                SELECT file_path, status
                FROM content_manifests
                WHERE snapshot_id = ?
                ORDER BY id ASC
                """,
                rs -> {
                    map.put(rs.getString(1), ContentManifestStatus.valueOf(rs.getString(2)));
                },
                snapshotId);
        return map;
    }

    private Map<String, StagedUploadRow> loadStagedUploadsByPath(String backfillId) {
        Map<String, StagedUploadRow> map = new HashMap<>();
        db.query(
                """
                SELECT path_sha256, file_path, temp_object_key, size_bytes, sha256_hex
                FROM git_backfill_staged_uploads
                WHERE backfill_id = ?
                """,
                rs -> {
                    StagedUploadRow row =
                            new StagedUploadRow(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getLong(4),
                                    rs.getString(5));
                    map.put(row.filePath(), row);
                },
                backfillId);
        return map;
    }

    private ExistingBackfillObjectRow findBackfillObject(
            String projectId,
            DiffEndpointKind targetKind,
            String targetId,
            String pathSha256,
            boolean forUpdate) {
        String sql =
                """
                SELECT id, object_key, size_bytes, sha256_hex
                FROM git_backfill_objects
                WHERE project_id = ? AND target_kind = ? AND target_id = ? AND path_sha256 = ?
                """
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(
                        sql,
                        (rs, i) ->
                                new ExistingBackfillObjectRow(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getLong(3),
                                        rs.getString(4)),
                        projectId,
                        targetKind.name(),
                        targetId,
                        pathSha256)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private boolean hasLiveBackfillObject(
            String projectId,
            DiffEndpointKind targetKind,
            String targetId,
            String pathSha256,
            long expectedSize,
            String expectedSha256) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(
                            SELECT 1
                            FROM git_backfill_objects o
                            JOIN storage_objects s ON s.object_key = o.object_key
                            WHERE o.project_id = ?
                              AND o.target_kind = ?
                              AND o.target_id = ?
                              AND o.path_sha256 = ?
                              AND o.size_bytes = ?
                              AND o.sha256_hex = ?
                              AND s.status = 'IMMUTABLE'
                        )
                        """,
                        Boolean.class,
                        projectId,
                        targetKind.name(),
                        targetId,
                        pathSha256,
                        expectedSize,
                        expectedSha256));
    }

    private boolean isImmutableObjectLive(String objectKey) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(
                            SELECT 1 FROM storage_objects
                            WHERE object_key = ? AND status = 'IMMUTABLE'
                        )
                        """,
                        Boolean.class,
                        objectKey));
    }

    private WipCheckRow requireEndpointAccessible(
            String spaceId, String projectId, DiffEndpointRef endpoint) {
        if (endpoint.kind() == DiffEndpointKind.COMMIT) {
            requireCommitKnown(projectId, endpoint.id());
            return null;
        }
        return loadAndRequireActiveWip(spaceId, projectId, endpoint.id(), false);
    }

    private void lockAndRequireActiveWip(String spaceId, String projectId, String snapshotId) {
        loadAndRequireActiveWip(spaceId, projectId, snapshotId, true);
    }

    private WipCheckRow loadAndRequireActiveWip(
            String spaceId, String projectId, String snapshotId, boolean forUpdate) {
        String sql =
                """
                SELECT id, base_kind, base_commit_oid, data_level, patch_object_key, lifecycle_state
                FROM wip_snapshots
                WHERE id = ? AND project_id = ? AND space_id = ?
                """
                        + (forUpdate ? " FOR UPDATE" : "");
        WipCheckRow row =
                db.query(
                                sql,
                                (rs, i) ->
                                        new WipCheckRow(
                                                rs.getString(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                DataLevel.valueOf(rs.getString(4)),
                                                rs.getString(5),
                                                rs.getString(6)),
                                snapshotId,
                                projectId,
                                spaceId)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
        requireSnapshotLifecycleActive(row.lifecycleState());
        return row;
    }

    private void requireCommitKnown(String projectId, String commitOid) {
        boolean known =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM git_commits WHERE project_id = ? AND oid = ?
                                ) OR EXISTS(
                                    SELECT 1 FROM git_commit_parents p
                                    JOIN git_commits c ON c.id = p.commit_id
                                    WHERE c.project_id = ? AND p.parent_oid = ?
                                ) OR EXISTS(
                                    SELECT 1 FROM wip_snapshots
                                    WHERE project_id = ? AND base_commit_oid = ?
                                )
                                """,
                                Boolean.class,
                                projectId,
                                commitOid,
                                projectId,
                                commitOid,
                                projectId,
                                commitOid));
        if (!known) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private BackfillSessionRow loadAndVerifySession(
            String backfillId, String projectId, DevicePrincipal device, boolean forUpdate) {
        String sql =
                """
                SELECT backfill_id, project_id, space_id, user_id, device_id,
                       membership_epoch, target_kind, target_id, payload_digest,
                       manifest_json, status, accepted_count, expires_at, completed_at
                FROM git_backfill_sessions
                WHERE backfill_id = ?
                """
                        + (forUpdate ? " FOR UPDATE" : "");
        BackfillSessionRow session =
                db.query(
                                sql,
                                (rs, i) -> {
                                    Timestamp completedTs = rs.getTimestamp(14);
                                    return new BackfillSessionRow(
                                            rs.getString(1),
                                            rs.getString(2),
                                            rs.getString(3),
                                            rs.getString(4),
                                            rs.getString(5),
                                            rs.getLong(6),
                                            DiffEndpointKind.valueOf(rs.getString(7)),
                                            rs.getString(8),
                                            rs.getString(9),
                                            rs.getString(10),
                                            rs.getString(11),
                                            rs.getLong(12),
                                            rs.getTimestamp(13).toInstant(),
                                            completedTs != null ? completedTs.toInstant() : null);
                                },
                                backfillId)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));

        if (!session.projectId().equals(projectId)
                || !session.spaceId().equals(device.spaceId())
                || !session.userId().equals(device.userId())
                || !session.deviceId().equals(device.deviceId())
                || session.membershipEpoch() != device.membershipEpoch()) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        return session;
    }

    private List<BackfillManifestItem> parseManifest(String manifestJson) {
        try {
            return mapper.readValue(manifestJson, MANIFEST_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Corrupt manifest_json in git_backfill_sessions", ex);
        }
    }

    private static BackfillManifestItem resolveUploadManifestItem(
            List<BackfillManifestItem> manifest, String pathParam) {
        if (pathParam != null) {
            GitProjectUtils.requireSafeRelativePath(pathParam);
            for (BackfillManifestItem item : manifest) {
                if (pathParam.equals(item.path())) {
                    return item;
                }
            }
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (manifest.size() == 1) {
            return manifest.getFirst();
        }
        throw new DomainException(KernelErrorCode.INVALID_REQUEST);
    }

    private static void validateCreateSessionRequest(CreateBackfillSessionRequest req) {
        if (req == null || req.targetKind() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        validateEndpointRef(new DiffEndpointRef(req.targetKind(), req.targetId()));

        ScopeDeclarationDto scope = req.scopeDeclaration();
        if (scope == null || scope.includedPaths() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        GitProjectUtils.requireSeq(scope.hiddenItemCount());
        GitProjectUtils.requireSeq(scope.excludedItemCount());
        Set<String> includedPaths = new HashSet<>();
        for (String p : scope.includedPaths()) {
            GitProjectUtils.requireSafeRelativePath(p);
            if (!includedPaths.add(p)) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }

        if (req.items() == null || req.items().size() > MAX_BACKFILL_FILES) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        Set<String> seenItemPaths = new HashSet<>();
        long totalBytes = 0L;
        for (BackfillManifestItem item : req.items()) {
            if (item == null || item.sizeBytes() == null) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            GitProjectUtils.requireSafeRelativePath(item.path());
            if (!includedPaths.contains(item.path()) || !seenItemPaths.add(item.path())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            if (item.mode() == null
                    || item.mode().isBlank()
                    || item.mode().length() > 16
                    || "160000".equals(item.mode())
                    || "120000".equals(item.mode())
                    || !item.mode().matches("^[0-7]{6}$")) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            if (item.blobOid() != null && !GitProjectUtils.isAnyValidOid(item.blobOid())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            if (item.sizeBytes() < 0 || item.sizeBytes() > GitProjectUtils.MAX_PATCH_BYTES) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            totalBytes += item.sizeBytes();
            if (totalBytes > GitProjectUtils.MAX_PATCH_BYTES) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            if (!GitProjectUtils.isValidSha256(item.sha256())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }
    }

    private static void validateEndpointRef(DiffEndpointRef ref) {
        if (ref == null || ref.kind() == null || ref.id() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (ref.kind() == DiffEndpointKind.COMMIT) {
            if (!GitProjectUtils.isAnyValidOid(ref.id())) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        } else {
            GitProjectUtils.requireId(ref.id());
        }
    }

    private static byte[] readAndVerifyUploadBytes(
            InputStream bytes, long expectedSize, String expectedSha256) {
        if (bytes == null || expectedSize < 0 || expectedSize > GitProjectUtils.MAX_PATCH_BYTES) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream out =
                    new ByteArrayOutputStream((int) Math.min(expectedSize, 65536L));
            byte[] buf = new byte[16384];
            long total = 0L;
            int n;
            while ((n = bytes.read(buf)) != -1) {
                total += n;
                if (total > expectedSize) {
                    throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                }
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            if (total != expectedSize) {
                throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
            }
            String actualSha256 = HexFormat.of().formatHex(md.digest());
            if (!expectedSha256.equals(actualSha256)) {
                throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void requireDeviceProjectScope(
            DevicePrincipal device, String projectId, String requiredScope) {
        if (device == null
                || !device.projectId().equals(projectId)
                || !device.scopes().contains(requiredScope)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireSnapshotLifecycleActive(String lifecycleState) {
        if ("FORCE_PURGED".equals(lifecycleState)) {
            throw new DomainException(
                    KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED,
                    Map.of("purged", true, "reason", "FORCE_PURGED"));
        }
        if ("EXPIRED_OR_DELETED".equals(lifecycleState)) {
            throw new DomainException(
                    KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED,
                    Map.of("purged", false, "reason", "EXPIRED_OR_DELETED"));
        }
        if (!"ACTIVE".equals(lifecycleState)) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
    }
}
