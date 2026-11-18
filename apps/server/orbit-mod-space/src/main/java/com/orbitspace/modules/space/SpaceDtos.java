package com.orbitspace.modules.space;

import com.orbitspace.kernel.SpaceRole;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** OpenAPI wire DTOs for space lifecycle, membership, invitations, and RBAC operations. */
public final class SpaceDtos {
    private SpaceDtos() {}

    public enum InviteRole {
        ADMIN(SpaceRole.ADMIN),
        MEMBER(SpaceRole.MEMBER),
        VIEWER(SpaceRole.VIEWER);

        private final SpaceRole spaceRole;

        InviteRole(SpaceRole spaceRole) {
            this.spaceRole = spaceRole;
        }

        public SpaceRole toSpaceRole() {
            return spaceRole;
        }
    }

    public record ProjectSummary(String projectId, String name) {}

    public record SpaceSummary(
            String spaceId,
            String name,
            SpaceRole role,
            long membershipEpoch,
            Instant recentlyVisitedAt,
            long unreadDiscussionCount) {}

    public record SpaceDetail(SpaceSummary space, List<ProjectSummary> projects, String roomId) {}

    public record MemberDto(
            String userId, String displayName, SpaceRole role, long membershipEpoch) {}

    public record InviteDto(
            String inviteId,
            Instant expiresAt,
            long maxUses,
            long usedCount,
            InviteRole targetRole,
            boolean revoked) {}

    public record CreateInviteResponse(InviteDto invite, String joinUrl) {}

    public record CreateSpaceRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String description,
            @Size(max = 120) String initialProjectName) {}

    public record CreateInviteRequest(
            @NotNull Instant expiresAt,
            @NotNull @Min(1) @Max(9007199254740991L) Long maxUses,
            @NotNull InviteRole targetRole) {}

    public record JoinSpaceRequest(@NotBlank @Size(max = 256) String token) {}

    public record ChangeMemberRoleRequest(@NotNull InviteRole role) {}

    public record TransferOwnerRequest(@NotBlank @Size(max = 160) String newOwnerUserId) {}
}
