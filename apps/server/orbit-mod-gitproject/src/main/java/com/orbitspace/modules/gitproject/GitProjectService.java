package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.AuditLogPort;
import com.orbitspace.kernel.BaseKind;
import com.orbitspace.kernel.ContentManifestStatus;
import com.orbitspace.kernel.DataLevel;
import com.orbitspace.kernel.DevicePrincipal;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.MessageKind;
import com.orbitspace.kernel.ObjectStoragePort;
import com.orbitspace.kernel.OutboxPublisherPort;
import com.orbitspace.kernel.ProjectScopePort;
import com.orbitspace.kernel.ResourceCardVerifierPort;
import com.orbitspace.kernel.ResourceLifecycleHook;
import com.orbitspace.kernel.ResourceLifecycleState;
import com.orbitspace.kernel.RevocationLedgerPort;
import com.orbitspace.kernel.SpaceAuthorizationPort;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.gitproject.GitDagObservationStore.CommitLookup;
import com.orbitspace.modules.gitproject.GitDagObservationStore.CommitPage;
import com.orbitspace.modules.gitproject.GitProjectDtos.AdvanceWorktreeEpochRequest;
import com.orbitspace.modules.gitproject.GitProjectDtos.CaptureReceiptDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.CaptureState;
import com.orbitspace.modules.gitproject.GitProjectDtos.CommitDetailDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ContentManifestEntryDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.CreateGitProjectRequest;
import com.orbitspace.modules.gitproject.GitProjectDtos.GitProjectDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.HeadDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ObservationSourceDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ObservedRefDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ParseJobDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ParseState;
import com.orbitspace.modules.gitproject.GitProjectDtos.PatchDescriptorDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ProjectGraphDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.PushStateRequest;
import com.orbitspace.modules.gitproject.GitProjectDtos.RegisterWorktreeRequest;
import com.orbitspace.modules.gitproject.GitProjectDtos.ReportProofRequest;
import com.orbitspace.modules.gitproject.GitProjectDtos.ReportSessionDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.ScopeDeclarationDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.SnapshotDetailDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.StagingSummaryDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.WipFreshness;
import com.orbitspace.modules.gitproject.GitProjectDtos.WipNodeDto;
import com.orbitspace.modules.gitproject.GitProjectDtos.WorktreeDto;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Domain service for Git projects, DAG observations, WIP monotonic capture protocol, and lifecycle. */
public class GitProjectService {
    private static final Duration REPORT_SESSION_TTL = Duration.ofMinutes(15);

    public record OpenedPatch(InputStream stream, long sizeBytes) {}

    private record CreateProjectInput(String spaceId, String name) {}

    private record RegisterWorktreeInput(
            String projectId, String deviceId, String localInstanceId) {}

    private record AdvanceEpochInput(
            String projectId, String worktreeId, String deviceId, long expectedWorktreeEpoch) {}

    private record RetryParseInput(
            String spaceId, String projectId, String snapshotId, String jobId) {}

    private record WorktreeRow(
            String id,
            String projectId,
            String userId,
            String deviceId,
            long worktreeEpoch,
            long currentCaptureSeq,
            String activeSnapshotId) {}

    private record ReportSessionRow(
            String reportId,
            String projectId,
            String spaceId,
            String worktreeId,
            String userId,
            String deviceId,
            long membershipEpoch,
            long worktreeEpoch,
            long captureSeq,
            String idempotencyKey,
            String payloadDigest,
            String payloadJson,
            boolean patchRequired,
            long expectedPatchSize,
            String expectedPatchSha256,
            String tempObjectKey,
            String status,
            String snapshotId,
            String receiptJson,
            Instant expiresAt) {}

    private record SnapshotRow(
            String id,
            String projectId,
            String spaceId,
            String worktreeId,
            String userId,
            String deviceId,
            long worktreeEpoch,
            long captureSeq,
            String idempotencyKey,
            String payloadDigest,
            Instant capturedAt,
            BaseKind baseKind,
            String baseCommitOid,
            String branchName,
            CaptureState captureState,
            DataLevel dataLevel,
            String patchObjectKey,
            long patchSizeBytes,
            String patchSha256,
            String stagingSummaryJson,
            String scopeDeclarationJson,
            String note,
            ParseState parseState,
            String parseJobId,
            String lifecycleState,
            long lifecycleVersion,
            WipFreshness freshness) {}

    private record JobRow(String jobId, String status, long inputVersion) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final TransactionTemplate tx;
    private final SpaceAuthorizationPort spaceAuth;
    private final ProjectScopePort projectScope;
    private final ObjectStoragePort objectStorage;
    private final AsyncJobPort asyncJobs;
    private final OutboxPublisherPort outbox;
    private final AuditLogPort auditLog;
    private final RevocationLedgerPort revocationLedger;
    private final List<ResourceLifecycleHook> lifecycleHooks;
    private final GitProjectIdempotencyStore idempotency;
    private final GitDagObservationStore dagStore;

    public GitProjectService(
            JdbcTemplate db,
            ObjectMapper mapper,
            PlatformTransactionManager txManager,
            SpaceAuthorizationPort spaceAuth,
            ProjectScopePort projectScope,
            ObjectStoragePort objectStorage,
            AsyncJobPort asyncJobs,
            OutboxPublisherPort outbox,
            AuditLogPort auditLog,
            RevocationLedgerPort revocationLedger,
            List<ResourceLifecycleHook> lifecycleHooks,
            GitProjectIdempotencyStore idempotency) {
        this.db = db;
        this.mapper = mapper;
        this.strictMapper = GitProjectUtils.strictMapper(mapper);
        this.tx = new TransactionTemplate(txManager);
        this.spaceAuth = spaceAuth;
        this.projectScope = projectScope;
        this.objectStorage = objectStorage;
        this.asyncJobs = asyncJobs;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.revocationLedger = revocationLedger;
        this.lifecycleHooks = List.copyOf(lifecycleHooks);
        this.idempotency = idempotency;
        this.dagStore = new GitDagObservationStore(db, mapper);
    }

    @Transactional
    public GitProjectDto createGitProject(
            String spaceId,
            String userId,
            long epoch,
            String idempotencyKey,
            String rawBody) {
        CreateGitProjectRequest req =
                GitProjectUtils.parseStrict(strictMapper, rawBody, CreateGitProjectRequest.class);
        String trimmed = req.name() == null ? "" : req.name().strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 120) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.ADMIN);

        return idempotency.execute(
                "CREATE_PROJECT",
                spaceId,
                userId,
                idempotencyKey,
                new CreateProjectInput(spaceId, req.name()),
                GitProjectDto.class,
                () -> {
                    spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.ADMIN);
                    String projectId = GitProjectUtils.newId();
                    db.update(
                            """
                            INSERT INTO git_projects(id, space_id, name, lifecycle_version, status)
                            VALUES (?, ?, ?, 1, 'ACTIVE')
                            """,
                            projectId,
                            spaceId,
                            trimmed);
                    auditLog.append(
                            new AuditLogPort.Entry(
                                    GitProjectUtils.requestId(),
                                    userId,
                                    "PROJECT_CREATE",
                                    projectId,
                                    "SUCCESS",
                                    Instant.now()));
                    return new GitProjectDto(spaceId, projectId, trimmed);
                });
    }

    @Transactional(readOnly = true)
    public ProjectGraphDto getProjectGraph(
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            String cursor,
            Integer limitParam) {
        int limit = limitParam == null ? 50 : limitParam;
        if (limit < 1 || limit > 200) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        Long beforeCommitDbId = parseCursor(cursor);

        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
        projectScope.requireProjectInSpace(projectId, spaceId);

        CommitPage page = dagStore.loadCommitNodesPage(projectId, beforeCommitDbId, limit);
        List<ObservedRefDto> refs =
                beforeCommitDbId == null ? dagStore.loadObservedRefs(projectId) : List.of();
        List<WipNodeDto> wipNodes =
                beforeCommitDbId == null ? loadActiveWipNodes(projectId) : List.of();
        return new ProjectGraphDto(
                projectId, page.commits(), refs, wipNodes, page.nextCursor());
    }

    @Transactional(readOnly = true)
    public CommitDetailDto getCommit(
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            String commitOid,
            String parentOid) {
        validateCommitAndParentOids(commitOid, parentOid);
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
        projectScope.requireProjectInSpace(projectId, spaceId);

        CommitLookup lookup = dagStore.loadSingleCommit(projectId, commitOid, parentOid);
        return new CommitDetailDto(
                projectId,
                lookup.commit(),
                lookup.selectedParentOid(),
                DataLevel.METADATA_ONLY,
                new ScopeDeclarationDto(List.of(), 0L, 0L),
                List.of(),
                null);
    }

    @Transactional(readOnly = true)
    public void verifyCommitPatchUnavailable(
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            String commitOid,
            String parentOid) {
        validateCommitAndParentOids(commitOid, parentOid);
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
        projectScope.requireProjectInSpace(projectId, spaceId);
        dagStore.loadSingleCommit(projectId, commitOid, parentOid);
        // P1 stores commit metadata observations only; single-commit patch bytes are absent.
        throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
    }

    @Transactional(readOnly = true)
    public SnapshotDetailDto getSnapshot(
            String spaceId, String projectId, String userId, long epoch, String snapshotId) {
        GitProjectUtils.requireId(snapshotId);
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
        projectScope.requireProjectInSpace(projectId, spaceId);

        SnapshotRow snap = loadSnapshot(spaceId, projectId, snapshotId, false);
        requireSnapshotActive(snap);

        ParseState liveParseState = resolveLiveParseState(snap.parseState(), snap.parseJobId());
        WipNodeDto wip = toWipNodeDto(snap, liveParseState);
        ScopeDeclarationDto scope =
                GitProjectUtils.fromJson(
                        mapper, snap.scopeDeclarationJson(), ScopeDeclarationDto.class);
        StagingSummaryDto staging =
                GitProjectUtils.fromJson(
                        mapper, snap.stagingSummaryJson(), StagingSummaryDto.class);
        List<ContentManifestEntryDto> manifest = loadContentManifest(snap.id());
        PatchDescriptorDto patch =
                snap.dataLevel() != DataLevel.METADATA_ONLY && snap.patchObjectKey() != null
                        ? new PatchDescriptorDto(snap.patchSha256(), snap.patchSizeBytes())
                        : null;

        return new SnapshotDetailDto(
                projectId,
                wip,
                scope,
                manifest,
                staging,
                patch,
                snap.note(),
                snap.parseJobId());
    }

    public OpenedPatch openSnapshotPatch(
            String spaceId, String projectId, String userId, long epoch, String snapshotId) {
        GitProjectUtils.requireId(snapshotId);
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
        projectScope.requireProjectInSpace(projectId, spaceId);

        SnapshotRow snap = loadSnapshot(spaceId, projectId, snapshotId, false);
        requireSnapshotActive(snap);
        if (snap.dataLevel() == DataLevel.METADATA_ONLY || snap.patchObjectKey() == null) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }
        try {
            InputStream stream =
                    objectStorage.openRead(
                            snap.patchObjectKey(),
                            () -> {
                                spaceAuth.requireMembership(
                                        spaceId, userId, epoch, SpaceRole.VIEWER);
                                projectScope.requireProjectInSpace(projectId, spaceId);
                                SnapshotRow current =
                                        loadSnapshot(spaceId, projectId, snapshotId, false);
                                requireSnapshotActive(current);
                                if (current.dataLevel() == DataLevel.METADATA_ONLY
                                        || current.patchObjectKey() == null) {
                                    throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
                                }
                            });
            return new OpenedPatch(stream, snap.patchSizeBytes());
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        }
    }

    @Transactional
    public ParseJobDto retrySnapshotParse(
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            String snapshotId,
            String jobId,
            String idempotencyKey) {
        return retryParseInternal(
                spaceId, projectId, userId, epoch, snapshotId, jobId, idempotencyKey, true);
    }

    @Transactional
    public ParseJobDto retryOwnReportParse(
            DevicePrincipal device,
            String projectId,
            String snapshotId,
            String jobId,
            String idempotencyKey) {
        if (!device.projectId().equals(projectId)
                || !device.scopes().contains("patch:write")) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        return retryParseInternal(
                device.spaceId(),
                projectId,
                device.userId(),
                device.membershipEpoch(),
                snapshotId,
                jobId,
                idempotencyKey,
                false);
    }

    private ParseJobDto retryParseInternal(
            String spaceId,
            String projectId,
            String actorUserId,
            long epoch,
            String snapshotId,
            String jobId,
            String idempotencyKey,
            boolean allowSpaceAdmin) {
        GitProjectUtils.requireId(snapshotId);
        GitProjectUtils.requireId(jobId);

        SpaceAuthorizationPort.Membership membership =
                spaceAuth.requireMembership(spaceId, actorUserId, epoch, SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, spaceId);

        return idempotency.execute(
                "RETRY_PARSE",
                snapshotId,
                actorUserId,
                idempotencyKey,
                new RetryParseInput(spaceId, projectId, snapshotId, jobId),
                ParseJobDto.class,
                () -> {
                    SnapshotRow snap = loadSnapshot(spaceId, projectId, snapshotId, true);
                    if (allowSpaceAdmin) {
                        if (!membership.role().atLeast(SpaceRole.ADMIN)
                                && !snap.userId().equals(actorUserId)) {
                            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
                        }
                    } else if (!snap.userId().equals(actorUserId)) {
                        throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
                    }
                    requireSnapshotActive(snap);

                    JobRow jobRow =
                            db.query(
                                            """
                                            SELECT job_id, status, input_version
                                            FROM async_jobs
                                            WHERE job_id = ? AND resource_id = ? AND space_id = ?
                                            FOR UPDATE
                                            """,
                                            (rs, i) ->
                                                    new JobRow(
                                                            rs.getString(1),
                                                            rs.getString(2),
                                                            rs.getLong(3)),
                                            jobId,
                                            snapshotId,
                                            spaceId)
                                    .stream()
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new DomainException(
                                                            KernelErrorCode.RESOURCE_NOT_FOUND));

                    if (!jobId.equals(snap.parseJobId()) || !"FAILED".equals(jobRow.status())) {
                        throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                    }
                    if (snap.patchObjectKey() == null
                            || !isImmutableObjectLive(snap.patchObjectKey())) {
                        throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                    }

                    String newJobId = GitProjectUtils.newId();
                    long nextInputVersion = jobRow.inputVersion() + 1;
                    db.update(
                            """
                            UPDATE wip_snapshots
                            SET parse_job_id = ?, parse_state = 'PENDING'
                            WHERE id = ?
                            """,
                            newJobId,
                            snapshotId);
                    asyncJobs.enqueue(
                            new AsyncJobPort.Job(
                                    newJobId,
                                    GitWipPatchParseProcessor.JOB_TYPE,
                                    spaceId,
                                    snapshotId,
                                    actorUserId,
                                    snap.patchObjectKey(),
                                    nextInputVersion,
                                    snap.lifecycleVersion()));
                    auditLog.append(
                            new AuditLogPort.Entry(
                                    GitProjectUtils.requestId(),
                                    actorUserId,
                                    "WIP_PARSE_RETRY",
                                    snapshotId,
                                    "SUCCESS",
                                    Instant.now()));
                    return new ParseJobDto(newJobId, snapshotId, ParseState.PENDING);
                });
    }

    @Transactional
    public WorktreeDto registerWorktree(
            DevicePrincipal device, String projectId, String idempotencyKey, String rawBody) {
        if (!device.projectId().equals(projectId)
                || !device.scopes().contains("metadata:write")) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        RegisterWorktreeRequest req =
                GitProjectUtils.parseStrict(strictMapper, rawBody, RegisterWorktreeRequest.class);
        String localInstanceId = GitProjectUtils.normalizeInstanceId(req.localInstanceId());

        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        return idempotency.execute(
                "REGISTER_WORKTREE",
                projectId + ":" + device.deviceId(),
                device.userId(),
                idempotencyKey,
                new RegisterWorktreeInput(projectId, device.deviceId(), localInstanceId),
                WorktreeDto.class,
                () -> {
                    spaceAuth.requireMembership(
                            device.spaceId(),
                            device.userId(),
                            device.membershipEpoch(),
                            SpaceRole.MEMBER);
                    projectScope.requireProjectInSpace(projectId, device.spaceId());

                    String candidateId = GitProjectUtils.newId();
                    db.update(
                            """
                            INSERT INTO worktree_states(
                                id, project_id, user_id, device_id, local_instance_id,
                                worktree_epoch, current_capture_seq
                            )
                            VALUES (?, ?, ?, ?, ?, 1, 0)
                            ON DUPLICATE KEY UPDATE id = id
                            """,
                            candidateId,
                            projectId,
                            device.userId(),
                            device.deviceId(),
                            localInstanceId);

                    WorktreeRow wt =
                            db.queryForObject(
                                    """
                                    SELECT id, project_id, user_id, device_id,
                                           worktree_epoch, current_capture_seq, active_wip_snapshot_id
                                    FROM worktree_states
                                    WHERE project_id = ? AND user_id = ? AND device_id = ? AND local_instance_id = ?
                                    FOR UPDATE
                                    """,
                                    (rs, i) ->
                                            new WorktreeRow(
                                                    rs.getString(1),
                                                    rs.getString(2),
                                                    rs.getString(3),
                                                    rs.getString(4),
                                                    rs.getLong(5),
                                                    rs.getLong(6),
                                                    rs.getString(7)),
                                    projectId,
                                    device.userId(),
                                    device.deviceId(),
                                    localInstanceId);
                    return new WorktreeDto(
                            wt.projectId(),
                            wt.id(),
                            wt.worktreeEpoch(),
                            wt.currentCaptureSeq(),
                            wt.activeSnapshotId());
                });
    }

    @Transactional
    public WorktreeDto advanceWorktreeEpoch(
            DevicePrincipal device,
            String projectId,
            String worktreeId,
            String idempotencyKey,
            String rawBody) {
        if (!device.projectId().equals(projectId)
                || !device.scopes().contains("metadata:write")) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        GitProjectUtils.requireId(worktreeId);
        AdvanceWorktreeEpochRequest req =
                GitProjectUtils.parseStrict(
                        strictMapper, rawBody, AdvanceWorktreeEpochRequest.class);
        GitProjectUtils.requirePositiveSeq(req.expectedWorktreeEpoch());

        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        return idempotency.execute(
                "ADVANCE_EPOCH",
                worktreeId,
                device.userId(),
                idempotencyKey,
                new AdvanceEpochInput(
                        projectId, worktreeId, device.deviceId(), req.expectedWorktreeEpoch()),
                WorktreeDto.class,
                () -> {
                    WorktreeRow wt = lockAndVerifyWorktree(worktreeId, projectId, device);
                    if (wt.worktreeEpoch() != req.expectedWorktreeEpoch()) {
                        throw new DomainException(KernelErrorCode.WORKTREE_EPOCH_STALE);
                    }
                    long nextEpoch = wt.worktreeEpoch() + 1;
                    int updated =
                            db.update(
                                    """
                                    UPDATE worktree_states
                                    SET worktree_epoch = ?, current_capture_seq = 0, active_wip_snapshot_id = NULL
                                    WHERE id = ? AND worktree_epoch = ?
                                    """,
                                    nextEpoch,
                                    worktreeId,
                                    wt.worktreeEpoch());
                    if (updated != 1) {
                        throw new DomainException(KernelErrorCode.WORKTREE_EPOCH_STALE);
                    }
                    db.update(
                            """
                            UPDATE wip_report_sessions
                            SET status = 'EXPIRED'
                            WHERE worktree_id = ? AND worktree_epoch < ? AND status = 'OPEN'
                            """,
                            worktreeId,
                            nextEpoch);
                    return new WorktreeDto(projectId, worktreeId, nextEpoch, 0L, null);
                });
    }

    @Transactional
    public ReportSessionDto createReport(
            DevicePrincipal device, String projectId, String rawBody) {
        GitProjectUtils.VerifiedPushState verified =
                GitProjectUtils.parseAndVerifyPushState(strictMapper, rawBody, false);
        PushStateRequest payload = verified.payload();

        verifyDevicePayloadBinding(device, projectId, payload);
        if (!device.scopes().contains("metadata:write")) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        boolean patchRequired = payload.content().dataLevel() == DataLevel.PATCH_ONLY;
        if (patchRequired && !device.scopes().contains("patch:write")) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }

        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        WorktreeRow wt = lockAndVerifyWorktree(payload.worktreeId(), projectId, device);
        String storedKey = GitProjectUtils.normalizeKey64(payload.idempotencyKey());

        ReportSessionRow existingSession =
                findReportSessionByIdempotency(
                        wt.id(), device.userId(), device.deviceId(), storedKey, true);
        if (existingSession != null) {
            if (!existingSession.payloadDigest().equals(payload.payloadDigest())) {
                throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return new ReportSessionDto(
                    existingSession.reportId(),
                    existingSession.expiresAt(),
                    existingSession.patchRequired());
        }

        SnapshotRow existingSnap =
                findSnapshotByIdempotency(
                        wt.id(), device.userId(), device.deviceId(), storedKey, true);
        if (existingSnap != null) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (idempotency.hasRecord(
                "REPORT_CLEAN", wt.id(), device.userId(), payload.idempotencyKey())) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }

        if (payload.worktreeEpoch() != wt.worktreeEpoch()) {
            throw new DomainException(KernelErrorCode.WORKTREE_EPOCH_STALE);
        }
        if (payload.captureSeq() <= wt.currentCaptureSeq()
                || hasSnapshotForEpochSeq(wt.id(), payload.worktreeEpoch(), payload.captureSeq())) {
            throw new DomainException(KernelErrorCode.CAPTURE_SEQ_STALE);
        }

        dagStore.persistObservations(
                projectId,
                payload.objectFormat(),
                device.deviceId(),
                device.userId(),
                payload.capturedAt(),
                payload.commits(),
                payload.refs());

        String reportId = GitProjectUtils.newId();
        Instant expiresAt =
                Instant.now().plus(REPORT_SESSION_TTL).truncatedTo(ChronoUnit.MICROS);
        long expectedPatchSize = patchRequired ? payload.content().patch().sizeBytes() : 0L;
        String expectedPatchSha256 =
                patchRequired ? payload.content().patch().sha256() : null;

        db.update(
                """
                INSERT INTO wip_report_sessions(
                    report_id, project_id, space_id, worktree_id, user_id, device_id,
                    membership_epoch, worktree_epoch, capture_seq, idempotency_key,
                    payload_digest, payload_json, patch_required, expected_patch_size,
                    expected_patch_sha256, status, expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, 'OPEN', ?)
                """,
                reportId,
                projectId,
                device.spaceId(),
                wt.id(),
                device.userId(),
                device.deviceId(),
                device.membershipEpoch(),
                payload.worktreeEpoch(),
                payload.captureSeq(),
                storedKey,
                payload.payloadDigest(),
                verified.canonicalJson(),
                patchRequired,
                expectedPatchSize,
                expectedPatchSha256,
                Timestamp.from(expiresAt));

        return new ReportSessionDto(reportId, expiresAt, patchRequired);
    }

    public void putTemporaryPatch(
            DevicePrincipal device, String projectId, String reportId, InputStream bytes) {
        if (!device.projectId().equals(projectId)
                || !device.scopes().contains("patch:write")) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        GitProjectUtils.requireId(reportId);

        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        ReportSessionRow session = loadAndVerifySession(reportId, projectId, device, false);
        if (!session.patchRequired()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if ("EXPIRED".equals(session.status())
                || ("OPEN".equals(session.status())
                        && !session.expiresAt().isAfter(Instant.now()))) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
        if ("COMPLETED".equals(session.status()) && session.snapshotId() != null) {
            SnapshotRow snap =
                    loadSnapshot(device.spaceId(), projectId, session.snapshotId(), false);
            requireSnapshotActive(snap);
        }

        ObjectStoragePort.StoredObject stored;
        try {
            stored =
                    objectStorage.writeTemporary(
                            reportId, bytes, GitProjectUtils.MAX_PATCH_BYTES);
        } catch (IOException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        if (stored.sizeBytes() != session.expectedPatchSize()
                || !stored.sha256().equals(session.expectedPatchSha256())) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }

        tx.executeWithoutResult(
                status -> {
                    ReportSessionRow locked =
                            loadAndVerifySession(reportId, projectId, device, true);
                    if ("OPEN".equals(locked.status())) {
                        if (!locked.expiresAt().isAfter(Instant.now())) {
                            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                        }
                        db.update(
                                """
                                UPDATE wip_report_sessions
                                SET temp_object_key = ?
                                WHERE report_id = ? AND status = 'OPEN'
                                """,
                                stored.objectKey(),
                                reportId);
                    } else if ("EXPIRED".equals(locked.status())) {
                        throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                    }
                });
    }

    public CaptureReceiptDto completeReport(
            DevicePrincipal device, String projectId, String reportId, String rawBody) {
        if (!device.projectId().equals(projectId)
                || !device.scopes().contains("metadata:write")) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        GitProjectUtils.requireId(reportId);
        ReportProofRequest proof =
                GitProjectUtils.parseStrict(strictMapper, rawBody, ReportProofRequest.class);
        if (!GitProjectUtils.isValidSha256(proof.payloadDigest())) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String storedKey = GitProjectUtils.normalizeKey64(proof.idempotencyKey());

        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        ReportSessionRow preSession = loadAndVerifySession(reportId, projectId, device, false);
        if (preSession.patchRequired() && !device.scopes().contains("patch:write")) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }

        if (!storedKey.equals(preSession.idempotencyKey())
                || !proof.payloadDigest().equals(preSession.payloadDigest())) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }

        if ("COMPLETED".equals(preSession.status()) && preSession.receiptJson() != null) {
            return GitProjectUtils.fromJson(
                    mapper, preSession.receiptJson(), CaptureReceiptDto.class);
        }

        WorktreeRow preWt = loadWorktree(preSession.worktreeId(), projectId, device, false);
        if (preSession.worktreeEpoch() != preWt.worktreeEpoch()) {
            throw new DomainException(KernelErrorCode.WORKTREE_EPOCH_STALE);
        }
        if (preSession.captureSeq() <= preWt.currentCaptureSeq()) {
            throw new DomainException(KernelErrorCode.CAPTURE_SEQ_STALE);
        }
        if ("EXPIRED".equals(preSession.status())
                || !preSession.expiresAt().isAfter(Instant.now())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }

        ObjectStoragePort.StoredObject promoted = null;
        if (preSession.patchRequired()) {
            if (preSession.tempObjectKey() == null) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            try {
                promoted =
                        objectStorage.promote(
                                preSession.tempObjectKey(),
                                preSession.expectedPatchSize(),
                                preSession.expectedPatchSha256());
            } catch (IOException ex) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }

        final ObjectStoragePort.StoredObject immutablePatch = promoted;
        return tx.execute(
                status -> {
                    spaceAuth.requireMembership(
                            device.spaceId(),
                            device.userId(),
                            device.membershipEpoch(),
                            SpaceRole.MEMBER);
                    projectScope.requireProjectInSpace(projectId, device.spaceId());

                    ReportSessionRow session =
                            loadAndVerifySession(reportId, projectId, device, true);
                    WorktreeRow wt = loadWorktree(session.worktreeId(), projectId, device, true);

                    SnapshotRow existingSnap =
                            findSnapshotByIdempotency(
                                    wt.id(), device.userId(), device.deviceId(), storedKey, true);
                    if (existingSnap != null) {
                        if (!existingSnap.payloadDigest().equals(proof.payloadDigest())) {
                            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                        }
                        if (session.receiptJson() != null) {
                            return GitProjectUtils.fromJson(
                                    mapper, session.receiptJson(), CaptureReceiptDto.class);
                        }
                        return buildCaptureReceipt(existingSnap);
                    }
                    if ("COMPLETED".equals(session.status()) && session.receiptJson() != null) {
                        if (!storedKey.equals(session.idempotencyKey())
                                || !proof.payloadDigest().equals(session.payloadDigest())) {
                            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
                        }
                        return GitProjectUtils.fromJson(
                                mapper, session.receiptJson(), CaptureReceiptDto.class);
                    }

                    if (session.worktreeEpoch() != wt.worktreeEpoch()) {
                        throw new DomainException(KernelErrorCode.WORKTREE_EPOCH_STALE);
                    }
                    if (session.captureSeq() <= wt.currentCaptureSeq()) {
                        throw new DomainException(KernelErrorCode.CAPTURE_SEQ_STALE);
                    }
                    if (!"OPEN".equals(session.status())
                            || !session.expiresAt().isAfter(Instant.now())) {
                        throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
                    }

                    PushStateRequest payload =
                            GitProjectUtils.fromJson(
                                    mapper, session.payloadJson(), PushStateRequest.class);
                    String snapshotId = GitProjectUtils.newId();

                    if (session.patchRequired()) {
                        if (immutablePatch == null) {
                            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                        }
                        pinStorageReference(
                                "WIP_PATCH", snapshotId, immutablePatch.objectKey());
                    }

                    String parseJobId =
                            session.patchRequired() ? GitProjectUtils.newId() : null;
                    ParseState initialParseState =
                            session.patchRequired()
                                    ? ParseState.PENDING
                                    : ParseState.NOT_REQUESTED;

                    insertSnapshotAndManifest(
                            snapshotId,
                            session,
                            payload,
                            immutablePatch,
                            parseJobId,
                            initialParseState);

                    int wtUpdated =
                            db.update(
                                    """
                                    UPDATE worktree_states
                                    SET current_capture_seq = ?,
                                        active_wip_snapshot_id = ?,
                                        last_reported_at = CURRENT_TIMESTAMP(6)
                                    WHERE id = ? AND worktree_epoch = ? AND current_capture_seq < ?
                                    """,
                                    session.captureSeq(),
                                    snapshotId,
                                    wt.id(),
                                    session.worktreeEpoch(),
                                    session.captureSeq());
                    if (wtUpdated != 1) {
                        throw new DomainException(KernelErrorCode.CAPTURE_SEQ_STALE);
                    }

                    if (session.patchRequired()) {
                        asyncJobs.enqueue(
                                new AsyncJobPort.Job(
                                        parseJobId,
                                        GitWipPatchParseProcessor.JOB_TYPE,
                                        session.spaceId(),
                                        snapshotId,
                                        session.userId(),
                                        immutablePatch.objectKey(),
                                        1L,
                                        1L));
                    }

                    CaptureReceiptDto receipt =
                            new CaptureReceiptDto(
                                    wt.id(),
                                    session.worktreeEpoch(),
                                    session.captureSeq(),
                                    snapshotId,
                                    snapshotUrl(session.spaceId(), projectId, snapshotId),
                                    initialParseState);

                    db.update(
                            """
                            UPDATE wip_report_sessions
                            SET status = 'COMPLETED', snapshot_id = ?, receipt_json = CAST(? AS JSON)
                            WHERE report_id = ?
                            """,
                            snapshotId,
                            GitProjectUtils.toJson(mapper, receipt),
                            reportId);

                    publishWipCreatedOutbox(session, projectId, snapshotId);
                    auditLog.append(
                            new AuditLogPort.Entry(
                                    GitProjectUtils.requestId(),
                                    session.userId(),
                                    "WIP_CAPTURE_COMPLETE",
                                    snapshotId,
                                    "SUCCESS",
                                    Instant.now()));
                    return receipt;
                });
    }

    @Transactional
    public WorktreeDto reportClean(
            DevicePrincipal device, String projectId, String worktreeId, String rawBody) {
        GitProjectUtils.VerifiedPushState verified =
                GitProjectUtils.parseAndVerifyPushState(strictMapper, rawBody, true);
        PushStateRequest payload = verified.payload();

        if (!worktreeId.equals(payload.worktreeId())) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        verifyDevicePayloadBinding(device, projectId, payload);
        if (!device.scopes().contains("metadata:write")) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }

        spaceAuth.requireMembership(
                device.spaceId(), device.userId(), device.membershipEpoch(), SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, device.spaceId());

        WorktreeRow wt = lockAndVerifyWorktree(worktreeId, projectId, device);
        String storedKey = GitProjectUtils.normalizeKey64(payload.idempotencyKey());
        if (findReportSessionByIdempotency(
                        wt.id(), device.userId(), device.deviceId(), storedKey, false)
                != null
                || findSnapshotByIdempotency(
                                wt.id(), device.userId(), device.deviceId(), storedKey, false)
                        != null) {
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        }

        return idempotency.executeWithDigest(
                "REPORT_CLEAN",
                worktreeId,
                device.userId(),
                payload.idempotencyKey(),
                payload.payloadDigest(),
                WorktreeDto.class,
                () -> {
                    if (payload.worktreeEpoch() != wt.worktreeEpoch()) {
                        throw new DomainException(KernelErrorCode.WORKTREE_EPOCH_STALE);
                    }
                    if (payload.captureSeq() <= wt.currentCaptureSeq()
                            || hasSnapshotForEpochSeq(
                                    wt.id(), payload.worktreeEpoch(), payload.captureSeq())) {
                        throw new DomainException(KernelErrorCode.CAPTURE_SEQ_STALE);
                    }

                    dagStore.persistObservations(
                            projectId,
                            payload.objectFormat(),
                            device.deviceId(),
                            device.userId(),
                            payload.capturedAt(),
                            payload.commits(),
                            payload.refs());

                    int updated =
                            db.update(
                                    """
                                    UPDATE worktree_states
                                    SET current_capture_seq = ?,
                                        active_wip_snapshot_id = NULL,
                                        last_reported_at = CURRENT_TIMESTAMP(6)
                                    WHERE id = ? AND worktree_epoch = ? AND current_capture_seq < ?
                                    """,
                                    payload.captureSeq(),
                                    worktreeId,
                                    payload.worktreeEpoch(),
                                    payload.captureSeq());
                    if (updated != 1) {
                        throw new DomainException(KernelErrorCode.CAPTURE_SEQ_STALE);
                    }

                    auditLog.append(
                            new AuditLogPort.Entry(
                                    GitProjectUtils.requestId(),
                                    device.userId(),
                                    "WIP_WORKTREE_CLEAN",
                                    worktreeId,
                                    "SUCCESS",
                                    Instant.now()));
                    return new WorktreeDto(
                            projectId, worktreeId, wt.worktreeEpoch(), payload.captureSeq(), null);
                });
    }

    /** Marks active WIP snapshots stale when their worktree has not reported within stale_after_seconds. */
    @Transactional
    public int markInactiveSnapshotsStale(Instant now) {
        Timestamp ts = GitProjectUtils.toMysqlTimestamp(now);
        return db.update(
                """
                UPDATE wip_snapshots s
                JOIN worktree_states w ON w.id = s.worktree_id
                SET s.freshness = 'STALE'
                WHERE s.lifecycle_state = 'ACTIVE'
                  AND s.freshness = 'ACTIVE'
                  AND TIMESTAMPADD(SECOND, w.stale_after_seconds, COALESCE(w.last_reported_at, s.captured_at)) <= ?
                """,
                ts);
    }

    /** Expires unreferenced ACTIVE WIP snapshots created at or before cutoff. */
    public int expireUnreferencedSnapshots(Instant cutoff, int limit) {
        int bounded = Math.min(Math.max(limit, 1), 500);
        List<String> candidates =
                db.queryForList(
                        """
                        SELECT id
                        FROM wip_snapshots
                        WHERE lifecycle_state = 'ACTIVE' AND reference_count = 0 AND created_at <= ?
                        ORDER BY created_at ASC, id ASC
                        LIMIT ?
                        """,
                        String.class,
                        GitProjectUtils.toMysqlTimestamp(cutoff),
                        bounded);
        int expired = 0;
        for (String snapshotId : candidates) {
            Boolean changed =
                    tx.execute(
                            status -> expireSnapshotLocked(snapshotId, cutoff));
            if (Boolean.TRUE.equals(changed)) {
                expired++;
            }
        }
        return expired;
    }

    /** Immediately expires a specific unreferenced ACTIVE WIP snapshot. */
    @Transactional
    public boolean expireSnapshot(String snapshotId) {
        return expireSnapshotLocked(snapshotId, null);
    }

    private boolean expireSnapshotLocked(String snapshotId, Instant cutoff) {
        SnapshotRow snap =
                db.query(
                                snapshotSelectSql() + " WHERE id = ? FOR UPDATE",
                                this::mapSnapshotRow,
                                snapshotId)
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (snap == null
                || !"ACTIVE".equals(snap.lifecycleState())) {
            return false;
        }
        long refCount =
                db.queryForObject(
                        "SELECT reference_count FROM wip_snapshots WHERE id = ?",
                        Long.class,
                        snapshotId);
        if (refCount != 0) {
            return false;
        }
        if (cutoff != null) {
            Instant createdAt =
                    db.queryForObject(
                                    "SELECT created_at FROM wip_snapshots WHERE id = ?",
                                    Timestamp.class,
                                    snapshotId)
                            .toInstant();
            if (createdAt.isAfter(cutoff)) {
                return false;
            }
        }

        long nextVersion = snap.lifecycleVersion() + 1;
        int updated =
                db.update(
                        """
                        UPDATE wip_snapshots
                        SET lifecycle_state = 'EXPIRED_OR_DELETED',
                            lifecycle_version = ?,
                            parse_state = CASE
                                WHEN parse_state IN ('PENDING', 'RUNNING', 'RETRY_WAIT') THEN 'CANCELLED'
                                ELSE parse_state
                            END
                        WHERE id = ? AND lifecycle_state = 'ACTIVE' AND reference_count = 0
                        """,
                        nextVersion,
                        snapshotId);
        if (updated != 1) {
            return false;
        }
        clearActiveWorktreePointer(snapshotId);
        detachRefsAndCancelJobs(snapshotId, snap.spaceId());
        notifyLifecycleHooks(
                snap.spaceId(),
                snap.projectId(),
                snapshotId,
                ResourceLifecycleState.EXPIRED_OR_DELETED,
                nextVersion);
        return true;
    }

    /** Force-purges a snapshot, appends RESOURCE_FORCE_PURGE to RevocationLedger, and invokes lifecycle hooks. */
    @Transactional
    public void forcePurgeSnapshot(String spaceId, String snapshotId, String actorUserId) {
        GitProjectUtils.requireId(snapshotId);
        SnapshotRow snap =
                db.query(
                                snapshotSelectSql() + " WHERE id = ? AND space_id = ? FOR UPDATE",
                                this::mapSnapshotRow,
                                snapshotId,
                                spaceId)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
        if ("FORCE_PURGED".equals(snap.lifecycleState())) {
            return;
        }

        long nextVersion = snap.lifecycleVersion() + 1;
        db.update(
                """
                UPDATE wip_snapshots
                SET lifecycle_state = 'FORCE_PURGED',
                    lifecycle_version = ?,
                    parse_state = 'CANCELLED'
                WHERE id = ? AND space_id = ?
                """,
                nextVersion,
                snapshotId,
                spaceId);
        clearActiveWorktreePointer(snapshotId);
        detachRefsAndCancelJobs(snapshotId, spaceId);
        revocationLedger.append(
                new RevocationLedgerPort.Revocation(
                        GitProjectUtils.newId(),
                        RevocationLedgerPort.Target.RESOURCE_FORCE_PURGE,
                        spaceId,
                        snapshotId,
                        nextVersion,
                        Instant.now()));
        notifyLifecycleHooks(
                spaceId,
                snap.projectId(),
                snapshotId,
                ResourceLifecycleState.FORCE_PURGED,
                nextVersion);
        if (actorUserId != null && !actorUserId.isBlank()) {
            auditLog.append(
                    new AuditLogPort.Entry(
                            GitProjectUtils.requestId(),
                            actorUserId,
                            "WIP_SNAPSHOT_FORCE_PURGE",
                            snapshotId,
                            "SUCCESS",
                            Instant.now()));
        }
    }

    /** Idempotent replay handler for RevocationLedgerPort.Target.RESOURCE_FORCE_PURGE. */
    public void replayResourceForcePurge(RevocationLedgerPort.Entry entry) {
        RevocationLedgerPort.Revocation r = entry.revocation();
        if (r.target() != RevocationLedgerPort.Target.RESOURCE_FORCE_PURGE) {
            throw new IllegalArgumentException("Unsupported revocation target: " + r.target());
        }
        SnapshotRow snap =
                db.query(
                                snapshotSelectSql() + " WHERE id = ? AND space_id = ? FOR UPDATE",
                                this::mapSnapshotRow,
                                r.targetId(),
                                r.spaceId())
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (snap == null
                || ("FORCE_PURGED".equals(snap.lifecycleState())
                        && snap.lifecycleVersion() >= r.version())) {
            return;
        }

        int updated =
                db.update(
                        """
                        UPDATE wip_snapshots
                        SET lifecycle_state = 'FORCE_PURGED',
                            lifecycle_version = ?,
                            parse_state = 'CANCELLED'
                        WHERE id = ? AND space_id = ? AND lifecycle_version <= ?
                        """,
                        r.version(),
                        r.targetId(),
                        r.spaceId(),
                        r.version());
        if (updated == 1) {
            clearActiveWorktreePointer(r.targetId());
            detachRefsAndCancelJobs(r.targetId(), r.spaceId());
            notifyLifecycleHooks(
                    r.spaceId(),
                    snap.projectId(),
                    r.targetId(),
                    ResourceLifecycleState.FORCE_PURGED,
                    r.version());
        }
    }

    private void clearActiveWorktreePointer(String snapshotId) {
        db.update(
                """
                UPDATE worktree_states
                SET active_wip_snapshot_id = NULL
                WHERE active_wip_snapshot_id = ?
                """,
                snapshotId);
    }

    private void detachRefsAndCancelJobs(String snapshotId, String spaceId) {
        db.update(
                """
                DELETE FROM storage_object_references
                WHERE owner_type IN ('WIP_PATCH', 'WIP_PARSE_RESULT') AND owner_id = ?
                """,
                snapshotId);
        List<String> activeJobIds =
                db.queryForList(
                        """
                        SELECT job_id
                        FROM async_jobs
                        WHERE resource_id = ? AND space_id = ?
                          AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                        """,
                        String.class,
                        snapshotId,
                        spaceId);
        for (String jobId : activeJobIds) {
            asyncJobs.cancel(jobId);
        }
    }

    private void notifyLifecycleHooks(
            String spaceId,
            String projectId,
            String snapshotId,
            ResourceLifecycleState state,
            long lifecycleVersion) {
        var change =
                new ResourceLifecycleHook.Change(
                        spaceId,
                        new ResourceCardVerifierPort.Reference(
                                MessageKind.WIP_REF, projectId, snapshotId),
                        state,
                        lifecycleVersion);
        for (ResourceLifecycleHook hook : lifecycleHooks) {
            hook.onLifecycleChanged(change);
        }
    }

    private void insertSnapshotAndManifest(
            String snapshotId,
            ReportSessionRow session,
            PushStateRequest payload,
            ObjectStoragePort.StoredObject immutablePatch,
            String parseJobId,
            ParseState initialParseState) {
        String patchKey = immutablePatch != null ? immutablePatch.objectKey() : null;
        long patchSize = immutablePatch != null ? immutablePatch.sizeBytes() : 0L;
        String patchSha = immutablePatch != null ? immutablePatch.sha256() : null;

        try {
            db.update(
                    """
                    INSERT INTO wip_snapshots(
                        id, project_id, space_id, worktree_id, user_id, device_id,
                        worktree_epoch, capture_seq, idempotency_key, payload_digest,
                        captured_at, object_format, base_kind, base_commit_oid, branch_name,
                        capture_state, data_level, patch_object_key, patch_size_bytes, patch_sha256,
                        staging_summary_json, scope_declaration_json, note,
                        parse_state, parse_job_id, lifecycle_state, lifecycle_version, freshness, reference_count
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        CAST(? AS JSON), CAST(? AS JSON), ?,
                        ?, ?, 'ACTIVE', 1, 'ACTIVE', 0
                    )
                    """,
                    snapshotId,
                    session.projectId(),
                    session.spaceId(),
                    session.worktreeId(),
                    session.userId(),
                    session.deviceId(),
                    session.worktreeEpoch(),
                    session.captureSeq(),
                    session.idempotencyKey(),
                    session.payloadDigest(),
                    GitProjectUtils.toMysqlTimestamp(payload.capturedAt()),
                    payload.objectFormat(),
                    payload.head().baseKind().name(),
                    payload.head().baseCommitOid(),
                    payload.head().branch(),
                    payload.captureState().name(),
                    payload.content().dataLevel().name(),
                    patchKey,
                    patchSize,
                    patchSha,
                    GitProjectUtils.toJson(mapper, payload.stagingSummary()),
                    GitProjectUtils.toJson(mapper, payload.scopeDeclaration()),
                    payload.note(),
                    initialParseState.name(),
                    parseJobId);
        } catch (DuplicateKeyException ex) {
            throw new DomainException(KernelErrorCode.CAPTURE_SEQ_STALE);
        }

        List<ContentManifestEntryDto> entries = payload.contentManifest();
        if (!entries.isEmpty()) {
            db.batchUpdate(
                    """
                    INSERT INTO content_manifests(snapshot_id, file_path, status, sha256_hex)
                    VALUES (?, ?, ?, ?)
                    """,
                    entries,
                    entries.size(),
                    (ps, entry) -> {
                        ps.setString(1, snapshotId);
                        ps.setString(2, entry.path());
                        ps.setString(3, entry.status().name());
                        ps.setString(4, entry.sha256());
                    });
        }
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

    private void publishWipCreatedOutbox(
            ReportSessionRow session, String projectId, String snapshotId) {
        Map<String, Object> eventPayload =
                Map.of(
                        "spaceId", session.spaceId(),
                        "projectId", projectId,
                        "worktreeId", session.worktreeId(),
                        "snapshotId", snapshotId,
                        "worktreeEpoch", session.worktreeEpoch(),
                        "captureSeq", session.captureSeq());
        outbox.publish(
                new OutboxPublisherPort.Event(
                        "wip-" + GitProjectUtils.sha256Hex(snapshotId),
                        "GIT_WIP_SNAPSHOT_CREATED",
                        "WIP_SNAPSHOT",
                        snapshotId,
                        GitProjectUtils.toJson(mapper, eventPayload),
                        Instant.now()));
    }

    private List<WipNodeDto> loadActiveWipNodes(String projectId) {
        List<SnapshotRow> rows =
                db.query(
                        """
                        SELECT s.id, s.project_id, s.space_id, s.worktree_id, s.user_id, s.device_id,
                               s.worktree_epoch, s.capture_seq, s.idempotency_key, s.payload_digest,
                               s.captured_at, s.base_kind, s.base_commit_oid, s.branch_name,
                               s.capture_state, s.data_level, s.patch_object_key, s.patch_size_bytes,
                               s.patch_sha256, s.staging_summary_json, s.scope_declaration_json, s.note,
                               s.parse_state, s.parse_job_id, s.lifecycle_state, s.lifecycle_version,
                               s.freshness
                        FROM worktree_states w
                        JOIN wip_snapshots s ON s.id = w.active_wip_snapshot_id
                        WHERE w.project_id = ? AND s.project_id = ? AND s.lifecycle_state = 'ACTIVE'
                        ORDER BY s.captured_at DESC, s.id ASC
                        """,
                        this::mapSnapshotRow,
                        projectId,
                        projectId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, ParseState> jobStates = loadLiveJobStates(rows);
        List<WipNodeDto> result = new ArrayList<>(rows.size());
        for (SnapshotRow row : rows) {
            ParseState state = resolveLiveParseState(row.parseState(), row.parseJobId(), jobStates);
            result.add(toWipNodeDto(row, state));
        }
        return List.copyOf(result);
    }

    private Map<String, ParseState> loadLiveJobStates(List<SnapshotRow> rows) {
        List<String> jobIds =
                rows.stream()
                        .filter(
                                r ->
                                        r.parseJobId() != null
                                                && r.parseState() != ParseState.SUCCEEDED
                                                && r.parseState() != ParseState.CANCELLED)
                        .map(SnapshotRow::parseJobId)
                        .distinct()
                        .toList();
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(jobIds.size(), "?"));
        Map<String, ParseState> states = new HashMap<>();
        db.query(
                "SELECT job_id, status FROM async_jobs WHERE job_id IN (" + placeholders + ")",
                (rs, i) -> {
                    states.put(rs.getString(1), ParseState.valueOf(rs.getString(2)));
                    return null;
                },
                jobIds.toArray());
        return states;
    }

    private ParseState resolveLiveParseState(ParseState storedState, String parseJobId) {
        if (parseJobId == null
                || storedState == ParseState.SUCCEEDED
                || storedState == ParseState.CANCELLED) {
            return storedState;
        }
        List<String> statuses =
                db.queryForList(
                        "SELECT status FROM async_jobs WHERE job_id = ?",
                        String.class,
                        parseJobId);
        return statuses.isEmpty() ? storedState : ParseState.valueOf(statuses.getFirst());
    }

    private static ParseState resolveLiveParseState(
            ParseState storedState, String parseJobId, Map<String, ParseState> jobStates) {
        if (parseJobId == null
                || storedState == ParseState.SUCCEEDED
                || storedState == ParseState.CANCELLED) {
            return storedState;
        }
        return jobStates.getOrDefault(parseJobId, storedState);
    }

    private List<ContentManifestEntryDto> loadContentManifest(String snapshotId) {
        return db.query(
                """
                SELECT file_path, status, sha256_hex
                FROM content_manifests
                WHERE snapshot_id = ?
                ORDER BY id ASC
                """,
                (rs, i) ->
                        new ContentManifestEntryDto(
                                rs.getString(1),
                                ContentManifestStatus.valueOf(rs.getString(2)),
                                rs.getString(3)),
                snapshotId);
    }

    private SnapshotRow loadSnapshot(
            String spaceId, String projectId, String snapshotId, boolean forUpdate) {
        String sql =
                snapshotSelectSql()
                        + " WHERE id = ? AND project_id = ? AND space_id = ?"
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(sql, this::mapSnapshotRow, snapshotId, projectId, spaceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private SnapshotRow findSnapshotByIdempotency(
            String worktreeId,
            String userId,
            String deviceId,
            String storedKey,
            boolean forUpdate) {
        String sql =
                snapshotSelectSql()
                        + """
                         WHERE worktree_id = ? AND user_id = ? AND device_id = ? AND idempotency_key = ?
                        """
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(sql, this::mapSnapshotRow, worktreeId, userId, deviceId, storedKey)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private ReportSessionRow findReportSessionByIdempotency(
            String worktreeId,
            String userId,
            String deviceId,
            String storedKey,
            boolean forUpdate) {
        String sql =
                reportSessionSelectSql()
                        + """
                         WHERE worktree_id = ? AND user_id = ? AND device_id = ? AND idempotency_key = ?
                        """
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(sql, this::mapReportSessionRow, worktreeId, userId, deviceId, storedKey)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private ReportSessionRow loadAndVerifySession(
            String reportId, String projectId, DevicePrincipal device, boolean forUpdate) {
        String sql =
                reportSessionSelectSql()
                        + " WHERE report_id = ?"
                        + (forUpdate ? " FOR UPDATE" : "");
        ReportSessionRow session =
                db.query(sql, this::mapReportSessionRow, reportId)
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

    private WorktreeRow lockAndVerifyWorktree(
            String worktreeId, String projectId, DevicePrincipal device) {
        return loadWorktree(worktreeId, projectId, device, true);
    }

    private WorktreeRow loadWorktree(
            String worktreeId, String projectId, DevicePrincipal device, boolean forUpdate) {
        String sql =
                """
                SELECT id, project_id, user_id, device_id,
                       worktree_epoch, current_capture_seq, active_wip_snapshot_id
                FROM worktree_states
                WHERE id = ?
                """
                        + (forUpdate ? " FOR UPDATE" : "");
        WorktreeRow wt =
                db.query(
                                sql,
                                (rs, i) ->
                                        new WorktreeRow(
                                                rs.getString(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                rs.getString(4),
                                                rs.getLong(5),
                                                rs.getLong(6),
                                                rs.getString(7)),
                                worktreeId)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
        if (!wt.projectId().equals(projectId)
                || !wt.userId().equals(device.userId())
                || !wt.deviceId().equals(device.deviceId())) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        return wt;
    }

    private boolean hasSnapshotForEpochSeq(
            String worktreeId, long worktreeEpoch, long captureSeq) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        """
                        SELECT EXISTS(
                            SELECT 1 FROM wip_snapshots
                            WHERE worktree_id = ? AND worktree_epoch = ? AND capture_seq = ?
                        )
                        """,
                        Boolean.class,
                        worktreeId,
                        worktreeEpoch,
                        captureSeq));
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

    private static void verifyDevicePayloadBinding(
            DevicePrincipal device, String projectId, PushStateRequest payload) {
        if (!payload.spaceId().equals(device.spaceId())
                || !payload.projectId().equals(projectId)
                || !payload.projectId().equals(device.projectId())
                || !payload.deviceId().equals(device.deviceId())
                || payload.membershipEpoch() != device.membershipEpoch()) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireSnapshotActive(SnapshotRow snap) {
        if ("FORCE_PURGED".equals(snap.lifecycleState())) {
            throw new DomainException(
                    KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED,
                    Map.of("purged", true, "reason", "FORCE_PURGED"));
        }
        if ("EXPIRED_OR_DELETED".equals(snap.lifecycleState())) {
            throw new DomainException(
                    KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED,
                    Map.of("purged", false, "reason", "EXPIRED_OR_DELETED"));
        }
        if (!"ACTIVE".equals(snap.lifecycleState())) {
            throw new DomainException(KernelErrorCode.RESOURCE_EXPIRED_OR_PURGED);
        }
    }

    private static void validateCommitAndParentOids(String commitOid, String parentOid) {
        if (!GitProjectUtils.isAnyValidOid(commitOid)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (parentOid != null && !GitProjectUtils.isAnyValidOid(parentOid)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.isBlank()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        try {
            long value = Long.parseLong(cursor.strip());
            if (value < 1) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
    }

    private static String snapshotUrl(String spaceId, String projectId, String snapshotId) {
        return "/spaces/"
                + URLEncoder.encode(spaceId, StandardCharsets.UTF_8)
                + "?projectId="
                + URLEncoder.encode(projectId, StandardCharsets.UTF_8)
                + "&snapshotId="
                + URLEncoder.encode(snapshotId, StandardCharsets.UTF_8);
    }

    private static WipNodeDto toWipNodeDto(SnapshotRow snap, ParseState liveParseState) {
        return new WipNodeDto(
                snap.id(),
                snap.worktreeId(),
                snap.worktreeEpoch(),
                snap.captureSeq(),
                new ObservationSourceDto(snap.deviceId(), snap.userId(), snap.capturedAt()),
                new HeadDto(snap.baseKind(), snap.baseCommitOid(), snap.branchName()),
                snap.capturedAt(),
                snap.freshness(),
                snap.captureState(),
                liveParseState,
                snap.dataLevel());
    }

    private static CaptureReceiptDto buildCaptureReceipt(SnapshotRow snap) {
        return new CaptureReceiptDto(
                snap.worktreeId(),
                snap.worktreeEpoch(),
                snap.captureSeq(),
                snap.id(),
                snapshotUrl(snap.spaceId(), snap.projectId(), snap.id()),
                snap.parseState());
    }

    private static String reportSessionSelectSql() {
        return """
                SELECT report_id, project_id, space_id, worktree_id, user_id, device_id,
                       membership_epoch, worktree_epoch, capture_seq, idempotency_key,
                       payload_digest, payload_json, patch_required, expected_patch_size,
                       expected_patch_sha256, temp_object_key, status, snapshot_id,
                       receipt_json, expires_at
                FROM wip_report_sessions
                """;
    }

    private ReportSessionRow mapReportSessionRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ReportSessionRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getLong(7),
                rs.getLong(8),
                rs.getLong(9),
                rs.getString(10),
                rs.getString(11),
                rs.getString(12),
                rs.getBoolean(13),
                rs.getLong(14),
                rs.getString(15),
                rs.getString(16),
                rs.getString(17),
                rs.getString(18),
                rs.getString(19),
                rs.getTimestamp(20).toInstant());
    }

    private static String snapshotSelectSql() {
        return """
                SELECT id, project_id, space_id, worktree_id, user_id, device_id,
                       worktree_epoch, capture_seq, idempotency_key, payload_digest,
                       captured_at, base_kind, base_commit_oid, branch_name,
                       capture_state, data_level, patch_object_key, patch_size_bytes,
                       patch_sha256, staging_summary_json, scope_declaration_json, note,
                       parse_state, parse_job_id, lifecycle_state, lifecycle_version, freshness
                FROM wip_snapshots
                """;
    }

    private SnapshotRow mapSnapshotRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SnapshotRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getLong(7),
                rs.getLong(8),
                rs.getString(9),
                rs.getString(10),
                rs.getTimestamp(11).toInstant(),
                BaseKind.valueOf(rs.getString(12)),
                rs.getString(13),
                rs.getString(14),
                CaptureState.valueOf(rs.getString(15)),
                DataLevel.valueOf(rs.getString(16)),
                rs.getString(17),
                rs.getLong(18),
                rs.getString(19),
                rs.getString(20),
                rs.getString(21),
                rs.getString(22),
                ParseState.valueOf(rs.getString(23)),
                rs.getString(24),
                rs.getString(25),
                rs.getLong(26),
                WipFreshness.valueOf(rs.getString(27)));
    }
}
