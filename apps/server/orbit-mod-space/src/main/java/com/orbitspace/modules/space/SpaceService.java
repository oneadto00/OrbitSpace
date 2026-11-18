package com.orbitspace.modules.space;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.AtomicTokenStorePort;
import com.orbitspace.kernel.AuditLogPort;
import com.orbitspace.kernel.ChatUnreadQueryPort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.MembershipRevocationHook;
import com.orbitspace.kernel.OutboxPublisherPort;
import com.orbitspace.kernel.ProjectScopePort;
import com.orbitspace.kernel.RevocationLedgerPort;
import com.orbitspace.kernel.SpaceAuthorizationPort;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.kernel.SpaceRoomPort;
import com.orbitspace.modules.space.SpaceDtos.ChangeMemberRoleRequest;
import com.orbitspace.modules.space.SpaceDtos.CreateInviteRequest;
import com.orbitspace.modules.space.SpaceDtos.CreateInviteResponse;
import com.orbitspace.modules.space.SpaceDtos.CreateSpaceRequest;
import com.orbitspace.modules.space.SpaceDtos.InviteDto;
import com.orbitspace.modules.space.SpaceDtos.InviteRole;
import com.orbitspace.modules.space.SpaceDtos.JoinSpaceRequest;
import com.orbitspace.modules.space.SpaceDtos.MemberDto;
import com.orbitspace.modules.space.SpaceDtos.ProjectSummary;
import com.orbitspace.modules.space.SpaceDtos.SpaceDetail;
import com.orbitspace.modules.space.SpaceDtos.SpaceSummary;
import com.orbitspace.modules.space.SpaceDtos.TransferOwnerRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core transactional domain service for spaces, invitations, RBAC, and membership_epoch revocation. */
public class SpaceService {
    private static final Logger LOG = LoggerFactory.getLogger(SpaceService.class);
    private static final String EVENTS_CHANNEL = "orbit:events";
    private static final Instant MAX_MYSQL_TIMESTAMP = Instant.parse("2038-01-19T03:14:07Z");

    private record LockedMember(
            String userId, String status, SpaceRole role, long membershipEpoch, long permVersion) {}

    private record InviteRow(
            String id,
            String spaceId,
            InviteRole targetRole,
            long maxUses,
            long usedCount,
            Instant expiresAt,
            boolean revoked,
            String spaceName,
            String spaceStatus) {}

    private record ExistingMemberRow(
            String status,
            SpaceRole role,
            long membershipEpoch,
            long permVersion,
            Instant recentlyVisitedAt) {}

    private record CreateSpaceInput(String name, String description, String initialProjectName) {}

    private record CreateInviteInput(
            String spaceId, Instant expiresAt, long maxUses, InviteRole targetRole) {}

    private record JoinSpaceInput(String token) {}

    private record TransferOwnerInput(String spaceId, String newOwnerUserId) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final AtomicTokenStorePort tokens;
    private final RevocationLedgerPort revocationLedger;
    private final OutboxPublisherPort outbox;
    private final AuditLogPort auditLog;
    private final SpaceAuthorizationService authorization;
    private final SpaceIdempotencyStore idempotency;
    private final List<MembershipRevocationHook> revocationHooks;
    private final ObjectProvider<SpaceRoomPort> roomPortProvider;
    private final ObjectProvider<ProjectScopePort> projectPortProvider;
    private final ObjectProvider<ChatUnreadQueryPort> unreadPortProvider;
    private final String publicOrigin;

    public SpaceService(
            JdbcTemplate db,
            ObjectMapper mapper,
            StringRedisTemplate redis,
            AtomicTokenStorePort tokens,
            RevocationLedgerPort revocationLedger,
            OutboxPublisherPort outbox,
            AuditLogPort auditLog,
            SpaceAuthorizationService authorization,
            SpaceIdempotencyStore idempotency,
            List<MembershipRevocationHook> revocationHooks,
            ObjectProvider<SpaceRoomPort> roomPortProvider,
            ObjectProvider<ProjectScopePort> projectPortProvider,
            ObjectProvider<ChatUnreadQueryPort> unreadPortProvider,
            @Value("${orbit.public-origin}") String publicOrigin) {
        this.db = db;
        this.mapper = mapper;
        this.redis = redis;
        this.tokens = tokens;
        this.revocationLedger = revocationLedger;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.authorization = authorization;
        this.idempotency = idempotency;
        this.revocationHooks = List.copyOf(revocationHooks);
        this.roomPortProvider = roomPortProvider;
        this.projectPortProvider = projectPortProvider;
        this.unreadPortProvider = unreadPortProvider;
        this.publicOrigin = publicOrigin.endsWith("/")
                ? publicOrigin.substring(0, publicOrigin.length() - 1)
                : publicOrigin;
    }

    @Transactional
    public SpaceDetail createSpace(
            String userId, String idempotencyKey, CreateSpaceRequest request) {
        String name = request.name().strip();
        if (name.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        String initialProjectName = request.initialProjectName();
        if (initialProjectName != null) {
            initialProjectName = initialProjectName.strip();
            if (initialProjectName.isEmpty()) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
        }
        String resolvedProjectName = initialProjectName;
        var canonicalInput = new CreateSpaceInput(
                request.name(), request.description(), request.initialProjectName());

        return idempotency.execute(
                        "CREATE_SPACE",
                        userId,
                        idempotencyKey,
                        canonicalInput,
                        201,
                        SpaceDetail.class,
                        () -> createSpaceInternal(userId, name, request.description(), resolvedProjectName))
                .body();
    }

    private SpaceDetail createSpaceInternal(
            String userId, String name, String description, String initialProjectName) {
        String spaceId = SpaceSecrets.id();
        db.update(
                """
                INSERT INTO spaces(id, name, description, owner_user_id, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                spaceId,
                name,
                description,
                userId);
        db.update(
                """
                INSERT INTO space_members(
                    space_id, user_id, status, role, membership_epoch, perm_version, joined_baseline_seq, recently_visited_at
                )
                VALUES (?, ?, 'ACTIVE', 'OWNER', 1, 1, 0, NULL)
                """,
                spaceId,
                userId);

        SpaceRoomPort rooms = roomPortProvider.getIfAvailable();
        String roomId = rooms != null ? rooms.ensureMainRoom(spaceId) : spaceId;

        List<ProjectSummary> projects = List.of();
        if (initialProjectName != null) {
            ProjectScopePort projectScope = projectPortProvider.getIfAvailable();
            if (projectScope == null) {
                throw new DomainException(KernelErrorCode.INVALID_REQUEST);
            }
            var ownerMembership = new SpaceAuthorizationPort.Membership(
                    spaceId, userId, SpaceRole.OWNER, 1L, 1L);
            var created = projectScope.createInitialProject(ownerMembership, initialProjectName);
            projects = List.of(new ProjectSummary(created.projectId(), created.name()));
        }

        auditLog.append(new AuditLogPort.Entry(
                SpaceSecrets.requestId(), userId, "SPACE_CREATE", spaceId, "SUCCESS", Instant.now()));

        var summary = new SpaceSummary(spaceId, name, SpaceRole.OWNER, 1L, null, 0L);
        return new SpaceDetail(summary, projects, roomId);
    }

    @Transactional(readOnly = true)
    public List<SpaceSummary> listMySpaces(String userId) {
        return db.query(
                """
                SELECT s.id, s.name, m.role, m.membership_epoch, m.recently_visited_at
                FROM space_members m
                JOIN spaces s ON s.id = m.space_id
                WHERE m.user_id = ? AND m.status = 'ACTIVE' AND s.status = 'ACTIVE'
                ORDER BY (m.recently_visited_at IS NULL) ASC, m.recently_visited_at DESC, s.created_at DESC, s.id ASC
                """,
                (rs, i) -> {
                    String spaceId = rs.getString(1);
                    String name = rs.getString(2);
                    SpaceRole role = SpaceRole.valueOf(rs.getString(3));
                    long epoch = rs.getLong(4);
                    Timestamp visited = rs.getTimestamp(5);
                    Instant recentlyVisitedAt = visited != null ? visited.toInstant() : null;
                    return new SpaceSummary(
                            spaceId,
                            name,
                            role,
                            epoch,
                            recentlyVisitedAt,
                            countUnread(spaceId, userId, epoch));
                },
                userId);
    }

    @Transactional
    public SpaceDetail getSpace(String spaceId, String userId, long epoch) {
        SpaceAuthorizationPort.Membership membership =
                authorization.requireMembership(spaceId, userId, epoch, SpaceRole.VIEWER);

        int updated = db.update(
                """
                UPDATE space_members
                SET recently_visited_at = CURRENT_TIMESTAMP(6), updated_at = CURRENT_TIMESTAMP(6)
                WHERE space_id = ? AND user_id = ? AND status = 'ACTIVE' AND membership_epoch = ?
                """,
                spaceId,
                userId,
                epoch);
        if (updated != 1) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }

        SpaceSummary summary = db.query(
                        """
                        SELECT s.name, m.role, m.membership_epoch, m.recently_visited_at
                        FROM space_members m
                        JOIN spaces s ON s.id = m.space_id
                        WHERE m.space_id = ? AND m.user_id = ? AND m.status = 'ACTIVE' AND s.status = 'ACTIVE'
                        """,
                        (rs, i) -> {
                            Timestamp visited = rs.getTimestamp(4);
                            long memberEpoch = rs.getLong(3);
                            return new SpaceSummary(
                                    spaceId,
                                    rs.getString(1),
                                    SpaceRole.valueOf(rs.getString(2)),
                                    memberEpoch,
                                    visited != null ? visited.toInstant() : null,
                                    countUnread(spaceId, userId, memberEpoch));
                        },
                        spaceId,
                        userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID));

        ProjectScopePort projectScope = projectPortProvider.getIfAvailable();
        List<ProjectSummary> projects = projectScope != null
                ? projectScope.listProjects(membership).stream()
                        .map(p -> new ProjectSummary(p.projectId(), p.name()))
                        .toList()
                : List.of();

        SpaceRoomPort rooms = roomPortProvider.getIfAvailable();
        String roomId = rooms != null ? rooms.currentPosition(spaceId).roomId() : spaceId;
        return new SpaceDetail(summary, projects, roomId);
    }

    @Transactional(readOnly = true)
    public List<MemberDto> listMembers(String spaceId) {
        return db.query(
                """
                SELECT m.user_id, u.display_name, m.role, m.membership_epoch
                FROM space_members m
                JOIN users u ON u.id = m.user_id
                JOIN spaces s ON s.id = m.space_id
                WHERE m.space_id = ? AND m.status = 'ACTIVE' AND s.status = 'ACTIVE'
                ORDER BY FIELD(m.role, 'OWNER', 'ADMIN', 'MEMBER', 'VIEWER'), u.display_name ASC, m.user_id ASC
                """,
                (rs, i) -> new MemberDto(
                        rs.getString(1),
                        rs.getString(2),
                        SpaceRole.valueOf(rs.getString(3)),
                        rs.getLong(4)),
                spaceId);
    }

    @Transactional(readOnly = true)
    public List<InviteDto> listInvites(String spaceId) {
        return db.query(
                """
                SELECT id, expires_at, max_uses, used_count, target_role, revoked
                FROM space_invites
                WHERE space_id = ?
                ORDER BY created_at DESC, id ASC
                """,
                (rs, i) -> new InviteDto(
                        rs.getString(1),
                        rs.getTimestamp(2).toInstant(),
                        rs.getLong(3),
                        rs.getLong(4),
                        InviteRole.valueOf(rs.getString(5)),
                        rs.getBoolean(6)),
                spaceId);
    }

    @Transactional
    public CreateInviteResponse createInvite(
            String spaceId,
            String actorUserId,
            long epoch,
            String idempotencyKey,
            CreateInviteRequest request) {
        SpaceAuthorizationPort.Membership actor =
                authorization.requireMembership(spaceId, actorUserId, epoch, SpaceRole.ADMIN);
        if (request.targetRole() == InviteRole.ADMIN && actor.role() != SpaceRole.OWNER) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        Instant expiresAt = request.expiresAt().truncatedTo(ChronoUnit.MICROS);
        if (expiresAt.isAfter(MAX_MYSQL_TIMESTAMP)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        var canonicalInput =
                new CreateInviteInput(spaceId, expiresAt, request.maxUses(), request.targetRole());
        return idempotency.execute(
                        "CREATE_INVITE",
                        actorUserId,
                        idempotencyKey,
                        canonicalInput,
                        201,
                        CreateInviteResponse.class,
                        () -> createInviteInternal(
                                spaceId, actorUserId, epoch, expiresAt, request.maxUses(), request.targetRole()))
                .body();
    }

    private CreateInviteResponse createInviteInternal(
            String spaceId,
            String actorUserId,
            long epoch,
            Instant expiresAt,
            long maxUses,
            InviteRole targetRole) {
        if (!expiresAt.isAfter(Instant.now())) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        requireActiveSpace(spaceId);
        LockedMember actor = requireActiveMemberForUpdate(spaceId, actorUserId, epoch, SpaceRole.ADMIN);
        if (targetRole == InviteRole.ADMIN && actor.role() != SpaceRole.OWNER) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }

        String inviteId = SpaceSecrets.id();
        String rawToken = SpaceSecrets.token();
        String tokenHash = SpaceSecrets.hash(rawToken);

        db.update(
                """
                INSERT INTO space_invites(
                    id, space_id, token_hash, target_role, max_uses, used_count, expires_at, revoked, created_by_user_id
                )
                VALUES (?, ?, ?, ?, ?, 0, ?, FALSE, ?)
                """,
                inviteId,
                spaceId,
                tokenHash,
                targetRole.name(),
                maxUses,
                Timestamp.from(expiresAt),
                actorUserId);

        auditLog.append(new AuditLogPort.Entry(
                SpaceSecrets.requestId(),
                actorUserId,
                "SPACE_INVITE_CREATE",
                spaceId,
                "SUCCESS",
                Instant.now()));

        InviteDto invite = new InviteDto(inviteId, expiresAt, maxUses, 0L, targetRole, false);
        return new CreateInviteResponse(invite, publicOrigin + "/join?token=" + rawToken);
    }

    @Transactional
    public void revokeInvite(String spaceId, String actorUserId, long epoch, String inviteId) {
        requireActiveSpace(spaceId);
        requireActiveMemberForUpdate(spaceId, actorUserId, epoch, SpaceRole.ADMIN);

        Boolean revoked = db.query(
                        "SELECT revoked FROM space_invites WHERE id = ? AND space_id = ? FOR UPDATE",
                        (rs, i) -> rs.getBoolean(1),
                        inviteId,
                        spaceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND));

        if (!revoked) {
            db.update(
                    "UPDATE space_invites SET revoked = TRUE WHERE id = ? AND space_id = ?",
                    inviteId,
                    spaceId);
            auditLog.append(new AuditLogPort.Entry(
                    SpaceSecrets.requestId(),
                    actorUserId,
                    "SPACE_INVITE_REVOKE",
                    spaceId,
                    "SUCCESS",
                    Instant.now()));
        }
    }

    @Transactional
    public SpaceSummary joinSpace(
            String userId, String idempotencyKey, JoinSpaceRequest request) {
        String rawToken = request.token().strip();
        if (rawToken.isEmpty()) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }
        return idempotency.execute(
                        "JOIN_SPACE",
                        userId,
                        idempotencyKey,
                        new JoinSpaceInput(rawToken),
                        200,
                        SpaceSummary.class,
                        () -> joinSpaceInternal(userId, rawToken))
                .body();
    }

    private SpaceSummary joinSpaceInternal(String userId, String rawToken) {
        String tokenHash = SpaceSecrets.hash(rawToken);
        InviteRow invite = db.query(
                        """
                        SELECT i.id, i.space_id, i.target_role, i.max_uses, i.used_count,
                               i.expires_at, i.revoked, s.name, s.status
                        FROM space_invites i
                        JOIN spaces s ON s.id = i.space_id
                        WHERE i.token_hash = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> new InviteRow(
                                rs.getString(1),
                                rs.getString(2),
                                InviteRole.valueOf(rs.getString(3)),
                                rs.getLong(4),
                                rs.getLong(5),
                                rs.getTimestamp(6).toInstant(),
                                rs.getBoolean(7),
                                rs.getString(8),
                                rs.getString(9)),
                        tokenHash)
                .stream()
                .findFirst()
                .orElse(null);

        Instant now = Instant.now();
        if (invite == null
                || !"ACTIVE".equals(invite.spaceStatus())
                || invite.revoked()
                || !invite.expiresAt().isAfter(now)
                || invite.usedCount() >= invite.maxUses()) {
            throw new DomainException(KernelErrorCode.INVITE_UNAVAILABLE);
        }

        ExistingMemberRow existing = db.query(
                        """
                        SELECT status, role, membership_epoch, perm_version, recently_visited_at
                        FROM space_members
                        WHERE space_id = ? AND user_id = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> {
                            Timestamp visited = rs.getTimestamp(5);
                            return new ExistingMemberRow(
                                    rs.getString(1),
                                    SpaceRole.valueOf(rs.getString(2)),
                                    rs.getLong(3),
                                    rs.getLong(4),
                                    visited != null ? visited.toInstant() : null);
                        },
                        invite.spaceId(),
                        userId)
                .stream()
                .findFirst()
                .orElse(null);

        if (existing != null && "ACTIVE".equals(existing.status())) {
            return new SpaceSummary(
                    invite.spaceId(),
                    invite.spaceName(),
                    existing.role(),
                    existing.membershipEpoch(),
                    existing.recentlyVisitedAt(),
                    countUnread(invite.spaceId(), userId, existing.membershipEpoch()));
        }

        db.update("UPDATE space_invites SET used_count = used_count + 1 WHERE id = ?", invite.id());

        SpaceRoomPort rooms = roomPortProvider.getIfAvailable();
        long baselineSeq = rooms != null ? rooms.currentPosition(invite.spaceId()).maxMessageSeq() : 0L;
        SpaceRole assignedRole = invite.targetRole().toSpaceRole();

        long newEpoch;
        if (existing != null) {
            newEpoch = existing.membershipEpoch() + 1;
            long newPermVersion = existing.permVersion() + 1;
            db.update(
                    """
                    UPDATE space_members
                    SET status = 'ACTIVE',
                        role = ?,
                        membership_epoch = ?,
                        perm_version = ?,
                        joined_baseline_seq = ?,
                        recently_visited_at = NULL,
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE space_id = ? AND user_id = ?
                    """,
                    assignedRole.name(),
                    newEpoch,
                    newPermVersion,
                    baselineSeq,
                    invite.spaceId(),
                    userId);
            afterCommit(() -> invalidateMembershipSafely(invite.spaceId(), userId, newPermVersion));
        } else {
            newEpoch = 1L;
            db.update(
                    """
                    INSERT INTO space_members(
                        space_id, user_id, status, role, membership_epoch, perm_version, joined_baseline_seq, recently_visited_at
                    )
                    VALUES (?, ?, 'ACTIVE', ?, 1, 1, ?, NULL)
                    """,
                    invite.spaceId(),
                    userId,
                    assignedRole.name(),
                    baselineSeq);
        }

        auditLog.append(new AuditLogPort.Entry(
                SpaceSecrets.requestId(), userId, "SPACE_INVITE_JOIN", invite.spaceId(), "SUCCESS", now));

        return new SpaceSummary(
                invite.spaceId(),
                invite.spaceName(),
                assignedRole,
                newEpoch,
                null,
                countUnread(invite.spaceId(), userId, newEpoch));
    }

    @Transactional
    public MemberDto changeMemberRole(
            String spaceId,
            String actorUserId,
            long epoch,
            String targetUserId,
            ChangeMemberRoleRequest request) {
        requireActiveSpace(spaceId);
        Map<String, LockedMember> locked = lockMembersOrdered(spaceId, actorUserId, targetUserId);
        LockedMember actor = validateActor(locked.get(actorUserId), epoch, SpaceRole.ADMIN);
        LockedMember target = locked.get(targetUserId);
        if (target == null || !"ACTIVE".equals(target.status())) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }
        if (actor.role() == SpaceRole.ADMIN) {
            if (target.role() == SpaceRole.OWNER
                    || target.role() == SpaceRole.ADMIN
                    || request.role() == InviteRole.ADMIN) {
                throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
            }
        }
        if (target.role() == SpaceRole.OWNER) {
            throw new DomainException(KernelErrorCode.SPACE_SINGLE_OWNER_REQUIRED);
        }

        SpaceRole newRole = request.role().toSpaceRole();
        String displayName = db.queryForObject(
                "SELECT display_name FROM users WHERE id = ?", String.class, targetUserId);

        if (target.role() != newRole) {
            long newPermVersion = target.permVersion() + 1;
            db.update(
                    """
                    UPDATE space_members
                    SET role = ?, perm_version = ?, updated_at = CURRENT_TIMESTAMP(6)
                    WHERE space_id = ? AND user_id = ? AND status = 'ACTIVE'
                    """,
                    newRole.name(),
                    newPermVersion,
                    spaceId,
                    targetUserId);
            auditLog.append(new AuditLogPort.Entry(
                    SpaceSecrets.requestId(),
                    actorUserId,
                    "SPACE_MEMBER_ROLE_CHANGE",
                    spaceId,
                    "SUCCESS",
                    Instant.now()));
            afterCommit(() -> invalidateMembershipSafely(spaceId, targetUserId, newPermVersion));
        }

        return new MemberDto(targetUserId, displayName, newRole, target.membershipEpoch());
    }

    @Transactional
    public void removeMember(
            String spaceId, String actorUserId, long epoch, String targetUserId) {
        requireActiveSpace(spaceId);
        Map<String, LockedMember> locked = lockMembersOrdered(spaceId, actorUserId, targetUserId);
        LockedMember actor = validateActor(locked.get(actorUserId), epoch, SpaceRole.ADMIN);
        LockedMember target = locked.get(targetUserId);
        if (target == null || !"ACTIVE".equals(target.status())) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }
        if (target.role() == SpaceRole.OWNER) {
            throw new DomainException(
                    actor.role() == SpaceRole.OWNER
                            ? KernelErrorCode.SPACE_SINGLE_OWNER_REQUIRED
                            : KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (target.role() == SpaceRole.ADMIN && actor.role() != SpaceRole.OWNER) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        revokeMembership(spaceId, actorUserId, target, "REMOVED");
    }

    @Transactional
    public void transferOwnership(
            String spaceId,
            String actorUserId,
            long epoch,
            String idempotencyKey,
            TransferOwnerRequest request) {
        authorization.requireMembership(spaceId, actorUserId, epoch, SpaceRole.OWNER);
        String newOwnerUserId = request.newOwnerUserId().strip();
        if (newOwnerUserId.isEmpty() || newOwnerUserId.equals(actorUserId)) {
            throw new DomainException(KernelErrorCode.INVALID_REQUEST);
        }

        idempotency.execute(
                "TRANSFER_OWNERSHIP",
                actorUserId,
                idempotencyKey,
                new TransferOwnerInput(spaceId, newOwnerUserId),
                204,
                Void.class,
                () -> {
                    transferOwnershipInternal(spaceId, actorUserId, epoch, newOwnerUserId);
                    return null;
                });
    }

    private void transferOwnershipInternal(
            String spaceId, String actorUserId, long epoch, String newOwnerUserId) {
        String spaceStatus = db.query(
                        "SELECT status FROM spaces WHERE id = ? FOR UPDATE",
                        (rs, i) -> rs.getString(1),
                        spaceId)
                .stream()
                .findFirst()
                .orElse(null);
        if (!"ACTIVE".equals(spaceStatus)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }

        Map<String, LockedMember> locked = lockMembersOrdered(spaceId, actorUserId, newOwnerUserId);
        LockedMember actor = validateActor(locked.get(actorUserId), epoch, SpaceRole.OWNER);
        LockedMember target = locked.get(newOwnerUserId);
        if (target == null || !"ACTIVE".equals(target.status())) {
            throw new DomainException(KernelErrorCode.RESOURCE_NOT_FOUND);
        }

        long targetNewPerm = target.permVersion() + 1;
        long actorNewPerm = actor.permVersion() + 1;
        db.update(
                """
                UPDATE space_members
                SET role = 'OWNER', perm_version = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE space_id = ? AND user_id = ? AND status = 'ACTIVE'
                """,
                targetNewPerm,
                spaceId,
                newOwnerUserId);
        db.update(
                """
                UPDATE space_members
                SET role = 'ADMIN', perm_version = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE space_id = ? AND user_id = ? AND status = 'ACTIVE'
                """,
                actorNewPerm,
                spaceId,
                actorUserId);
        db.update("UPDATE spaces SET owner_user_id = ? WHERE id = ?", newOwnerUserId, spaceId);

        auditLog.append(new AuditLogPort.Entry(
                SpaceSecrets.requestId(),
                actorUserId,
                "SPACE_OWNER_TRANSFER",
                spaceId,
                "SUCCESS",
                Instant.now()));

        afterCommit(() -> {
            invalidateMembershipSafely(spaceId, newOwnerUserId, targetNewPerm);
            invalidateMembershipSafely(spaceId, actorUserId, actorNewPerm);
        });
    }

    @Transactional
    public void leaveSpace(String spaceId, String actorUserId, long epoch) {
        requireActiveSpace(spaceId);
        LockedMember actor = requireActiveMemberForUpdate(spaceId, actorUserId, epoch, SpaceRole.VIEWER);
        if (actor.role() == SpaceRole.OWNER) {
            throw new DomainException(KernelErrorCode.SPACE_SINGLE_OWNER_REQUIRED);
        }
        revokeMembership(spaceId, actorUserId, actor, "LEFT");
    }

    /** Idempotent and monotonic replay handler for {@code RevocationLedgerPort.Target.MEMBERSHIP_EPOCH}. */
    public void replayMembershipRevocation(RevocationLedgerPort.Entry entry) {
        var r = entry.revocation();
        if (r.target() != RevocationLedgerPort.Target.MEMBERSHIP_EPOCH) {
            throw new IllegalArgumentException("Unsupported revocation target: " + r.target());
        }
        LockedMember existing = lockMember(r.spaceId(), r.targetId());
        if (existing == null
                || !"ACTIVE".equals(existing.status())
                || existing.membershipEpoch() > r.version()) {
            return;
        }
        long newPermVersion = existing.permVersion() + 1;
        int updated = db.update(
                """
                UPDATE space_members
                SET status = 'REMOVED', perm_version = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE space_id = ? AND user_id = ? AND status = 'ACTIVE' AND membership_epoch <= ?
                """,
                newPermVersion,
                r.spaceId(),
                r.targetId(),
                r.version());
        if (updated == 1) {
            var hookRevocation = new MembershipRevocationHook.Revocation(
                    r.spaceId(), r.targetId(), r.version(), newPermVersion);
            for (MembershipRevocationHook hook : revocationHooks) {
                hook.onRevoked(hookRevocation);
            }
            afterCommit(() -> invalidateMembershipSafely(r.spaceId(), r.targetId(), newPermVersion));
        }
    }

    private void revokeMembership(
            String spaceId, String actorUserId, LockedMember target, String terminalStatus) {
        long endedEpoch = target.membershipEpoch();
        long newPermVersion = target.permVersion() + 1;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        int updated = db.update(
                """
                UPDATE space_members
                SET status = ?, perm_version = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE space_id = ? AND user_id = ? AND status = 'ACTIVE' AND membership_epoch = ?
                """,
                terminalStatus,
                newPermVersion,
                spaceId,
                target.userId(),
                endedEpoch);
        if (updated != 1) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }

        var hookRevocation = new MembershipRevocationHook.Revocation(
                spaceId, target.userId(), endedEpoch, newPermVersion);
        for (MembershipRevocationHook hook : revocationHooks) {
            hook.onRevoked(hookRevocation);
        }

        revocationLedger.append(new RevocationLedgerPort.Revocation(
                SpaceSecrets.id(),
                RevocationLedgerPort.Target.MEMBERSHIP_EPOCH,
                spaceId,
                target.userId(),
                endedEpoch,
                now));

        Map<String, Object> payload = Map.of(
                "spaceId", spaceId,
                "userId", target.userId(),
                "endedMembershipEpoch", endedEpoch,
                "newPermVersion", newPermVersion,
                "status", terminalStatus,
                "actorUserId", actorUserId);
        var event = new OutboxPublisherPort.Event(
                SpaceSecrets.id(),
                "MEMBERSHIP_REVOKED",
                "SPACE",
                spaceId + ":" + target.userId(),
                SpaceSecrets.toJson(mapper, payload),
                now);
        outbox.publish(event);

        String action = "REMOVED".equals(terminalStatus) ? "SPACE_MEMBER_REMOVE" : "SPACE_MEMBER_LEAVE";
        auditLog.append(new AuditLogPort.Entry(
                SpaceSecrets.requestId(), actorUserId, action, spaceId, "SUCCESS", now));

        String eventJson = SpaceSecrets.toJson(mapper, event);
        afterCommit(() -> {
            invalidateMembershipSafely(spaceId, target.userId(), newPermVersion);
            broadcastEventSafely(eventJson);
        });
    }

    private void requireActiveSpace(String spaceId) {
        boolean active = Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM spaces WHERE id = ? AND status = 'ACTIVE')",
                Boolean.class,
                spaceId));
        if (!active) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
    }

    private LockedMember requireActiveMemberForUpdate(
            String spaceId, String userId, long expectedEpoch, SpaceRole minimumRole) {
        return validateActor(lockMember(spaceId, userId), expectedEpoch, minimumRole);
    }

    private static LockedMember validateActor(
            LockedMember actor, long expectedEpoch, SpaceRole minimumRole) {
        if (actor == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (!"ACTIVE".equals(actor.status()) || actor.membershipEpoch() != expectedEpoch) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }
        if (!actor.role().atLeast(minimumRole)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        return actor;
    }

    private Map<String, LockedMember> lockMembersOrdered(
            String spaceId, String userA, String userB) {
        var result = new LinkedHashMap<String, LockedMember>();
        if (userA.equals(userB)) {
            result.put(userA, lockMember(spaceId, userA));
            return result;
        }
        String first = userA.compareTo(userB) <= 0 ? userA : userB;
        String second = userA.compareTo(userB) <= 0 ? userB : userA;
        result.put(first, lockMember(spaceId, first));
        result.put(second, lockMember(spaceId, second));
        return result;
    }

    private LockedMember lockMember(String spaceId, String userId) {
        return db.query(
                        """
                        SELECT user_id, status, role, membership_epoch, perm_version
                        FROM space_members
                        WHERE space_id = ? AND user_id = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> new LockedMember(
                                rs.getString(1),
                                rs.getString(2),
                                SpaceRole.valueOf(rs.getString(3)),
                                rs.getLong(4),
                                rs.getLong(5)),
                        spaceId,
                        userId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private long countUnread(String spaceId, String userId, long epoch) {
        ChatUnreadQueryPort unreadQuery = unreadPortProvider.getIfAvailable();
        if (unreadQuery == null) {
            return 0L;
        }
        return Math.max(0L, unreadQuery.countUnread(spaceId, userId, epoch));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    private void invalidateMembershipSafely(String spaceId, String userId, long newPermVersion) {
        try {
            tokens.invalidateMembership(spaceId, userId, newPermVersion);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Failed to invalidate membership cache (spaceId={}, cause={})",
                    spaceId,
                    ex.getClass().getSimpleName());
        }
    }

    private void broadcastEventSafely(String eventJson) {
        try {
            redis.convertAndSend(EVENTS_CHANNEL, eventJson);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Failed to broadcast revocation event (cause={})",
                    ex.getClass().getSimpleName());
        }
    }
}
