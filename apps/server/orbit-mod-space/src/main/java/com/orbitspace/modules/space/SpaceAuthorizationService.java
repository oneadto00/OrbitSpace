package com.orbitspace.modules.space;

import com.orbitspace.kernel.AtomicTokenStorePort;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.SpaceAuthorizationPort;
import com.orbitspace.kernel.SpaceRole;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/** Authoritative space RBAC and membership-epoch verification backed by MySQL and fenced Redis cache. */
public class SpaceAuthorizationService implements SpaceAuthorizationPort {
    private static final Duration MEMBERSHIP_CACHE_TTL = Duration.ofSeconds(5);

    private record MemberRow(String status, SpaceRole role, long membershipEpoch, long permVersion) {}

    private final JdbcTemplate db;
    private final AtomicTokenStorePort tokens;

    public SpaceAuthorizationService(JdbcTemplate db, AtomicTokenStorePort tokens) {
        this.db = db;
        this.tokens = tokens;
    }

    @Override
    public Membership requireMembership(
            String spaceId, String userId, long expectedEpoch, SpaceRole minimumRole) {
        if (spaceId == null || spaceId.isBlank() || userId == null || userId.isBlank() || minimumRole == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (expectedEpoch < 1) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }
        Long currentPermVersion = db.query(
                        """
                        SELECT m.perm_version
                        FROM space_members m
                        JOIN spaces s ON s.id = m.space_id
                        WHERE m.space_id = ? AND m.user_id = ? AND s.status = 'ACTIVE'
                        """,
                        (rs, i) -> rs.getLong(1),
                        spaceId,
                        userId)
                .stream()
                .findFirst()
                .orElse(null);
        if (currentPermVersion == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }

        var cached = tokens.findMembership(spaceId, userId, expectedEpoch, currentPermVersion);
        if (cached.isPresent()) {
            Membership member = cached.get();
            if (!member.role().atLeast(minimumRole)) {
                throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
            }
            return member;
        }

        MemberRow row = db.query(
                        """
                        SELECT m.status, m.role, m.membership_epoch, m.perm_version
                        FROM space_members m
                        JOIN spaces s ON s.id = m.space_id
                        WHERE m.space_id = ? AND m.user_id = ? AND s.status = 'ACTIVE'
                        """,
                        (rs, i) -> new MemberRow(
                                rs.getString(1),
                                SpaceRole.valueOf(rs.getString(2)),
                                rs.getLong(3),
                                rs.getLong(4)),
                        spaceId,
                        userId)
                .stream()
                .findFirst()
                .orElse(null);
        if (row == null) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        if (!"ACTIVE".equals(row.status()) || row.membershipEpoch() != expectedEpoch) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }

        Membership membership = new Membership(
                spaceId, userId, row.role(), row.membershipEpoch(), row.permVersion());
        tokens.cacheMembership(membership, MEMBERSHIP_CACHE_TTL);
        if (!membership.role().atLeast(minimumRole)) {
            throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        }
        return membership;
    }
}
