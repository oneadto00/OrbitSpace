package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.AuditLogPort;
import com.orbitspace.kernel.ContentManifestStatus;
import com.orbitspace.kernel.DataLevel;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.ObjectStoragePort;
import com.orbitspace.kernel.ProjectScopePort;
import com.orbitspace.kernel.SpaceAuthorizationPort;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.CreateTwoPointDiffRequest;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.DiffEndpointKind;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.DiffEndpointRef;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.DiffSymbolChange;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.TwoPointComparisonState;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.TwoPointDiffDto;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.TwoPointDiffFileEntry;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.TwoPointDiffStatus;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.TwoPointDiffSummary;
import com.orbitspace.modules.gitproject.GitDiffTextSupport.ParsedPatchFile;
import com.orbitspace.modules.gitproject.GitProjectDtos.ScopeDeclarationDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Domain service for controlled P2 two-point diffs enforcing the honest five-state content_manifest
 * matrix, real unified diff generation, and optional async DIFF_ENRICHMENT symbol analysis.
 */
public class GitTwoPointDiffService {
    public static final String DIFF_ENRICHMENT_JOB_TYPE = "DIFF_ENRICHMENT";

    private static final Logger LOG = LoggerFactory.getLogger(GitTwoPointDiffService.class);
    private static final long MAX_ENRICHMENT_FILE_BYTES = 1024L * 1024L;
    private static final TypeReference<List<TwoPointDiffFileEntry>> FILE_LIST_TYPE =
            new TypeReference<>() {};

    private record CreateDiffInput(
            String spaceId, String projectId, DiffEndpointRef base, DiffEndpointRef target) {}

    private record WipDiffRow(
            String id,
            String baseKind,
            String baseCommitOid,
            String captureState,
            DataLevel dataLevel,
            String patchObjectKey,
            String scopeDeclarationJson,
            String lifecycleState) {}

    private record BackfillObjectRow(
            String filePath, String objectKey, long sizeBytes, String sha256Hex) {}

    private record DiffRow(
            String id,
            String spaceId,
            String projectId,
            DiffEndpointKind baseKind,
            String baseId,
            DiffEndpointKind targetKind,
            String targetId,
            TwoPointDiffStatus status,
            String enrichmentJobId,
            String enrichmentResultObjectKey,
            String summaryJson,
            String filesJson,
            String lifecycleState,
            Instant createdAt) {}

    private record ResolvedSide(ContentManifestStatus status, String text, String missingDetail) {}

    private record EnrichmentFileInput(
            String path, String patchText, String oldText, String newText) {}

    private record EnrichmentPayload(String diffId, List<EnrichmentFileInput> files) {}

    private record ComputedDiff(
            TwoPointDiffSummary summary,
            List<TwoPointDiffFileEntry> files,
            List<EnrichmentFileInput> enrichmentFiles) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final TransactionTemplate tx;
    private final SpaceAuthorizationPort spaceAuth;
    private final ProjectScopePort projectScope;
    private final ObjectStoragePort objectStorage;
    private final AsyncJobPort asyncJobs;
    private final AuditLogPort auditLog;
    private final GitProjectIdempotencyStore idempotency;

    public GitTwoPointDiffService(
            JdbcTemplate db,
            ObjectMapper mapper,
            PlatformTransactionManager txManager,
            SpaceAuthorizationPort spaceAuth,
            ProjectScopePort projectScope,
            ObjectStoragePort objectStorage,
            AsyncJobPort asyncJobs,
            AuditLogPort auditLog,
            GitProjectIdempotencyStore idempotency) {
        this.db = db;
        this.mapper = mapper;
        this.strictMapper = GitProjectUtils.strictMapper(mapper);
        this.tx = new TransactionTemplate(txManager);
        this.spaceAuth = spaceAuth;
        this.projectScope = projectScope;
        this.objectStorage = objectStorage;
        this.asyncJobs = asyncJobs;
        this.auditLog = auditLog;
        this.idempotency = idempotency;
    }

    public TwoPointDiffDto createTwoPointDiff(
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            String idempotencyKey,
            String rawBody) {
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
        projectScope.requireProjectInSpace(projectId, spaceId);
        GitProjectUtils.normalizeKey64(idempotencyKey);

        CreateTwoPointDiffRequest req =
                GitProjectUtils.parseStrict(strictMapper, rawBody, CreateTwoPointDiffRequest.class);
        validateEndpointRef(req.base());
        validateEndpointRef(req.target());

        CreateDiffInput canonicalInput =
                new CreateDiffInput(spaceId, projectId, req.base(), req.target());

        WipDiffRow baseWip = requireEndpointAccessible(spaceId, projectId, req.base());
        WipDiffRow targetWip = requireEndpointAccessible(spaceId, projectId, req.target());

        // Fast-path replay for repeated idempotency keys after verifying endpoints are still live.
        if (idempotency.hasRecord("CREATE_TWO_POINT_DIFF", projectId, userId, idempotencyKey)) {
            return tx.execute(
                    status -> {
                        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
                        projectScope.requireProjectInSpace(projectId, spaceId);
                        requireEndpointAccessible(spaceId, projectId, req.base());
                        requireEndpointAccessible(spaceId, projectId, req.target());
                        return idempotency.execute(
                                "CREATE_TWO_POINT_DIFF",
                                projectId,
                                userId,
                                idempotencyKey,
                                canonicalInput,
                                TwoPointDiffDto.class,
                                () -> {
                                    throw new IllegalStateException(
                                            "Expected existing idempotency record");
                                });
                    });
        }

        ComputedDiff computed =
                computeControlledDiff(
                        spaceId, projectId, userId, epoch, req.base(), req.target(), baseWip, targetWip);

        String diffId = GitProjectUtils.newId();
        String enrichmentJobId = null;
        String enrichmentInputKey = null;

        if (!computed.enrichmentFiles().isEmpty()) {
            try {
                enrichmentInputKey =
                        stageEnrichmentInput(diffId, computed.enrichmentFiles());
                if (enrichmentInputKey != null) {
                    enrichmentJobId = GitProjectUtils.newId();
                }
            } catch (Exception ex) {
                LOG.warn(
                        "Falling back to plain text diff without symbol enrichment (diffId={}, cause={})",
                        diffId,
                        ex.getClass().getSimpleName());
                enrichmentJobId = null;
                enrichmentInputKey = null;
            }
        }

        final String finalJobId = enrichmentJobId;
        final String finalInputKey = enrichmentInputKey;

        return tx.execute(
                status -> {
                    spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
                    projectScope.requireProjectInSpace(projectId, spaceId);

                    return idempotency.execute(
                            "CREATE_TWO_POINT_DIFF",
                            projectId,
                            userId,
                            idempotencyKey,
                            canonicalInput,
                            TwoPointDiffDto.class,
                            () -> {
                                requireEndpointAccessible(spaceId, projectId, req.base());
                                requireEndpointAccessible(spaceId, projectId, req.target());

                                TwoPointDiffStatus initialStatus =
                                        finalJobId != null
                                                ? TwoPointDiffStatus.PENDING
                                                : TwoPointDiffStatus.READY;
                                Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

                                db.update(
                                        """
                                        INSERT INTO two_point_diffs(
                                            id, space_id, project_id, actor_user_id,
                                            base_kind, base_id, target_kind, target_id,
                                            status, enrichment_job_id, enrichment_input_object_key,
                                            enrichment_result_object_key, summary_json, files_json,
                                            lifecycle_state, lifecycle_version, created_at
                                        )
                                        VALUES (
                                            ?, ?, ?, ?,
                                            ?, ?, ?, ?,
                                            ?, ?, ?,
                                            NULL, CAST(? AS JSON), CAST(? AS JSON),
                                            'ACTIVE', 1, ?
                                        )
                                        """,
                                        diffId,
                                        spaceId,
                                        projectId,
                                        userId,
                                        req.base().kind().name(),
                                        req.base().id(),
                                        req.target().kind().name(),
                                        req.target().id(),
                                        initialStatus.name(),
                                        finalJobId,
                                        finalInputKey,
                                        GitProjectUtils.toJson(mapper, computed.summary()),
                                        GitProjectUtils.toJson(mapper, computed.files()),
                                        Timestamp.from(createdAt));

                                String effectiveJobId = finalJobId;
                                TwoPointDiffStatus effectiveStatus = initialStatus;
                                if (finalJobId != null && finalInputKey != null) {
                                    try {
                                        pinStorageReference(
                                                "DIFF_ENRICH_INPUT", diffId, finalInputKey);
                                        asyncJobs.enqueue(
                                                new AsyncJobPort.Job(
                                                        finalJobId,
                                                        DIFF_ENRICHMENT_JOB_TYPE,
                                                        spaceId,
                                                        diffId,
                                                        userId,
                                                        finalInputKey,
                                                        1L,
                                                        1L));
                                    } catch (RuntimeException ex) {
                                        LOG.warn(
                                                "DIFF_ENRICHMENT enqueue failed; degrading diffId={} to plain text READY (cause={})",
                                                diffId,
                                                ex.getClass().getSimpleName());
                                        effectiveJobId = null;
                                        effectiveStatus = TwoPointDiffStatus.READY;
                                        db.update(
                                                """
                                                UPDATE two_point_diffs
                                                SET status = 'READY',
                                                    enrichment_job_id = NULL
                                                WHERE id = ?
                                                """,
                                                diffId);
                                    }
                                }

                                auditLog.append(
                                        new AuditLogPort.Entry(
                                                GitProjectUtils.requestId(),
                                                userId,
                                                "GIT_TWO_POINT_DIFF_CREATE",
                                                diffId,
                                                "SUCCESS",
                                                Instant.now()));

                                return new TwoPointDiffDto(
                                        diffId,
                                        projectId,
                                        req.base(),
                                        req.target(),
                                        effectiveStatus,
                                        effectiveJobId,
                                        computed.summary(),
                                        computed.files(),
                                        createdAt);
                            });
                });
    }

    public TwoPointDiffDto getTwoPointDiff(
            String spaceId, String projectId, String userId, long epoch, String diffId) {
        GitProjectUtils.requireId(diffId);
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
        projectScope.requireProjectInSpace(projectId, spaceId);

        DiffRow row = loadDiffRow(spaceId, projectId, diffId);
        requireDiffAndEndpointsActive(row);

        TwoPointDiffSummary summary =
                GitProjectUtils.fromJson(mapper, row.summaryJson(), TwoPointDiffSummary.class);
        List<TwoPointDiffFileEntry> files = parseFileEntries(row.filesJson());

        TwoPointDiffStatus effectiveStatus = row.status();
        if (row.enrichmentResultObjectKey() != null) {
            files = mergeEnrichmentSymbols(row, userId, epoch, files);
            effectiveStatus = TwoPointDiffStatus.READY;
        } else if (effectiveStatus == TwoPointDiffStatus.PENDING && row.enrichmentJobId() != null) {
            String jobStatus = loadAsyncJobStatus(row.enrichmentJobId());
            if (jobStatus == null
                    || "FAILED".equals(jobStatus)
                    || "CANCELLED".equals(jobStatus)
                    || "SUCCEEDED".equals(jobStatus)) {
                // Safe degradation: plain text diff remains fully usable even if enrichment failed.
                effectiveStatus = TwoPointDiffStatus.READY;
            }
        } else if (effectiveStatus == TwoPointDiffStatus.FAILED) {
            effectiveStatus = TwoPointDiffStatus.READY;
        }

        return new TwoPointDiffDto(
                row.id(),
                row.projectId(),
                new DiffEndpointRef(row.baseKind(), row.baseId()),
                new DiffEndpointRef(row.targetKind(), row.targetId()),
                effectiveStatus,
                row.enrichmentJobId(),
                summary,
                files,
                row.createdAt());
    }

    private ComputedDiff computeControlledDiff(
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            DiffEndpointRef base,
            DiffEndpointRef target,
            WipDiffRow baseWip,
            WipDiffRow targetWip) {
        Map<String, BackfillObjectRow> baseBackfills = loadLiveBackfillObjects(projectId, base);
        Map<String, BackfillObjectRow> targetBackfills = loadLiveBackfillObjects(projectId, target);
        Map<String, ContentManifestStatus> baseManifest =
                baseWip != null ? loadWipManifest(baseWip.id()) : Map.of();
        Map<String, ContentManifestStatus> targetManifest =
                targetWip != null ? loadWipManifest(targetWip.id()) : Map.of();
        Map<String, ParsedPatchFile> basePatchSections =
                loadWipPatchSections(spaceId, projectId, userId, epoch, baseWip);
        Map<String, ParsedPatchFile> targetPatchSections =
                loadWipPatchSections(spaceId, projectId, userId, epoch, targetWip);

        boolean sameEndpoint = base.kind() == target.kind() && base.id().equals(target.id());
        boolean directTargetOnBase =
                base.kind() == DiffEndpointKind.COMMIT
                        && targetWip != null
                        && "COMMIT".equals(targetWip.baseKind())
                        && base.id().equals(targetWip.baseCommitOid());
        boolean directBaseOnTarget =
                target.kind() == DiffEndpointKind.COMMIT
                        && baseWip != null
                        && "COMMIT".equals(baseWip.baseKind())
                        && target.id().equals(baseWip.baseCommitOid());

        Map<String, BackfillObjectRow> sharedBaseBackfills = Map.of();
        if (baseWip != null
                && targetWip != null
                && "COMMIT".equals(baseWip.baseKind())
                && "COMMIT".equals(targetWip.baseKind())
                && baseWip.baseCommitOid() != null
                && baseWip.baseCommitOid().equals(targetWip.baseCommitOid())) {
            sharedBaseBackfills =
                    loadLiveBackfillObjects(
                            projectId,
                            new DiffEndpointRef(DiffEndpointKind.COMMIT, baseWip.baseCommitOid()));
        }

        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(baseManifest.keySet());
        allPaths.addAll(targetManifest.keySet());
        allPaths.addAll(baseBackfills.keySet());
        allPaths.addAll(targetBackfills.keySet());
        allPaths.addAll(basePatchSections.keySet());
        allPaths.addAll(targetPatchSections.keySet());

        Map<String, Optional<String>> objectTextCache = new HashMap<>();
        List<TwoPointDiffFileEntry> entries = new ArrayList<>(allPaths.size());
        List<EnrichmentFileInput> enrichmentFiles = new ArrayList<>();

        for (String path : allPaths) {
            if (!GitProjectUtils.isSafeRelativePath(path)) {
                continue;
            }

            if (sameEndpoint) {
                TwoPointDiffFileEntry entry =
                        evaluateSameEndpointPath(
                                path,
                                base,
                                baseBackfills.get(path),
                                baseManifest.get(path),
                                basePatchSections.get(path),
                                spaceId,
                                projectId,
                                userId,
                                epoch,
                                objectTextCache);
                entries.add(entry);
                continue;
            }

            if (directTargetOnBase) {
                TwoPointDiffFileEntry entry =
                        evaluateDirectCommitToWip(
                                path,
                                baseBackfills.get(path),
                                targetBackfills.get(path),
                                targetManifest.get(path),
                                targetPatchSections.get(path),
                                spaceId,
                                projectId,
                                userId,
                                epoch,
                                objectTextCache,
                                enrichmentFiles);
                if (entry != null) {
                    entries.add(entry);
                    continue;
                }
            }

            if (directBaseOnTarget) {
                TwoPointDiffFileEntry entry =
                        evaluateDirectWipToCommit(
                                path,
                                baseBackfills.get(path),
                                targetBackfills.get(path),
                                baseManifest.get(path),
                                basePatchSections.get(path),
                                spaceId,
                                projectId,
                                userId,
                                epoch,
                                objectTextCache,
                                enrichmentFiles);
                if (entry != null) {
                    entries.add(entry);
                    continue;
                }
            }

            ResolvedSide baseSide =
                    resolveGeneralEndpointSide(
                            base,
                            path,
                            baseBackfills.get(path),
                            baseManifest.get(path),
                            basePatchSections.get(path),
                            sharedBaseBackfills.get(path),
                            spaceId,
                            projectId,
                            userId,
                            epoch,
                            objectTextCache);
            ResolvedSide targetSide =
                    resolveGeneralEndpointSide(
                            target,
                            path,
                            targetBackfills.get(path),
                            targetManifest.get(path),
                            targetPatchSections.get(path),
                            sharedBaseBackfills.get(path),
                            spaceId,
                            projectId,
                            userId,
                            epoch,
                            objectTextCache);

            TwoPointDiffFileEntry entry =
                    classifyFiveStatePair(path, null, baseSide, targetSide, enrichmentFiles);
            entries.add(entry);
        }

        long comparable = 0;
        long unsharedOrMissing = 0;
        long unsupported = 0;
        for (TwoPointDiffFileEntry entry : entries) {
            if (entry.deterministic()) {
                comparable++;
            } else if (entry.comparisonState() == TwoPointComparisonState.INCOMPARABLE_UNSUPPORTED) {
                unsupported++;
            } else {
                unsharedOrMissing++;
            }
        }

        boolean endpointsProveEmptyDiff =
                sameEndpoint
                        || (directTargetOnBase && isProvenNoChangesWip(targetWip))
                        || (directBaseOnTarget && isProvenNoChangesWip(baseWip));
        boolean hasInsufficientData =
                unsharedOrMissing > 0
                        || unsupported > 0
                        || (entries.isEmpty() && !endpointsProveEmptyDiff);

        TwoPointDiffSummary summary =
                new TwoPointDiffSummary(
                        comparable, unsharedOrMissing, unsupported, hasInsufficientData);
        return new ComputedDiff(summary, List.copyOf(entries), List.copyOf(enrichmentFiles));
    }

    private TwoPointDiffFileEntry evaluateSameEndpointPath(
            String path,
            DiffEndpointRef endpoint,
            BackfillObjectRow backfill,
            ContentManifestStatus manifestStatus,
            ParsedPatchFile patchFile,
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            Map<String, Optional<String>> textCache) {
        if (manifestStatus == ContentManifestStatus.UNSUPPORTED
                || (patchFile != null && patchFile.binary())) {
            return incomparableEntry(
                    path,
                    null,
                    ContentManifestStatus.UNSUPPORTED,
                    ContentManifestStatus.UNSUPPORTED,
                    TwoPointComparisonState.INCOMPARABLE_UNSUPPORTED,
                    "该路径属于不支持文本比较的条目（如子模块、LFS 指针或二进制文件）。");
        }
        if (backfill != null) {
            Optional<String> text =
                    readTextObjectCached(
                            backfill.objectKey(), spaceId, projectId, userId, epoch, textCache);
            if (text.isEmpty()) {
                return incomparableEntry(
                        path,
                        null,
                        ContentManifestStatus.UNSUPPORTED,
                        ContentManifestStatus.UNSUPPORTED,
                        TwoPointComparisonState.INCOMPARABLE_UNSUPPORTED,
                        "已补传对象为二进制、非 UTF-8 文本或 Git LFS 指针，无法进行文本比较。");
            }
            return new TwoPointDiffFileEntry(
                    path,
                    null,
                    ContentManifestStatus.AVAILABLE,
                    ContentManifestStatus.AVAILABLE,
                    TwoPointComparisonState.UNCHANGED,
                    true,
                    null,
                    null,
                    List.of());
        }
        ContentManifestStatus st =
                manifestStatus != null
                        ? manifestStatus
                        : (endpoint.kind() == DiffEndpointKind.COMMIT
                                ? ContentManifestStatus.MISSING
                                : ContentManifestStatus.MISSING);
        return switch (st) {
            case AVAILABLE ->
                    new TwoPointDiffFileEntry(
                            path,
                            null,
                            ContentManifestStatus.AVAILABLE,
                            ContentManifestStatus.AVAILABLE,
                            TwoPointComparisonState.UNCHANGED,
                            true,
                            null,
                            null,
                            List.of());
            case ABSENT_CONFIRMED ->
                    new TwoPointDiffFileEntry(
                            path,
                            null,
                            ContentManifestStatus.ABSENT_CONFIRMED,
                            ContentManifestStatus.ABSENT_CONFIRMED,
                            TwoPointComparisonState.UNCHANGED,
                            true,
                            null,
                            null,
                            List.of());
            case NOT_SHARED ->
                    incomparableEntry(
                            path,
                            null,
                            ContentManifestStatus.NOT_SHARED,
                            ContentManifestStatus.NOT_SHARED,
                            TwoPointComparisonState.INCOMPARABLE_NOT_SHARED,
                            "该路径未被共享，无法确认差异，切勿视为文件删除。");
            case MISSING ->
                    incomparableEntry(
                            path,
                            null,
                            ContentManifestStatus.MISSING,
                            ContentManifestStatus.MISSING,
                            TwoPointComparisonState.INCOMPARABLE_MISSING,
                            "端点缺少该路径的可用内容，无法确认差异，请使用 orbit backfill 补传。");
            case UNSUPPORTED ->
                    incomparableEntry(
                            path,
                            null,
                            ContentManifestStatus.UNSUPPORTED,
                            ContentManifestStatus.UNSUPPORTED,
                            TwoPointComparisonState.INCOMPARABLE_UNSUPPORTED,
                            "该路径属于不支持比较的条目。");
        };
    }

    private TwoPointDiffFileEntry evaluateDirectCommitToWip(
            String path,
            BackfillObjectRow baseBackfill,
            BackfillObjectRow targetBackfill,
            ContentManifestStatus targetMs,
            ParsedPatchFile targetPatch,
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            Map<String, Optional<String>> textCache,
            List<EnrichmentFileInput> enrichmentFiles) {
        if (targetMs == ContentManifestStatus.UNSUPPORTED
                || (targetPatch != null && targetPatch.binary())) {
            ContentManifestStatus baseSt =
                    baseBackfill != null
                            ? ContentManifestStatus.AVAILABLE
                            : ContentManifestStatus.MISSING;
            return incomparableEntry(
                    path,
                    targetPatch != null ? targetPatch.oldPath() : null,
                    baseSt,
                    ContentManifestStatus.UNSUPPORTED,
                    TwoPointComparisonState.INCOMPARABLE_UNSUPPORTED,
                    "目标端点标记该路径为不支持比较的条目（如子模块、LFS 指针或二进制文件）。");
        }
        if (targetMs == ContentManifestStatus.NOT_SHARED) {
            ContentManifestStatus baseSt =
                    baseBackfill != null
                            ? ContentManifestStatus.AVAILABLE
                            : ContentManifestStatus.MISSING;
            return incomparableEntry(
                    path,
                    null,
                    baseSt,
                    ContentManifestStatus.NOT_SHARED,
                    TwoPointComparisonState.INCOMPARABLE_NOT_SHARED,
                    "目标 WIP 未共享该路径内容（NOT_SHARED），不可比较，绝不视为删除。");
        }
        if (targetMs == ContentManifestStatus.MISSING) {
            ContentManifestStatus baseSt =
                    baseBackfill != null
                            ? ContentManifestStatus.AVAILABLE
                            : ContentManifestStatus.MISSING;
            return incomparableEntry(
                    path,
                    null,
                    baseSt,
                    ContentManifestStatus.MISSING,
                    TwoPointComparisonState.INCOMPARABLE_MISSING,
                    "目标 WIP 缺少该路径内容（MISSING），请执行 orbit backfill 受控补传，绝不视为删除。");
        }

        // If both sides were explicitly backfilled, compare exact full texts directly.
        if (baseBackfill != null && targetBackfill != null) {
            return null;
        }

        if (targetPatch != null) {
            String oldText =
                    baseBackfill != null
                            ? readTextObjectCached(
                                            baseBackfill.objectKey(),
                                            spaceId,
                                            projectId,
                                            userId,
                                            epoch,
                                            textCache)
                                    .orElse(null)
                            : null;
            String newText =
                    targetBackfill != null
                            ? readTextObjectCached(
                                            targetBackfill.objectKey(),
                                            spaceId,
                                            projectId,
                                            userId,
                                            epoch,
                                            textCache)
                                    .orElse(null)
                            : null;

            if (targetPatch.newFile()) {
                if (newText == null) {
                    newText = GitDiffTextSupport.extractNewFileText(targetPatch).orElse(null);
                }
                String patchText = targetPatch.patchText();
                addEnrichmentCandidate(enrichmentFiles, path, patchText, null, newText);
                return new TwoPointDiffFileEntry(
                        path,
                        targetPatch.oldPath(),
                        ContentManifestStatus.ABSENT_CONFIRMED,
                        ContentManifestStatus.AVAILABLE,
                        TwoPointComparisonState.ADDED,
                        true,
                        null,
                        patchText,
                        List.of());
            }
            if (targetPatch.deletedFile()) {
                String patchText = targetPatch.patchText();
                addEnrichmentCandidate(enrichmentFiles, path, patchText, oldText, null);
                return new TwoPointDiffFileEntry(
                        path,
                        targetPatch.oldPath(),
                        ContentManifestStatus.AVAILABLE,
                        ContentManifestStatus.ABSENT_CONFIRMED,
                        TwoPointComparisonState.DELETED,
                        true,
                        null,
                        patchText,
                        List.of());
            }
            if (newText == null && oldText != null) {
                newText = GitDiffTextSupport.applyPatchToBase(oldText, targetPatch).orElse(null);
            }
            boolean hasHunks = !targetPatch.hunks().isEmpty();
            String patchText = hasHunks ? targetPatch.patchText() : null;
            if (hasHunks) {
                addEnrichmentCandidate(enrichmentFiles, path, patchText, oldText, newText);
            }
            return new TwoPointDiffFileEntry(
                    path,
                    targetPatch.oldPath(),
                    ContentManifestStatus.AVAILABLE,
                    ContentManifestStatus.AVAILABLE,
                    hasHunks ? TwoPointComparisonState.MODIFIED : TwoPointComparisonState.UNCHANGED,
                    true,
                    null,
                    patchText,
                    List.of());
        }
        return null;
    }

    private TwoPointDiffFileEntry evaluateDirectWipToCommit(
            String path,
            BackfillObjectRow baseBackfill,
            BackfillObjectRow targetBackfill,
            ContentManifestStatus baseMs,
            ParsedPatchFile basePatch,
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            Map<String, Optional<String>> textCache,
            List<EnrichmentFileInput> enrichmentFiles) {
        if (baseMs == ContentManifestStatus.UNSUPPORTED
                || (basePatch != null && basePatch.binary())) {
            ContentManifestStatus targetSt =
                    targetBackfill != null
                            ? ContentManifestStatus.AVAILABLE
                            : ContentManifestStatus.MISSING;
            return incomparableEntry(
                    path,
                    basePatch != null ? basePatch.oldPath() : null,
                    ContentManifestStatus.UNSUPPORTED,
                    targetSt,
                    TwoPointComparisonState.INCOMPARABLE_UNSUPPORTED,
                    "基准端点标记该路径为不支持比较的条目（如子模块、LFS 指针或二进制文件）。");
        }
        if (baseMs == ContentManifestStatus.NOT_SHARED) {
            ContentManifestStatus targetSt =
                    targetBackfill != null
                            ? ContentManifestStatus.AVAILABLE
                            : ContentManifestStatus.MISSING;
            return incomparableEntry(
                    path,
                    null,
                    ContentManifestStatus.NOT_SHARED,
                    targetSt,
                    TwoPointComparisonState.INCOMPARABLE_NOT_SHARED,
                    "基准 WIP 未共享该路径内容（NOT_SHARED），不可比较，绝不视为删除。");
        }
        if (baseMs == ContentManifestStatus.MISSING) {
            ContentManifestStatus targetSt =
                    targetBackfill != null
                            ? ContentManifestStatus.AVAILABLE
                            : ContentManifestStatus.MISSING;
            return incomparableEntry(
                    path,
                    null,
                    ContentManifestStatus.MISSING,
                    targetSt,
                    TwoPointComparisonState.INCOMPARABLE_MISSING,
                    "基准 WIP 缺少该路径内容（MISSING），请执行 orbit backfill 受控补传，绝不视为删除。");
        }

        if (baseBackfill != null && targetBackfill != null) {
            return null;
        }

        if (basePatch != null) {
            String targetText =
                    targetBackfill != null
                            ? readTextObjectCached(
                                            targetBackfill.objectKey(),
                                            spaceId,
                                            projectId,
                                            userId,
                                            epoch,
                                            textCache)
                                    .orElse(null)
                            : null;
            String baseText =
                    baseBackfill != null
                            ? readTextObjectCached(
                                            baseBackfill.objectKey(),
                                            spaceId,
                                            projectId,
                                            userId,
                                            epoch,
                                            textCache)
                                    .orElse(null)
                            : null;

            String invertedPatch = GitDiffTextSupport.invertPatch(basePatch);
            if (basePatch.newFile()) {
                // Added in WIP (base) -> absent in commit (target) => DELETED from base to target
                if (baseText == null) {
                    baseText = GitDiffTextSupport.extractNewFileText(basePatch).orElse(null);
                }
                addEnrichmentCandidate(enrichmentFiles, path, invertedPatch, baseText, null);
                return new TwoPointDiffFileEntry(
                        path,
                        basePatch.oldPath(),
                        ContentManifestStatus.AVAILABLE,
                        ContentManifestStatus.ABSENT_CONFIRMED,
                        TwoPointComparisonState.DELETED,
                        true,
                        null,
                        invertedPatch,
                        List.of());
            }
            if (basePatch.deletedFile()) {
                // Deleted in WIP (base) -> present in commit (target) => ADDED from base to target
                addEnrichmentCandidate(enrichmentFiles, path, invertedPatch, null, targetText);
                return new TwoPointDiffFileEntry(
                        path,
                        basePatch.oldPath(),
                        ContentManifestStatus.ABSENT_CONFIRMED,
                        ContentManifestStatus.AVAILABLE,
                        TwoPointComparisonState.ADDED,
                        true,
                        null,
                        invertedPatch,
                        List.of());
            }
            if (baseText == null && targetText != null) {
                baseText = GitDiffTextSupport.applyPatchToBase(targetText, basePatch).orElse(null);
            }
            boolean hasHunks = !basePatch.hunks().isEmpty();
            String patchText = hasHunks ? invertedPatch : null;
            if (hasHunks) {
                addEnrichmentCandidate(enrichmentFiles, path, patchText, baseText, targetText);
            }
            return new TwoPointDiffFileEntry(
                    path,
                    basePatch.oldPath(),
                    ContentManifestStatus.AVAILABLE,
                    ContentManifestStatus.AVAILABLE,
                    hasHunks ? TwoPointComparisonState.MODIFIED : TwoPointComparisonState.UNCHANGED,
                    true,
                    null,
                    patchText,
                    List.of());
        }
        return null;
    }

    private ResolvedSide resolveGeneralEndpointSide(
            DiffEndpointRef endpoint,
            String path,
            BackfillObjectRow backfill,
            ContentManifestStatus manifestStatus,
            ParsedPatchFile patchFile,
            BackfillObjectRow sharedBaseBackfill,
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            Map<String, Optional<String>> textCache) {
        if (endpoint.kind() == DiffEndpointKind.COMMIT) {
            if (backfill != null) {
                Optional<String> text =
                        readTextObjectCached(
                                backfill.objectKey(), spaceId, projectId, userId, epoch, textCache);
                if (text.isEmpty()) {
                    return new ResolvedSide(
                            ContentManifestStatus.UNSUPPORTED,
                            null,
                            "提交补传对象为二进制、非 UTF-8 或 Git LFS 指针。");
                }
                return new ResolvedSide(ContentManifestStatus.AVAILABLE, text.get(), null);
            }
            return new ResolvedSide(
                    ContentManifestStatus.MISSING,
                    null,
                    "提交端点尚未补传该路径的受控文件内容（请运行 orbit backfill）。");
        }

        // WIP endpoint
        if (manifestStatus == ContentManifestStatus.UNSUPPORTED
                || (patchFile != null && patchFile.binary())) {
            return new ResolvedSide(
                    ContentManifestStatus.UNSUPPORTED,
                    null,
                    "WIP 快照将该路径标记为不支持比较的条目。");
        }
        if (backfill != null) {
            Optional<String> text =
                    readTextObjectCached(
                            backfill.objectKey(), spaceId, projectId, userId, epoch, textCache);
            if (text.isEmpty()) {
                return new ResolvedSide(
                        ContentManifestStatus.UNSUPPORTED,
                        null,
                        "WIP 补传对象为二进制、非 UTF-8 或 Git LFS 指针。");
            }
            return new ResolvedSide(ContentManifestStatus.AVAILABLE, text.get(), null);
        }
        if (manifestStatus == ContentManifestStatus.ABSENT_CONFIRMED) {
            return new ResolvedSide(ContentManifestStatus.ABSENT_CONFIRMED, null, null);
        }
        if (manifestStatus == ContentManifestStatus.NOT_SHARED) {
            return new ResolvedSide(
                    ContentManifestStatus.NOT_SHARED,
                    null,
                    "WIP 快照未共享该路径（NOT_SHARED），不可视为删除。");
        }
        if (manifestStatus == ContentManifestStatus.AVAILABLE && patchFile != null) {
            if (patchFile.newFile()) {
                Optional<String> extracted = GitDiffTextSupport.extractNewFileText(patchFile);
                if (extracted.isPresent()) {
                    return new ResolvedSide(ContentManifestStatus.AVAILABLE, extracted.get(), null);
                }
            } else if (sharedBaseBackfill != null) {
                Optional<String> sharedBaseText =
                        readTextObjectCached(
                                sharedBaseBackfill.objectKey(),
                                spaceId,
                                projectId,
                                userId,
                                epoch,
                                textCache);
                if (sharedBaseText.isPresent()) {
                    Optional<String> reconstructed =
                            GitDiffTextSupport.applyPatchToBase(sharedBaseText.get(), patchFile);
                    if (reconstructed.isPresent()) {
                        return new ResolvedSide(
                                ContentManifestStatus.AVAILABLE, reconstructed.get(), null);
                    }
                }
            }
            return new ResolvedSide(
                    ContentManifestStatus.MISSING,
                    null,
                    "WIP 快照仅含相对其基准提交的 Patch，缺少跨点比较所需的完整文件内容，请运行 orbit backfill 补传。");
        }
        return new ResolvedSide(
                ContentManifestStatus.MISSING,
                null,
                "WIP 快照缺少该路径的完整内容或存在性证据，请运行 orbit backfill 补传。");
    }

    private TwoPointDiffFileEntry classifyFiveStatePair(
            String path,
            String oldPath,
            ResolvedSide baseSide,
            ResolvedSide targetSide,
            List<EnrichmentFileInput> enrichmentFiles) {
        ContentManifestStatus b = baseSide.status();
        ContentManifestStatus t = targetSide.status();

        if (b == ContentManifestStatus.UNSUPPORTED || t == ContentManifestStatus.UNSUPPORTED) {
            return incomparableEntry(
                    path,
                    oldPath,
                    b,
                    t,
                    TwoPointComparisonState.INCOMPARABLE_UNSUPPORTED,
                    "至少一端存在不支持比较的条目（子模块、LFS 指针或二进制内容），无法生成确定性文本差异。");
        }
        if (b == ContentManifestStatus.NOT_SHARED || t == ContentManifestStatus.NOT_SHARED) {
            String reason =
                    b == ContentManifestStatus.NOT_SHARED && t == ContentManifestStatus.NOT_SHARED
                            ? "两端均未共享该路径（NOT_SHARED），无法比较，绝不判定为文件删除。"
                            : (b == ContentManifestStatus.NOT_SHARED
                                    ? "基准端未共享该路径（NOT_SHARED），无法比较，绝不判定为新增或删除。"
                                    : "目标端未共享该路径（NOT_SHARED），无法比较，绝不判定为文件删除。");
            return incomparableEntry(
                    path, oldPath, b, t, TwoPointComparisonState.INCOMPARABLE_NOT_SHARED, reason);
        }
        if (b == ContentManifestStatus.MISSING || t == ContentManifestStatus.MISSING) {
            String reason =
                    b == ContentManifestStatus.MISSING && t == ContentManifestStatus.MISSING
                            ? "两端均缺少该路径的完整内容（MISSING），请运行 orbit backfill 补传后再比较，绝不判定为删除。"
                            : (b == ContentManifestStatus.MISSING
                                    ? "基准端缺少该路径的完整内容（MISSING），请对基准端运行 orbit backfill 补传，绝不判定为删除。"
                                    : "目标端缺少该路径的完整内容（MISSING），请对目标端运行 orbit backfill 补传，绝不判定为删除。");
            return incomparableEntry(
                    path, oldPath, b, t, TwoPointComparisonState.INCOMPARABLE_MISSING, reason);
        }

        // Both sides have deterministic evidence: AVAILABLE or ABSENT_CONFIRMED.
        if (b == ContentManifestStatus.ABSENT_CONFIRMED && t == ContentManifestStatus.AVAILABLE) {
            String patchText = GitDiffTextSupport.unifiedDiff(path, null, targetSide.text());
            addEnrichmentCandidate(enrichmentFiles, path, patchText, null, targetSide.text());
            return new TwoPointDiffFileEntry(
                    path,
                    oldPath,
                    b,
                    t,
                    TwoPointComparisonState.ADDED,
                    true,
                    null,
                    patchText,
                    List.of());
        }
        if (b == ContentManifestStatus.AVAILABLE && t == ContentManifestStatus.ABSENT_CONFIRMED) {
            String patchText = GitDiffTextSupport.unifiedDiff(path, baseSide.text(), null);
            addEnrichmentCandidate(enrichmentFiles, path, patchText, baseSide.text(), null);
            return new TwoPointDiffFileEntry(
                    path,
                    oldPath,
                    b,
                    t,
                    TwoPointComparisonState.DELETED,
                    true,
                    null,
                    patchText,
                    List.of());
        }
        if (b == ContentManifestStatus.ABSENT_CONFIRMED
                && t == ContentManifestStatus.ABSENT_CONFIRMED) {
            return new TwoPointDiffFileEntry(
                    path,
                    oldPath,
                    b,
                    t,
                    TwoPointComparisonState.UNCHANGED,
                    true,
                    null,
                    null,
                    List.of());
        }

        // Both AVAILABLE
        String patchText = GitDiffTextSupport.unifiedDiff(path, baseSide.text(), targetSide.text());
        if (patchText == null) {
            return new TwoPointDiffFileEntry(
                    path,
                    oldPath,
                    b,
                    t,
                    TwoPointComparisonState.UNCHANGED,
                    true,
                    null,
                    null,
                    List.of());
        }
        addEnrichmentCandidate(enrichmentFiles, path, patchText, baseSide.text(), targetSide.text());
        return new TwoPointDiffFileEntry(
                path,
                oldPath,
                b,
                t,
                TwoPointComparisonState.MODIFIED,
                true,
                null,
                patchText,
                List.of());
    }

    private static TwoPointDiffFileEntry incomparableEntry(
            String path,
            String oldPath,
            ContentManifestStatus baseStatus,
            ContentManifestStatus targetStatus,
            TwoPointComparisonState state,
            String reasonMessage) {
        return new TwoPointDiffFileEntry(
                path, oldPath, baseStatus, targetStatus, state, false, reasonMessage, null, List.of());
    }

    private static void addEnrichmentCandidate(
            List<EnrichmentFileInput> list,
            String path,
            String patchText,
            String oldText,
            String newText) {
        if (patchText == null || patchText.isBlank()) {
            return;
        }
        String boundedOld =
                oldText != null && oldText.length() <= MAX_ENRICHMENT_FILE_BYTES ? oldText : null;
        String boundedNew =
                newText != null && newText.length() <= MAX_ENRICHMENT_FILE_BYTES ? newText : null;
        list.add(new EnrichmentFileInput(path, patchText, boundedOld, boundedNew));
    }

    private String stageEnrichmentInput(String diffId, List<EnrichmentFileInput> files)
            throws IOException {
        byte[] encoded =
                GitProjectUtils.toJson(mapper, new EnrichmentPayload(diffId, files))
                        .getBytes(StandardCharsets.UTF_8);
        if (encoded.length > GitProjectUtils.MAX_PATCH_BYTES) {
            // Compact payload by dropping full oldText/newText while preserving patchText hunks.
            List<EnrichmentFileInput> compact =
                    files.stream()
                            .map(f -> new EnrichmentFileInput(f.path(), f.patchText(), null, null))
                            .toList();
            encoded =
                    GitProjectUtils.toJson(mapper, new EnrichmentPayload(diffId, compact))
                            .getBytes(StandardCharsets.UTF_8);
            if (encoded.length > GitProjectUtils.MAX_PATCH_BYTES) {
                return null;
            }
        }
        ObjectStoragePort.StoredObject temp =
                objectStorage.writeTemporary(
                        "diff-enrich-in:" + diffId,
                        new ByteArrayInputStream(encoded),
                        GitProjectUtils.MAX_PATCH_BYTES);
        ObjectStoragePort.StoredObject promoted =
                objectStorage.promote(temp.objectKey(), temp.sizeBytes(), temp.sha256());
        return promoted.objectKey();
    }

    private List<TwoPointDiffFileEntry> mergeEnrichmentSymbols(
            DiffRow row,
            String userId,
            long epoch,
            List<TwoPointDiffFileEntry> baseFiles) {
        boolean[] accessVerified = {false};
        try (InputStream raw =
                objectStorage.openRead(
                        row.enrichmentResultObjectKey(),
                        () -> {
                            spaceAuth.requireMembership(
                                    row.spaceId(), userId, epoch, SpaceRole.VIEWER);
                            projectScope.requireProjectInSpace(row.projectId(), row.spaceId());
                            DiffRow live = loadDiffRow(row.spaceId(), row.projectId(), row.id());
                            requireDiffAndEndpointsActive(live);
                            accessVerified[0] = true;
                        })) {
            JsonNode root = mapper.readTree(raw);
            JsonNode filesNode = root != null ? root.get("files") : null;
            if (filesNode == null || !filesNode.isArray()) {
                return baseFiles;
            }
            Map<String, List<DiffSymbolChange>> symbolsByPath = new HashMap<>();
            for (JsonNode fileNode : filesNode) {
                if (fileNode == null || !fileNode.isObject()) {
                    continue;
                }
                String path = fileNode.path("path").asText(null);
                JsonNode symsNode = fileNode.get("symbols");
                if (path == null || symsNode == null || !symsNode.isArray()) {
                    continue;
                }
                List<DiffSymbolChange> parsed = new ArrayList<>();
                for (JsonNode sym : symsNode) {
                    if (sym == null || !sym.isObject()) {
                        continue;
                    }
                    String name = sym.path("name").asText(null);
                    String kind = sym.path("kind").asText(null);
                    if (name == null || name.isBlank() || kind == null || kind.isBlank()) {
                        continue;
                    }
                    Integer oldStart = positiveIntOrNull(sym.get("oldStartLine"));
                    Integer newStart = positiveIntOrNull(sym.get("newStartLine"));
                    parsed.add(new DiffSymbolChange(name, kind, oldStart, newStart));
                }
                symbolsByPath.put(path, List.copyOf(parsed));
            }
            if (symbolsByPath.isEmpty()) {
                return baseFiles;
            }
            List<TwoPointDiffFileEntry> merged = new ArrayList<>(baseFiles.size());
            for (TwoPointDiffFileEntry entry : baseFiles) {
                List<DiffSymbolChange> syms = symbolsByPath.get(entry.path());
                if (syms != null && !syms.isEmpty() && entry.deterministic()) {
                    merged.add(
                            new TwoPointDiffFileEntry(
                                    entry.path(),
                                    entry.oldPath(),
                                    entry.baseStatus(),
                                    entry.targetStatus(),
                                    entry.comparisonState(),
                                    entry.deterministic(),
                                    entry.reasonMessage(),
                                    entry.patchText(),
                                    syms));
                } else {
                    merged.add(entry);
                }
            }
            return List.copyOf(merged);
        } catch (DomainException ex) {
            if (!accessVerified[0]) {
                throw ex;
            }
            LOG.warn(
                    "Enrichment object unavailable for active diffId={}; degrading to plain text diff (code={})",
                    row.id(),
                    ex.errorCode().code());
            return baseFiles;
        } catch (Exception ex) {
            LOG.warn(
                    "Unable to merge DIFF_ENRICHMENT symbols for diffId={}; degrading to plain text diff (cause={})",
                    row.id(),
                    ex.getClass().getSimpleName());
            return baseFiles;
        }
    }

    private Optional<String> readTextObjectCached(
            String objectKey,
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            Map<String, Optional<String>> cache) {
        if (cache.containsKey(objectKey)) {
            return cache.get(objectKey);
        }
        Optional<String> decoded;
        try (InputStream in =
                objectStorage.openRead(
                        objectKey,
                        () -> {
                            spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
                            projectScope.requireProjectInSpace(projectId, spaceId);
                        })) {
            byte[] raw = in.readNBytes((int) GitProjectUtils.MAX_PATCH_BYTES + 1);
            if (raw.length > GitProjectUtils.MAX_PATCH_BYTES) {
                decoded = Optional.empty();
            } else {
                Optional<String> textOpt = GitDiffTextSupport.decodeUtf8Text(raw);
                if (textOpt.isPresent() && isGitLfsPointer(textOpt.get())) {
                    decoded = Optional.empty();
                } else {
                    decoded = textOpt;
                }
            }
        } catch (DomainException ex) {
            throw ex;
        } catch (IOException ex) {
            decoded = Optional.empty();
        }
        cache.put(objectKey, decoded);
        return decoded;
    }

    private Map<String, ParsedPatchFile> loadWipPatchSections(
            String spaceId, String projectId, String userId, long epoch, WipDiffRow wip) {
        if (wip == null
                || wip.dataLevel() != DataLevel.PATCH_ONLY
                || wip.patchObjectKey() == null) {
            return Map.of();
        }
        try (InputStream in =
                objectStorage.openRead(
                        wip.patchObjectKey(),
                        () -> {
                            spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
                            projectScope.requireProjectInSpace(projectId, spaceId);
                            WipDiffRow live = loadWipRow(spaceId, projectId, wip.id());
                            requireSnapshotLifecycleActive(live.lifecycleState());
                        })) {
            byte[] bytes = in.readNBytes((int) GitProjectUtils.MAX_PATCH_BYTES + 1);
            if (bytes.length == 0 || bytes.length > GitProjectUtils.MAX_PATCH_BYTES) {
                return Map.of();
            }
            String rawPatch = new String(bytes, StandardCharsets.UTF_8);
            return GitDiffTextSupport.parseUnifiedPatch(rawPatch);
        } catch (DomainException ex) {
            throw ex;
        } catch (IOException ex) {
            LOG.warn(
                    "Unable to read WIP patch for snapshotId={} during two-point diff (cause={})",
                    wip.id(),
                    ex.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, BackfillObjectRow> loadLiveBackfillObjects(
            String projectId, DiffEndpointRef endpoint) {
        Map<String, BackfillObjectRow> map = new HashMap<>();
        db.query(
                """
                SELECT o.file_path, o.object_key, o.size_bytes, o.sha256_hex
                FROM git_backfill_objects o
                JOIN storage_objects s ON s.object_key = o.object_key
                WHERE o.project_id = ?
                  AND o.target_kind = ?
                  AND o.target_id = ?
                  AND s.status = 'IMMUTABLE'
                """,
                rs -> {
                    BackfillObjectRow row =
                            new BackfillObjectRow(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getLong(3),
                                    rs.getString(4));
                    map.put(row.filePath(), row);
                },
                projectId,
                endpoint.kind().name(),
                endpoint.id());
        return map;
    }

    private Map<String, ContentManifestStatus> loadWipManifest(String snapshotId) {
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

    private WipDiffRow requireEndpointAccessible(
            String spaceId, String projectId, DiffEndpointRef endpoint) {
        if (endpoint.kind() == DiffEndpointKind.COMMIT) {
            requireCommitKnown(projectId, endpoint.id());
            return null;
        }
        WipDiffRow wip = loadWipRow(spaceId, projectId, endpoint.id());
        requireSnapshotLifecycleActive(wip.lifecycleState());
        return wip;
    }

    private WipDiffRow loadWipRow(String spaceId, String projectId, String snapshotId) {
        return db.query(
                        """
                        SELECT id, base_kind, base_commit_oid, capture_state, data_level,
                               patch_object_key, scope_declaration_json, lifecycle_state
                        FROM wip_snapshots
                        WHERE id = ? AND project_id = ? AND space_id = ?
                        """,
                        (rs, i) ->
                                new WipDiffRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        DataLevel.valueOf(rs.getString(5)),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getString(8)),
                        snapshotId,
                        projectId,
                        spaceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
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

    private DiffRow loadDiffRow(String spaceId, String projectId, String diffId) {
        return db.query(
                        """
                        SELECT id, space_id, project_id, base_kind, base_id, target_kind, target_id,
                               status, enrichment_job_id, enrichment_result_object_key,
                               summary_json, files_json, lifecycle_state, created_at
                        FROM two_point_diffs
                        WHERE id = ? AND project_id = ? AND space_id = ?
                        """,
                        (rs, i) ->
                                new DiffRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        DiffEndpointKind.valueOf(rs.getString(4)),
                                        rs.getString(5),
                                        DiffEndpointKind.valueOf(rs.getString(6)),
                                        rs.getString(7),
                                        TwoPointDiffStatus.valueOf(rs.getString(8)),
                                        rs.getString(9),
                                        rs.getString(10),
                                        rs.getString(11),
                                        rs.getString(12),
                                        rs.getString(13),
                                        rs.getTimestamp(14).toInstant()),
                        diffId,
                        projectId,
                        spaceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireDiffAndEndpointsActive(DiffRow row) {
        requireSnapshotLifecycleActive(row.lifecycleState());
        if (row.baseKind() == DiffEndpointKind.WIP) {
            requireLiveWipEndpointActive(row.spaceId(), row.projectId(), row.baseId());
        }
        if (row.targetKind() == DiffEndpointKind.WIP) {
            requireLiveWipEndpointActive(row.spaceId(), row.projectId(), row.targetId());
        }
    }

    private void requireLiveWipEndpointActive(String spaceId, String projectId, String snapshotId) {
        List<String> states =
                db.queryForList(
                        """
                        SELECT lifecycle_state
                        FROM wip_snapshots
                        WHERE id = ? AND project_id = ? AND space_id = ?
                        """,
                        String.class,
                        snapshotId,
                        projectId,
                        spaceId);
        if (states.isEmpty()) {
            throw new DomainException(
                    KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED,
                    Map.of("purged", false, "reason", "EXPIRED_OR_DELETED"));
        }
        requireSnapshotLifecycleActive(states.getFirst());
    }

    private String loadAsyncJobStatus(String jobId) {
        List<String> statuses =
                db.queryForList(
                        "SELECT status FROM async_jobs WHERE job_id = ?", String.class, jobId);
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private boolean isProvenNoChangesWip(WipDiffRow wip) {
        if (wip == null || !"NO_SHARED_CHANGES".equals(wip.captureState())) {
            return false;
        }
        ScopeDeclarationDto scope =
                GitProjectUtils.fromJson(
                        mapper, wip.scopeDeclarationJson(), ScopeDeclarationDto.class);
        return scope.hiddenItemCount() == 0 && scope.excludedItemCount() == 0;
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

    private List<TwoPointDiffFileEntry> parseFileEntries(String json) {
        try {
            return mapper.readValue(json, FILE_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Corrupt files_json in two_point_diffs", ex);
        }
    }

    private static Integer positiveIntOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isIntegralNumber()) {
            return null;
        }
        int val = node.intValue();
        return val >= 1 ? val : null;
    }

    private static boolean isGitLfsPointer(String text) {
        return text != null && text.startsWith("version https://git-lfs.github.com/spec/v1");
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
