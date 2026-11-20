package com.orbitspace.modules.gitproject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.AuditLogPort;
import com.orbitspace.kernel.DiscussionStatus;
import com.orbitspace.kernel.DiscussionThreadPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.MessageKind;
import com.orbitspace.kernel.ProjectScopePort;
import com.orbitspace.kernel.ResourceCardVerifierPort;
import com.orbitspace.kernel.ResourceLifecycleHook;
import com.orbitspace.kernel.ResourceLifecycleState;
import com.orbitspace.kernel.SpaceAuthorizationPort;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.ChangeCodeCommentStatusRequest;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.CodeCommentDto;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.CodeCommentListResponse;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.CodeCommentReplyDto;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.CodeCommentSide;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.CreateCodeCommentRequest;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.DiffEndpointKind;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.DiffEndpointRef;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.ForwardCodeCommentRequest;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.ReplyCodeCommentRequest;
import com.orbitspace.modules.gitproject.GitDiffCommentDtos.TwoPointDiffFileEntry;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public class GitCodeCommentService implements ResourceLifecycleHook {
    private static final String CHAT_THREAD_REPLY_BEAN = "orbitChatThreadReply";
    private static final TypeReference<List<TwoPointDiffFileEntry>> FILE_LIST_TYPE =
            new TypeReference<>() {};

    private record CreateCommentInput(
            String spaceId,
            String projectId,
            String diffId,
            DiffEndpointRef base,
            DiffEndpointRef target,
            String filePath,
            CodeCommentSide side,
            int lineNumber,
            String bodyText,
            boolean forwardToChat) {}

    private record ReplyCommentInput(
            String spaceId, String projectId, String commentId, String bodyText) {}

    private record ChangeDiscussionInput(
            String spaceId,
            String projectId,
            String commentId,
            DiscussionStatus status,
            String conclusionMsgId) {}

    private record ForwardCommentInput(
            String spaceId, String projectId, String commentId, String purpose) {}

    private record DiffAnchorRow(
            DiffEndpointKind baseKind,
            String baseId,
            DiffEndpointKind targetKind,
            String targetId,
            String filesJson,
            String lifecycleState) {}

    private record CommentRow(
            String id,
            String spaceId,
            String projectId,
            String diffId,
            DiffEndpointKind baseKind,
            String baseId,
            DiffEndpointKind targetKind,
            String targetId,
            String filePath,
            CodeCommentSide side,
            long lineNumber,
            String authorUserId,
            String bodyText,
            String discussionThreadId,
            DiscussionStatus discussionStatus,
            long discussionVersion,
            String lifecycleState,
            long lifecycleVersion,
            Instant createdAt,
            Instant updatedAt) {}

    private record AffectedDiffRow(String id, long lifecycleVersion) {}

    private record AffectedCommentRow(String id, long lifecycleVersion) {}

    private record ActiveMemberEpoch(String role, long membershipEpoch) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final ObjectMapper strictMapper;
    private final TransactionTemplate tx;
    private final SpaceAuthorizationPort spaceAuth;
    private final ProjectScopePort projectScope;
    private final AsyncJobPort asyncJobs;
    private final AuditLogPort auditLog;
    private final GitProjectIdempotencyStore idempotency;
    private final ObjectProvider<DiscussionThreadPort> discussionThreadProvider;
    private final ObjectProvider<ResourceLifecycleHook> lifecycleHooksProvider;
    private final ListableBeanFactory beanFactory;

    public GitCodeCommentService(
            JdbcTemplate db,
            ObjectMapper mapper,
            PlatformTransactionManager txManager,
            SpaceAuthorizationPort spaceAuth,
            ProjectScopePort projectScope,
            AsyncJobPort asyncJobs,
            AuditLogPort auditLog,
            GitProjectIdempotencyStore idempotency,
            ObjectProvider<DiscussionThreadPort> discussionThreadProvider,
            ObjectProvider<ResourceLifecycleHook> lifecycleHooksProvider,
            ListableBeanFactory beanFactory) {
        this.db = db;
        this.mapper = mapper;
        this.strictMapper = GitProjectUtils.strictMapper(mapper);
        this.tx = new TransactionTemplate(txManager);
        this.spaceAuth = spaceAuth;
        this.projectScope = projectScope;
        this.asyncJobs = asyncJobs;
        this.auditLog = auditLog;
        this.idempotency = idempotency;
        this.discussionThreadProvider = discussionThreadProvider;
        this.lifecycleHooksProvider = lifecycleHooksProvider;
        this.beanFactory = beanFactory;
    }

    @Transactional(readOnly = true)
    public CodeCommentListResponse listCodeComments(
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            String diffId,
            String path) {
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);
        projectScope.requireProjectInSpace(projectId, spaceId);

        if (diffId != null) {
            GitProjectUtils.requireId(diffId);
        }
        if (path != null) {
            GitProjectUtils.requireSafeRelativePath(path);
        }

        StringBuilder sql =
                new StringBuilder(
                        commentSelectSql()
                                + " WHERE space_id = ? AND project_id = ? AND lifecycle_state = 'ACTIVE'");
        List<Object> params = new ArrayList<>();
        params.add(spaceId);
        params.add(projectId);
        if (diffId != null) {
            sql.append(" AND diff_id = ?");
            params.add(diffId);
        }
        if (path != null) {
            sql.append(" AND file_path = ?");
            params.add(path);
        }
        sql.append(" ORDER BY created_at ASC, id ASC");

        List<CommentRow> rows = db.query(sql.toString(), this::mapCommentRow, params.toArray());
        if (rows.isEmpty()) {
            return new CodeCommentListResponse(List.of());
        }

        Set<String> wipIds = new HashSet<>();
        for (CommentRow row : rows) {
            if (row.baseKind() == DiffEndpointKind.WIP) {
                wipIds.add(row.baseId());
            }
            if (row.targetKind() == DiffEndpointKind.WIP) {
                wipIds.add(row.targetId());
            }
        }
        Set<String> activeWipIds = loadActiveWipIds(spaceId, projectId, wipIds);

        List<CodeCommentDto> items = new ArrayList<>(rows.size());
        for (CommentRow row : rows) {
            if (row.baseKind() == DiffEndpointKind.WIP && !activeWipIds.contains(row.baseId())) {
                continue;
            }
            if (row.targetKind() == DiffEndpointKind.WIP
                    && !activeWipIds.contains(row.targetId())) {
                continue;
            }
            items.add(toLiveDto(row));
        }
        return new CodeCommentListResponse(List.copyOf(items));
    }

    @Transactional
    public CodeCommentDto createCodeComment(
            String spaceId,
            String projectId,
            String userId,
            long epoch,
            String idempotencyKey,
            String rawBody) {
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, spaceId);
        GitProjectUtils.normalizeKey64(idempotencyKey);

        CreateCodeCommentRequest req =
                GitProjectUtils.parseStrict(strictMapper, rawBody, CreateCodeCommentRequest.class);
        validateEndpointRef(req.base());
        validateEndpointRef(req.target());
        GitProjectUtils.requireSafeRelativePath(req.filePath());
        if (req.side() == null || req.lineNumber() == null || req.lineNumber() < 1) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String bodyText = GitProjectUtils.requireBoundedText(req.bodyText(), 4000);
        if (req.diffId() != null) {
            GitProjectUtils.requireId(req.diffId());
        }
        boolean forwardToChat = Boolean.TRUE.equals(req.forwardToChat());

        CreateCommentInput canonicalInput =
                new CreateCommentInput(
                        spaceId,
                        projectId,
                        req.diffId(),
                        req.base(),
                        req.target(),
                        req.filePath(),
                        req.side(),
                        req.lineNumber(),
                        bodyText,
                        forwardToChat);

        return idempotency.execute(
                "CREATE_CODE_COMMENT",
                projectId,
                userId,
                idempotencyKey,
                canonicalInput,
                CodeCommentDto.class,
                () -> {
                    spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.MEMBER);
                    projectScope.requireProjectInSpace(projectId, spaceId);
                    requireEndpointAccessible(spaceId, projectId, req.base());
                    requireEndpointAccessible(spaceId, projectId, req.target());

                    String excerpt = null;
                    if (req.diffId() != null) {
                        excerpt =
                                validateDiffAnchorAndExtractExcerpt(
                                        spaceId,
                                        projectId,
                                        req.diffId(),
                                        req.base(),
                                        req.target(),
                                        req.filePath(),
                                        req.side(),
                                        req.lineNumber());
                    }

                    String commentId = GitProjectUtils.newId();
                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    Timestamp ts = Timestamp.from(now);

                    db.update(
                            """
                            INSERT INTO code_comments(
                                id, space_id, project_id, diff_id,
                                base_kind, base_id, target_kind, target_id,
                                file_path, side, line_number, code_context_excerpt,
                                author_user_id, body_text,
                                discussion_thread_id, discussion_status, discussion_version,
                                lifecycle_state, lifecycle_version, created_at, updated_at
                            )
                            VALUES (
                                ?, ?, ?, ?,
                                ?, ?, ?, ?,
                                ?, ?, ?, ?,
                                ?, ?,
                                NULL, 'OPEN', 1,
                                'ACTIVE', 1, ?, ?
                            )
                            """,
                            commentId,
                            spaceId,
                            projectId,
                            req.diffId(),
                            req.base().kind().name(),
                            req.base().id(),
                            req.target().kind().name(),
                            req.target().id(),
                            req.filePath(),
                            req.side().name(),
                            req.lineNumber(),
                            excerpt,
                            userId,
                            bodyText,
                            ts,
                            ts);

                    String rootChatMessageId = null;
                    DiscussionStatus status = DiscussionStatus.OPEN;
                    long version = 1L;

                    if (forwardToChat) {
                        DiscussionThreadPort threads = requireDiscussionThreadPort();
                        DiscussionThreadPort.ThreadSummary bound =
                                threads.bindOrForwardCodeComment(
                                        new DiscussionThreadPort.CodeCommentCardRequest(
                                                spaceId,
                                                projectId,
                                                commentId,
                                                userId,
                                                epoch,
                                                GitProjectUtils.normalizeKey64(
                                                        "cc-card:" + commentId),
                                                bodyText));
                        rootChatMessageId = bound.rootMessageId();
                        status = bound.status();
                        version = bound.version();
                        db.update(
                                """
                                UPDATE code_comments
                                SET discussion_thread_id = ?,
                                    discussion_status = ?,
                                    discussion_version = ?,
                                    updated_at = ?
                                WHERE id = ?
                                """,
                                rootChatMessageId,
                                status.name(),
                                version,
                                ts,
                                commentId);
                    }

                    auditLog.append(
                            new AuditLogPort.Entry(
                                    GitProjectUtils.requestId(),
                                    userId,
                                    "CODE_COMMENT_CREATE",
                                    commentId,
                                    "SUCCESS",
                                    Instant.now()));

                    return new CodeCommentDto(
                            commentId,
                            spaceId,
                            projectId,
                            req.diffId(),
                            req.base(),
                            req.target(),
                            req.filePath(),
                            req.side(),
                            req.lineNumber(),
                            userId,
                            bodyText,
                            rootChatMessageId,
                            status,
                            version,
                            List.of(),
                            now,
                            now);
                });
    }

    @Transactional
    public CodeCommentDto replyCodeComment(
            String spaceId,
            String projectId,
            String commentId,
            String userId,
            long epoch,
            String idempotencyKey,
            String rawBody) {
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, spaceId);
        GitProjectUtils.requireId(commentId);
        GitProjectUtils.normalizeKey64(idempotencyKey);

        ReplyCodeCommentRequest req =
                GitProjectUtils.parseStrict(strictMapper, rawBody, ReplyCodeCommentRequest.class);
        String bodyText = GitProjectUtils.requireBoundedText(req.bodyText(), 4000);

        CommentRow preCheck = loadCommentRow(spaceId, projectId, commentId, false);
        requireCommentAndEndpointsActive(preCheck);

        ReplyCommentInput canonicalInput =
                new ReplyCommentInput(spaceId, projectId, commentId, bodyText);

        return idempotency.execute(
                "REPLY_CODE_COMMENT",
                commentId,
                userId,
                idempotencyKey,
                canonicalInput,
                CodeCommentDto.class,
                () -> {
                    spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.MEMBER);
                    projectScope.requireProjectInSpace(projectId, spaceId);

                    CommentRow locked = loadCommentRow(spaceId, projectId, commentId, true);
                    requireCommentAndEndpointsActive(locked);

                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    Timestamp ts = Timestamp.from(now);

                    if (locked.discussionThreadId() != null) {
                        Function<Map<String, Object>, Map<String, Object>> replyBridge =
                                resolveChatThreadReplyBean();
                        if (replyBridge == null) {
                            throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
                        }
                        Map<String, Object> args = new HashMap<>();
                        args.put("spaceId", spaceId);
                        args.put("userId", userId);
                        args.put("membershipEpoch", epoch);
                        args.put("rootMessageId", locked.discussionThreadId());
                        args.put(
                                "clientMsgId",
                                GitProjectUtils.sha256Hex(
                                        "cc-reply:" + commentId + ":" + idempotencyKey));
                        args.put("bodyText", bodyText);
                        replyBridge.apply(args);
                    } else {
                        db.update(
                                """
                                INSERT INTO code_comment_replies(
                                    id, comment_id, sender_id, body_text, created_at
                                )
                                VALUES (?, ?, ?, ?, ?)
                                """,
                                GitProjectUtils.newId(),
                                commentId,
                                userId,
                                bodyText,
                                ts);
                    }

                    db.update(
                            "UPDATE code_comments SET updated_at = ? WHERE id = ?", ts, commentId);

                    auditLog.append(
                            new AuditLogPort.Entry(
                                    GitProjectUtils.requestId(),
                                    userId,
                                    "CODE_COMMENT_REPLY",
                                    commentId,
                                    "SUCCESS",
                                    Instant.now()));

                    return toLiveDto(loadCommentRow(spaceId, projectId, commentId, false));
                });
    }

    @Transactional
    public CodeCommentDto changeCodeCommentDiscussion(
            String spaceId,
            String projectId,
            String commentId,
            String userId,
            long epoch,
            String idempotencyKey,
            String rawBody) {
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, spaceId);
        GitProjectUtils.requireId(commentId);
        GitProjectUtils.normalizeKey64(idempotencyKey);

        ChangeCodeCommentStatusRequest req =
                GitProjectUtils.parseStrict(
                        strictMapper, rawBody, ChangeCodeCommentStatusRequest.class);
        if (req.status() == null) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        if (req.conclusionMsgId() != null) {
            GitProjectUtils.requireId(req.conclusionMsgId());
        }

        CommentRow preCheck = loadCommentRow(spaceId, projectId, commentId, false);
        requireCommentAndEndpointsActive(preCheck);

        ChangeDiscussionInput canonicalInput =
                new ChangeDiscussionInput(
                        spaceId, projectId, commentId, req.status(), req.conclusionMsgId());

        return idempotency.execute(
                "CHANGE_CODE_COMMENT_DISCUSSION",
                commentId,
                userId,
                idempotencyKey,
                canonicalInput,
                CodeCommentDto.class,
                () -> {
                    SpaceAuthorizationPort.Membership liveMembership =
                            spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.MEMBER);
                    projectScope.requireProjectInSpace(projectId, spaceId);

                    CommentRow locked = loadCommentRow(spaceId, projectId, commentId, true);
                    requireCommentAndEndpointsActive(locked);

                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    Timestamp ts = Timestamp.from(now);

                    if (locked.discussionThreadId() != null) {
                        DiscussionThreadPort threads = requireDiscussionThreadPort();
                        DiscussionThreadPort.ThreadSummary updated =
                                threads.syncConclusionStatus(
                                        spaceId,
                                        locked.discussionThreadId(),
                                        userId,
                                        epoch,
                                        req.status(),
                                        req.conclusionMsgId(),
                                        GitProjectUtils.sha256Hex(
                                                "cc-disc:" + commentId + ":" + idempotencyKey));
                        db.update(
                                """
                                UPDATE code_comments
                                SET discussion_status = ?,
                                    discussion_version = ?,
                                    updated_at = ?
                                WHERE id = ?
                                """,
                                updated.status().name(),
                                updated.version(),
                                ts,
                                commentId);
                    } else {
                        boolean authorized =
                                liveMembership.role().atLeast(SpaceRole.ADMIN)
                                        || userId.equals(locked.authorUserId());
                        if (!authorized) {
                            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
                        }
                        if (req.conclusionMsgId() != null
                                && !commentId.equals(req.conclusionMsgId())) {
                            boolean replyExists =
                                    Boolean.TRUE.equals(
                                            db.queryForObject(
                                                    """
                                                    SELECT EXISTS(
                                                        SELECT 1 FROM code_comment_replies
                                                        WHERE id = ? AND comment_id = ?
                                                    )
                                                    """,
                                                    Boolean.class,
                                                    req.conclusionMsgId(),
                                                    commentId));
                            if (!replyExists) {
                                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                            }
                        }
                        long nextVersion = locked.discussionVersion() + 1;
                        db.update(
                                """
                                UPDATE code_comments
                                SET discussion_status = ?,
                                    discussion_version = ?,
                                    updated_at = ?
                                WHERE id = ?
                                """,
                                req.status().name(),
                                nextVersion,
                                ts,
                                commentId);
                    }

                    auditLog.append(
                            new AuditLogPort.Entry(
                                    GitProjectUtils.requestId(),
                                    userId,
                                    "CODE_COMMENT_DISCUSSION_CHANGE",
                                    commentId,
                                    "SUCCESS",
                                    Instant.now()));

                    return toLiveDto(loadCommentRow(spaceId, projectId, commentId, false));
                });
    }

    @Transactional
    public CodeCommentDto forwardCodeCommentToChat(
            String spaceId,
            String projectId,
            String commentId,
            String userId,
            long epoch,
            String idempotencyKey,
            String rawBody) {
        spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.MEMBER);
        projectScope.requireProjectInSpace(projectId, spaceId);
        GitProjectUtils.requireId(commentId);
        GitProjectUtils.normalizeKey64(idempotencyKey);

        ForwardCodeCommentRequest req =
                (rawBody == null || rawBody.isBlank())
                        ? new ForwardCodeCommentRequest(null)
                        : GitProjectUtils.parseStrict(
                                strictMapper, rawBody, ForwardCodeCommentRequest.class);
        String purpose =
                req.purpose() != null
                        ? GitProjectUtils.requireBoundedText(req.purpose(), 4000)
                        : null;

        CommentRow preCheck = loadCommentRow(spaceId, projectId, commentId, false);
        requireCommentAndEndpointsActive(preCheck);

        ForwardCommentInput canonicalInput =
                new ForwardCommentInput(spaceId, projectId, commentId, purpose);

        return idempotency.execute(
                "FORWARD_CODE_COMMENT",
                commentId,
                userId,
                idempotencyKey,
                canonicalInput,
                CodeCommentDto.class,
                () -> {
                    spaceAuth.requireMembership(spaceId, userId, epoch, SpaceRole.MEMBER);
                    projectScope.requireProjectInSpace(projectId, spaceId);

                    CommentRow locked = loadCommentRow(spaceId, projectId, commentId, true);
                    requireCommentAndEndpointsActive(locked);

                    if (locked.discussionThreadId() != null) {
                        return toLiveDto(locked);
                    }

                    DiscussionThreadPort threads = requireDiscussionThreadPort();
                    String effectivePurpose = purpose != null ? purpose : locked.bodyText();
                    DiscussionThreadPort.ThreadSummary summary =
                            threads.bindOrForwardCodeComment(
                                    new DiscussionThreadPort.CodeCommentCardRequest(
                                            spaceId,
                                            projectId,
                                            commentId,
                                            userId,
                                            epoch,
                                            GitProjectUtils.normalizeKey64("cc-card:" + commentId),
                                            effectivePurpose));

                    // Mirror any pre-existing local replies into the unified chat thread.
                    List<CodeCommentReplyDto> localReplies = loadLocalReplies(commentId);
                    if (!localReplies.isEmpty()) {
                        Function<Map<String, Object>, Map<String, Object>> replyBridge =
                                resolveChatThreadReplyBean();
                        if (replyBridge != null) {
                            for (CodeCommentReplyDto reply : localReplies) {
                                String replyActorId = userId;
                                long replyActorEpoch = epoch;
                                if (!userId.equals(reply.senderId())) {
                                    ActiveMemberEpoch memberEpoch =
                                            findActiveMemberEpoch(spaceId, reply.senderId());
                                    if (memberEpoch != null
                                            && SpaceRole.valueOf(memberEpoch.role())
                                                    .atLeast(SpaceRole.MEMBER)) {
                                        replyActorId = reply.senderId();
                                        replyActorEpoch = memberEpoch.membershipEpoch();
                                    }
                                }
                                Map<String, Object> args = new HashMap<>();
                                args.put("spaceId", spaceId);
                                args.put("userId", replyActorId);
                                args.put("membershipEpoch", replyActorEpoch);
                                args.put("rootMessageId", summary.rootMessageId());
                                args.put(
                                        "clientMsgId",
                                        GitProjectUtils.sha256Hex(
                                                "cc-mirror:" + commentId + ":" + reply.messageId()));
                                args.put("bodyText", reply.bodyText());
                                replyBridge.apply(args);
                            }
                        }
                    }

                    if (locked.discussionStatus() == DiscussionStatus.RESOLVED
                            && summary.status() != DiscussionStatus.RESOLVED) {
                        summary =
                                threads.syncConclusionStatus(
                                        spaceId,
                                        summary.rootMessageId(),
                                        userId,
                                        epoch,
                                        DiscussionStatus.RESOLVED,
                                        null,
                                        GitProjectUtils.normalizeKey64("cc-fwd-sync:" + commentId));
                    }

                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    db.update(
                            """
                            UPDATE code_comments
                            SET discussion_thread_id = ?,
                                discussion_status = ?,
                                discussion_version = ?,
                                updated_at = ?
                            WHERE id = ?
                            """,
                            summary.rootMessageId(),
                            summary.status().name(),
                            summary.version(),
                            Timestamp.from(now),
                            commentId);

                    auditLog.append(
                            new AuditLogPort.Entry(
                                    GitProjectUtils.requestId(),
                                    userId,
                                    "CODE_COMMENT_FORWARD_CHAT",
                                    commentId,
                                    "SUCCESS",
                                    Instant.now()));

                    return toLiveDto(loadCommentRow(spaceId, projectId, commentId, false));
                });
    }

    /**
     * Listens for WIP_REF expiration/force-purge transitions (from GitProjectService or
     * P1LifecycleGuardians) and cascades lifecycle invalidation to backfill objects, two_point_diffs,
     * DIFF_ENRICHMENT jobs, and code_comments (emitting CODE_COMMENT_REF lifecycle changes).
     */
    @Override
    public void onLifecycleChanged(ResourceLifecycleHook.Change change) {
        if (change == null || change.reference() == null) {
            return;
        }
        // Guard strictly against recursion: only react to WIP_REF transitions and only emit CODE_COMMENT_REF.
        if (change.reference().kind() != MessageKind.WIP_REF) {
            return;
        }
        if (change.state() != ResourceLifecycleState.EXPIRED_OR_DELETED
                && change.state() != ResourceLifecycleState.FORCE_PURGED) {
            return;
        }
        cascadeWipSnapshotLifecycle(
                change.spaceId(),
                change.reference().projectId(),
                change.reference().targetId(),
                change.state());
    }

    void cascadeWipSnapshotLifecycle(
            String spaceId, String projectId, String snapshotId, ResourceLifecycleState targetState) {
        tx.executeWithoutResult(
                status -> {
                    db.update(
                            """
                            UPDATE git_backfill_sessions
                            SET status = 'EXPIRED'
                            WHERE project_id = ? AND target_kind = 'WIP' AND target_id = ? AND status = 'OPEN'
                            """,
                            projectId,
                            snapshotId);

                    List<Long> wipBackfillIds =
                            db.queryForList(
                                    """
                                    SELECT id
                                    FROM git_backfill_objects
                                    WHERE project_id = ? AND target_kind = 'WIP' AND target_id = ?
                                    """,
                                    Long.class,
                                    projectId,
                                    snapshotId);
                    for (Long backfillObjId : wipBackfillIds) {
                        db.update(
                                """
                                DELETE FROM storage_object_references
                                WHERE owner_type = 'GIT_BACKFILL_OBJECT' AND owner_id = ?
                                """,
                                Long.toString(backfillObjId));
                    }

                    String stateGuard =
                            targetState == ResourceLifecycleState.FORCE_PURGED
                                    ? "lifecycle_state <> 'FORCE_PURGED'"
                                    : "lifecycle_state = 'ACTIVE'";

                    List<AffectedDiffRow> affectedDiffs =
                            db.query(
                                    """
                                    SELECT id, lifecycle_version
                                    FROM two_point_diffs
                                    WHERE space_id = ? AND project_id = ?
                                      AND ((base_kind = 'WIP' AND base_id = ?) OR (target_kind = 'WIP' AND target_id = ?))
                                      AND """
                                            + stateGuard
                                            + " FOR UPDATE",
                                    (rs, i) -> new AffectedDiffRow(rs.getString(1), rs.getLong(2)),
                                    spaceId,
                                    projectId,
                                    snapshotId,
                                    snapshotId);

                    for (AffectedDiffRow diff : affectedDiffs) {
                        long nextVersion = diff.lifecycleVersion() + 1;
                        db.update(
                                """
                                UPDATE two_point_diffs
                                SET lifecycle_state = ?,
                                    lifecycle_version = ?,
                                    status = CASE WHEN status = 'PENDING' THEN 'FAILED' ELSE status END,
                                    enrichment_result_object_key = NULL
                                WHERE id = ?
                                """,
                                targetState.name(),
                                nextVersion,
                                diff.id());
                        db.update(
                                """
                                DELETE FROM storage_object_references
                                WHERE owner_type IN ('DIFF_ENRICH_INPUT', 'DIFF_ENRICHMENT')
                                  AND owner_id = ?
                                """,
                                diff.id());
                        List<String> activeJobIds =
                                db.queryForList(
                                        """
                                        SELECT job_id
                                        FROM async_jobs
                                        WHERE resource_id = ? AND space_id = ? AND job_type = ?
                                          AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                                        """,
                                        String.class,
                                        diff.id(),
                                        spaceId,
                                        GitTwoPointDiffService.DIFF_ENRICHMENT_JOB_TYPE);
                        for (String jobId : activeJobIds) {
                            asyncJobs.cancel(jobId);
                        }
                    }

                    List<AffectedCommentRow> affectedComments =
                            db.query(
                                    """
                                    SELECT id, lifecycle_version
                                    FROM code_comments
                                    WHERE space_id = ? AND project_id = ?
                                      AND ((base_kind = 'WIP' AND base_id = ?) OR (target_kind = 'WIP' AND target_id = ?))
                                      AND """
                                            + stateGuard
                                            + " FOR UPDATE",
                                    (rs, i) ->
                                            new AffectedCommentRow(rs.getString(1), rs.getLong(2)),
                                    spaceId,
                                    projectId,
                                    snapshotId,
                                    snapshotId);

                    if (affectedComments.isEmpty()) {
                        return;
                    }
                    List<ResourceLifecycleHook> hooks =
                            lifecycleHooksProvider.orderedStream().toList();
                    for (AffectedCommentRow comment : affectedComments) {
                        long nextVersion = comment.lifecycleVersion() + 1;
                        db.update(
                                """
                                UPDATE code_comments
                                SET lifecycle_state = ?,
                                    lifecycle_version = ?,
                                    code_context_excerpt = NULL,
                                    updated_at = CURRENT_TIMESTAMP(6)
                                WHERE id = ?
                                """,
                                targetState.name(),
                                nextVersion,
                                comment.id());

                        var commentChange =
                                new ResourceLifecycleHook.Change(
                                        spaceId,
                                        new ResourceCardVerifierPort.Reference(
                                                MessageKind.CODE_COMMENT_REF,
                                                projectId,
                                                comment.id()),
                                        targetState,
                                        nextVersion);
                        for (ResourceLifecycleHook hook : hooks) {
                            if (hook != this) {
                                hook.onLifecycleChanged(commentChange);
                            }
                        }
                    }
                });
    }

    private CodeCommentDto toLiveDto(CommentRow row) {
        DiscussionStatus status = row.discussionStatus();
        long version = row.discussionVersion();
        List<CodeCommentReplyDto> replies;

        if (row.discussionThreadId() != null) {
            DiscussionThreadPort threads = discussionThreadProvider.getIfAvailable();
            if (threads != null) {
                Optional<DiscussionThreadPort.ThreadSummary> liveThread =
                        threads.findThread(row.spaceId(), row.discussionThreadId());
                if (liveThread.isPresent()) {
                    status = liveThread.get().status();
                    version = liveThread.get().version();
                }
                replies = loadChatThreadReplies(row.discussionThreadId());
            } else {
                replies = loadLocalReplies(row.id());
            }
        } else {
            replies = loadLocalReplies(row.id());
        }

        return new CodeCommentDto(
                row.id(),
                row.spaceId(),
                row.projectId(),
                row.diffId(),
                new DiffEndpointRef(row.baseKind(), row.baseId()),
                new DiffEndpointRef(row.targetKind(), row.targetId()),
                row.filePath(),
                row.side(),
                row.lineNumber(),
                row.authorUserId(),
                row.bodyText(),
                row.discussionThreadId(),
                status,
                version,
                replies,
                row.createdAt(),
                row.updatedAt());
    }

    private List<CodeCommentReplyDto> loadChatThreadReplies(String rootMessageId) {
        return db.query(
                """
                SELECT id, sender_id,
                       COALESCE(NULLIF(body_text, ''), NULLIF(ref_purpose, ''), kind) AS display_body,
                       created_at
                FROM chat_messages
                WHERE reply_to_msg_id = ?
                  AND sender_id IS NOT NULL
                ORDER BY seq ASC
                """,
                (rs, i) ->
                        new CodeCommentReplyDto(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getTimestamp(4).toInstant()),
                rootMessageId);
    }

    private List<CodeCommentReplyDto> loadLocalReplies(String commentId) {
        return db.query(
                """
                SELECT id, sender_id, body_text, created_at
                FROM code_comment_replies
                WHERE comment_id = ?
                ORDER BY created_at ASC, id ASC
                """,
                (rs, i) ->
                        new CodeCommentReplyDto(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getTimestamp(4).toInstant()),
                commentId);
    }

    private String validateDiffAnchorAndExtractExcerpt(
            String spaceId,
            String projectId,
            String diffId,
            DiffEndpointRef base,
            DiffEndpointRef target,
            String filePath,
            CodeCommentSide side,
            int lineNumber) {
        DiffAnchorRow diff =
                db.query(
                                """
                                SELECT base_kind, base_id, target_kind, target_id,
                                       files_json, lifecycle_state
                                FROM two_point_diffs
                                WHERE id = ? AND project_id = ? AND space_id = ?
                                """,
                                (rs, i) ->
                                        new DiffAnchorRow(
                                                DiffEndpointKind.valueOf(rs.getString(1)),
                                                rs.getString(2),
                                                DiffEndpointKind.valueOf(rs.getString(3)),
                                                rs.getString(4),
                                                rs.getString(5),
                                                rs.getString(6)),
                                diffId,
                                projectId,
                                spaceId)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
        requireLifecycleActive(diff.lifecycleState());

        if (diff.baseKind() != base.kind()
                || !diff.baseId().equals(base.id())
                || diff.targetKind() != target.kind()
                || !diff.targetId().equals(target.id())) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        List<TwoPointDiffFileEntry> files;
        try {
            files = mapper.readValue(diff.filesJson(), FILE_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            return null;
        }
        for (TwoPointDiffFileEntry entry : files) {
            if (filePath.equals(entry.path()) || filePath.equals(entry.oldPath())) {
                if (!entry.deterministic()) {
                    throw new DomainException(KernelErrorCode.INVALID_REQUEST);
                }
                return GitDiffTextSupport.extractContextExcerpt(
                        entry.patchText(), side, lineNumber);
            }
        }
        return null;
    }

    private CommentRow loadCommentRow(
            String spaceId, String projectId, String commentId, boolean forUpdate) {
        String sql =
                commentSelectSql()
                        + " WHERE id = ? AND project_id = ? AND space_id = ?"
                        + (forUpdate ? " FOR UPDATE" : "");
        return db.query(sql, this::mapCommentRow, commentId, projectId, spaceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireCommentAndEndpointsActive(CommentRow row) {
        requireLifecycleActive(row.lifecycleState());
        if (row.baseKind() == DiffEndpointKind.WIP) {
            requireLiveWipActive(row.spaceId(), row.projectId(), row.baseId());
        }
        if (row.targetKind() == DiffEndpointKind.WIP) {
            requireLiveWipActive(row.spaceId(), row.projectId(), row.targetId());
        }
    }

    private void requireEndpointAccessible(
            String spaceId, String projectId, DiffEndpointRef endpoint) {
        if (endpoint.kind() == DiffEndpointKind.COMMIT) {
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
                                    endpoint.id(),
                                    projectId,
                                    endpoint.id(),
                                    projectId,
                                    endpoint.id()));
            if (!known) {
                throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
            }
            return;
        }
        List<String> states =
                db.queryForList(
                        """
                        SELECT lifecycle_state
                        FROM wip_snapshots
                        WHERE id = ? AND project_id = ? AND space_id = ?
                        """,
                        String.class,
                        endpoint.id(),
                        projectId,
                        spaceId);
        if (states.isEmpty()) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }
        requireLifecycleActive(states.getFirst());
    }

    private void requireLiveWipActive(String spaceId, String projectId, String snapshotId) {
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
        requireLifecycleActive(states.getFirst());
    }

    private Set<String> loadActiveWipIds(String spaceId, String projectId, Set<String> wipIds) {
        if (wipIds.isEmpty()) {
            return Set.of();
        }
        List<String> list = new ArrayList<>(wipIds);
        String placeholders = String.join(",", Collections.nCopies(list.size(), "?"));
        List<Object> params = new ArrayList<>(list.size() + 2);
        params.add(spaceId);
        params.add(projectId);
        params.addAll(list);
        return new HashSet<>(
                db.queryForList(
                        """
                        SELECT id
                        FROM wip_snapshots
                        WHERE space_id = ? AND project_id = ? AND lifecycle_state = 'ACTIVE'
                          AND id IN ("""
                                + placeholders
                                + ")",
                        String.class,
                        params.toArray()));
    }

    private ActiveMemberEpoch findActiveMemberEpoch(String spaceId, String userId) {
        return db.query(
                        """
                        SELECT role, membership_epoch
                        FROM space_members
                        WHERE space_id = ? AND user_id = ? AND status = 'ACTIVE'
                        """,
                        (rs, i) -> new ActiveMemberEpoch(rs.getString(1), rs.getLong(2)),
                        spaceId,
                        userId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private DiscussionThreadPort requireDiscussionThreadPort() {
        DiscussionThreadPort port = discussionThreadProvider.getIfAvailable();
        if (port == null) {
            throw new DomainException(KernelErrorCode.INTERNAL_ERROR);
        }
        return port;
    }

    @SuppressWarnings("unchecked")
    private Function<Map<String, Object>, Map<String, Object>> resolveChatThreadReplyBean() {
        if (!beanFactory.containsBean(CHAT_THREAD_REPLY_BEAN)) {
            return null;
        }
        Object bean = beanFactory.getBean(CHAT_THREAD_REPLY_BEAN);
        if (bean instanceof Function<?, ?> fn) {
            return (Function<Map<String, Object>, Map<String, Object>>) fn;
        }
        return null;
    }

    private static String commentSelectSql() {
        return """
                SELECT id, space_id, project_id, diff_id,
                       base_kind, base_id, target_kind, target_id,
                       file_path, side, line_number, author_user_id, body_text,
                       discussion_thread_id, discussion_status, discussion_version,
                       lifecycle_state, lifecycle_version, created_at, updated_at
                FROM code_comments
                """;
    }

    private CommentRow mapCommentRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new CommentRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                DiffEndpointKind.valueOf(rs.getString(5)),
                rs.getString(6),
                DiffEndpointKind.valueOf(rs.getString(7)),
                rs.getString(8),
                rs.getString(9),
                CodeCommentSide.valueOf(rs.getString(10)),
                rs.getLong(11),
                rs.getString(12),
                rs.getString(13),
                rs.getString(14),
                DiscussionStatus.valueOf(rs.getString(15)),
                rs.getLong(16),
                rs.getString(17),
                rs.getLong(18),
                rs.getTimestamp(19).toInstant(),
                rs.getTimestamp(20).toInstant());
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

    private static void requireLifecycleActive(String lifecycleState) {
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
