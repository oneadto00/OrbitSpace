package com.orbitspace.modules.gitproject;

import com.orbitspace.kernel.AuditLogPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.MessageKind;
import com.orbitspace.kernel.ProjectScopePort;
import com.orbitspace.kernel.ResourceCardVerifierPort;
import com.orbitspace.kernel.ResourceLifecycleState;
import com.orbitspace.kernel.SpaceAuthorizationPort;
import com.orbitspace.kernel.SpaceRole;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public class GitProjectScopeService implements ProjectScopePort, ResourceCardVerifierPort {
    private record WipCardRow(
            String branchName, String captureState, String note, String lifecycleState) {}

    private record CodeCommentCardRow(
            String baseKind,
            String baseId,
            String targetKind,
            String targetId,
            String filePath,
            long lineNumber,
            String codeContextExcerpt,
            String bodyText,
            String lifecycleState) {}

    private final JdbcTemplate db;
    private final SpaceAuthorizationPort spaceAuth;
    private final AuditLogPort auditLog;

    public GitProjectScopeService(
            JdbcTemplate db, SpaceAuthorizationPort spaceAuth, AuditLogPort auditLog) {
        this.db = db;
        this.spaceAuth = spaceAuth;
        this.auditLog = auditLog;
    }

    @Override
    public void requireProjectInSpace(String projectId, String spaceId) {
        if (projectId == null || projectId.isBlank() || spaceId == null || spaceId.isBlank()) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        boolean active =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(
                                    SELECT 1 FROM git_projects p
                                    JOIN spaces s ON s.id = p.space_id
                                    WHERE p.id = ? AND p.space_id = ? AND p.status = 'ACTIVE' AND s.status = 'ACTIVE'
                                )
                                """,
                                Boolean.class,
                                projectId,
                                spaceId));
        if (!active) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
    }

    @Override
    @Transactional
    public Summary createInitialProject(
            SpaceAuthorizationPort.Membership owner, String projectName) {
        if (owner == null || !owner.role().atLeast(SpaceRole.ADMIN)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (projectName == null || projectName.isBlank()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String trimmed = projectName.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 120) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String projectId = GitProjectUtils.newId();
        db.update(
                """
                INSERT INTO git_projects(id, space_id, name, lifecycle_version, status)
                VALUES (?, ?, ?, 1, 'ACTIVE')
                """,
                projectId,
                owner.spaceId(),
                trimmed);
        auditLog.append(
                new AuditLogPort.Entry(
                        GitProjectUtils.requestId(),
                        owner.userId(),
                        "PROJECT_CREATE",
                        projectId,
                        "SUCCESS",
                        Instant.now()));
        return new Summary(projectId, trimmed);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Summary> listProjects(SpaceAuthorizationPort.Membership member) {
        if (member == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        return db.query(
                """
                SELECT id, name
                FROM git_projects
                WHERE space_id = ? AND status = 'ACTIVE'
                ORDER BY created_at ASC, id ASC
                """,
                (rs, i) -> new Summary(rs.getString(1), rs.getString(2)),
                member.spaceId());
    }

    @Override
    public boolean supports(MessageKind kind) {
        return kind == MessageKind.COMMIT_REF
                || kind == MessageKind.WIP_REF
                || kind == MessageKind.CODE_COMMENT_REF;
    }

    @Override
    @Transactional(readOnly = true)
    public Preview verify(
            String userId, String spaceId, long membershipEpoch, Reference reference) {
        if (reference == null || userId == null || spaceId == null) {
            return new Preview(ResourceLifecycleState.REVOKED_FOR_ACTOR, null);
        }
        try {
            spaceAuth.requireMembership(spaceId, userId, membershipEpoch, SpaceRole.VIEWER);
            requireProjectInSpace(reference.projectId(), spaceId);
        } catch (DomainException ex) {
            return new Preview(ResourceLifecycleState.REVOKED_FOR_ACTOR, null);
        }

        if (reference.kind() == MessageKind.COMMIT_REF) {
            return verifyCommitCard(reference.projectId(), reference.targetId());
        }
        if (reference.kind() == MessageKind.WIP_REF) {
            return verifyWipCard(spaceId, reference.projectId(), reference.targetId());
        }
        if (reference.kind() == MessageKind.CODE_COMMENT_REF) {
            return verifyCodeCommentCard(spaceId, reference.projectId(), reference.targetId());
        }
        return new Preview(ResourceLifecycleState.REVOKED_FOR_ACTOR, null);
    }

    private Preview verifyCommitCard(String projectId, String commitOid) {
        List<String> commits =
                db.queryForList(
                        "SELECT oid FROM git_commits WHERE project_id = ? AND oid = ? LIMIT 1",
                        String.class,
                        projectId,
                        commitOid);
        if (commits.isEmpty()) {
            return new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
        }
        List<String> subjects =
                db.queryForList(
                        """
                        SELECT subject
                        FROM git_object_observations
                        WHERE project_id = ? AND oid = ?
                        ORDER BY observed_at DESC, id ASC
                        LIMIT 1
                        """,
                        String.class,
                        projectId,
                        commitOid);
        String shortOid = GitProjectUtils.shortOid(commitOid);
        String subject = subjects.isEmpty() ? "" : subjects.getFirst().strip();
        String label = subject.isEmpty() ? shortOid : shortOid + " · " + subject;
        return new Preview(ResourceLifecycleState.ACTIVE, label);
    }

    private Preview verifyWipCard(String spaceId, String projectId, String snapshotId) {
        WipCardRow row =
                db.query(
                                """
                                SELECT branch_name, capture_state, note, lifecycle_state
                                FROM wip_snapshots
                                WHERE id = ? AND project_id = ? AND space_id = ?
                                """,
                                (rs, i) ->
                                        new WipCardRow(
                                                rs.getString(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                rs.getString(4)),
                                snapshotId,
                                projectId,
                                spaceId)
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (row == null) {
            return new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
        }
        return switch (row.lifecycleState()) {
            case "FORCE_PURGED" -> new Preview(ResourceLifecycleState.FORCE_PURGED, null);
            case "EXPIRED_OR_DELETED" -> new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
            case "ACTIVE" -> {
                String branch =
                        row.branchName() != null && !row.branchName().isBlank()
                                ? row.branchName()
                                : "HEAD";
                String detail =
                        row.note() != null && !row.note().isBlank()
                                ? row.note().strip()
                                : row.captureState();
                yield new Preview(
                        ResourceLifecycleState.ACTIVE, "wip @ " + branch + " · " + detail);
            }
            default -> new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
        };
    }

    private Preview verifyCodeCommentCard(String spaceId, String projectId, String commentId) {
        CodeCommentCardRow row =
                db.query(
                                """
                                SELECT base_kind, base_id, target_kind, target_id,
                                       file_path, line_number, code_context_excerpt, body_text, lifecycle_state
                                FROM code_comments
                                WHERE id = ? AND project_id = ? AND space_id = ?
                                """,
                                (rs, i) ->
                                        new CodeCommentCardRow(
                                                rs.getString(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                rs.getString(4),
                                                rs.getString(5),
                                                rs.getLong(6),
                                                rs.getString(7),
                                                rs.getString(8),
                                                rs.getString(9)),
                                commentId,
                                projectId,
                                spaceId)
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (row == null) {
            return new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
        }
        if ("FORCE_PURGED".equals(row.lifecycleState())) {
            return new Preview(ResourceLifecycleState.FORCE_PURGED, null);
        }
        if (!"ACTIVE".equals(row.lifecycleState())) {
            return new Preview(ResourceLifecycleState.EXPIRED_OR_DELETED, null);
        }

        ResourceLifecycleState endpointState = checkWipEndpointsState(spaceId, projectId, row);
        if (endpointState != ResourceLifecycleState.ACTIVE) {
            return new Preview(endpointState, null);
        }

        String anchor = row.filePath() + ":L" + row.lineNumber();
        String snippet = firstConciseSnippet(row.codeContextExcerpt());
        if (snippet.isEmpty()) {
            snippet = firstConciseSnippet(row.bodyText());
        }
        String label = snippet.isEmpty() ? anchor : anchor + " · " + snippet;
        return new Preview(ResourceLifecycleState.ACTIVE, label);
    }

    private ResourceLifecycleState checkWipEndpointsState(
            String spaceId, String projectId, CodeCommentCardRow row) {
        ResourceLifecycleState worst = ResourceLifecycleState.ACTIVE;
        if ("WIP".equals(row.baseKind())) {
            worst = mergeWorstState(worst, queryWipState(spaceId, projectId, row.baseId()));
        }
        if ("WIP".equals(row.targetKind())) {
            worst = mergeWorstState(worst, queryWipState(spaceId, projectId, row.targetId()));
        }
        return worst;
    }

    private ResourceLifecycleState queryWipState(
            String spaceId, String projectId, String snapshotId) {
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
            return ResourceLifecycleState.EXPIRED_OR_DELETED;
        }
        return switch (states.getFirst()) {
            case "ACTIVE" -> ResourceLifecycleState.ACTIVE;
            case "FORCE_PURGED" -> ResourceLifecycleState.FORCE_PURGED;
            default -> ResourceLifecycleState.EXPIRED_OR_DELETED;
        };
    }

    private static ResourceLifecycleState mergeWorstState(
            ResourceLifecycleState a, ResourceLifecycleState b) {
        if (a == ResourceLifecycleState.FORCE_PURGED || b == ResourceLifecycleState.FORCE_PURGED) {
            return ResourceLifecycleState.FORCE_PURGED;
        }
        if (a != ResourceLifecycleState.ACTIVE) {
            return a;
        }
        return b;
    }

    private static String firstConciseSnippet(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80) + "…";
            }
        }
        return "";
    }
}
