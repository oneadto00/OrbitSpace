package com.orbitspace.modules.space;

import com.orbitspace.kernel.AuthenticatedPrincipal;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.SpaceRole;
import com.orbitspace.modules.space.SpaceDtos.ChangeMemberRoleRequest;
import com.orbitspace.modules.space.SpaceDtos.CreateInviteRequest;
import com.orbitspace.modules.space.SpaceDtos.CreateInviteResponse;
import com.orbitspace.modules.space.SpaceDtos.CreateSpaceRequest;
import com.orbitspace.modules.space.SpaceDtos.InviteDto;
import com.orbitspace.modules.space.SpaceDtos.JoinSpaceRequest;
import com.orbitspace.modules.space.SpaceDtos.MemberDto;
import com.orbitspace.modules.space.SpaceDtos.SpaceDetail;
import com.orbitspace.modules.space.SpaceDtos.SpaceSummary;
import com.orbitspace.modules.space.SpaceDtos.TransferOwnerRequest;
import com.orbitspace.security.RequireSpaceRole;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SpaceController {
    private static final String EPOCH_HEADER = "X-Membership-Epoch";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final SpaceService spaces;

    public SpaceController(SpaceService spaces) {
        this.spaces = spaces;
    }

    @PostMapping("/api/v1/spaces")
    @ResponseStatus(HttpStatus.CREATED)
    public SpaceDetail createSpace(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateSpaceRequest body) {
        return spaces.createSpace(account.userId(), idempotencyKey, body);
    }

    @GetMapping("/api/v1/spaces")
    public List<SpaceSummary> listMySpaces(@AuthenticationPrincipal AuthenticatedPrincipal account) {
        return spaces.listMySpaces(account.userId());
    }

    @GetMapping("/api/v1/spaces/{spaceId}")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public SpaceDetail getSpace(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            HttpServletRequest request) {
        return spaces.getSpace(spaceId, account.userId(), membershipEpoch(request));
    }

    @GetMapping("/api/v1/spaces/{spaceId}/members")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public List<MemberDto> listMembers(@PathVariable String spaceId) {
        return spaces.listMembers(spaceId);
    }

    @GetMapping("/api/v1/spaces/{spaceId}/invites")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public List<InviteDto> listInvites(@PathVariable String spaceId) {
        return spaces.listInvites(spaceId);
    }

    @PostMapping("/api/v1/spaces/{spaceId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireSpaceRole(SpaceRole.ADMIN)
    public CreateInviteResponse createInvite(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateInviteRequest body,
            HttpServletRequest request) {
        return spaces.createInvite(
                spaceId, account.userId(), membershipEpoch(request), idempotencyKey, body);
    }

    @DeleteMapping("/api/v1/spaces/{spaceId}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireSpaceRole(SpaceRole.ADMIN)
    public void revokeInvite(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String inviteId,
            HttpServletRequest request) {
        spaces.revokeInvite(spaceId, account.userId(), membershipEpoch(request), inviteId);
    }

    @PostMapping("/api/v1/space-invites/join")
    public SpaceSummary joinSpace(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody JoinSpaceRequest body) {
        return spaces.joinSpace(account.userId(), idempotencyKey, body);
    }

    @PatchMapping("/api/v1/spaces/{spaceId}/members/{userId}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public MemberDto changeMemberRole(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String userId,
            @Valid @RequestBody ChangeMemberRoleRequest body,
            HttpServletRequest request) {
        return spaces.changeMemberRole(
                spaceId, account.userId(), membershipEpoch(request), userId, body);
    }

    @DeleteMapping("/api/v1/spaces/{spaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireSpaceRole(SpaceRole.ADMIN)
    public void removeMember(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @PathVariable String userId,
            HttpServletRequest request) {
        spaces.removeMember(spaceId, account.userId(), membershipEpoch(request), userId);
    }

    @PutMapping("/api/v1/spaces/{spaceId}/owner")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireSpaceRole(SpaceRole.OWNER)
    public void transferOwnership(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferOwnerRequest body,
            HttpServletRequest request) {
        spaces.transferOwnership(
                spaceId, account.userId(), membershipEpoch(request), idempotencyKey, body);
    }

    @PostMapping("/api/v1/spaces/{spaceId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireSpaceRole(SpaceRole.VIEWER)
    public void leaveSpace(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String spaceId,
            HttpServletRequest request) {
        spaces.leaveSpace(spaceId, account.userId(), membershipEpoch(request));
    }

    private static long membershipEpoch(HttpServletRequest request) {
        try {
            return Long.parseLong(request.getHeader(EPOCH_HEADER));
        } catch (NumberFormatException ex) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }
    }
}
