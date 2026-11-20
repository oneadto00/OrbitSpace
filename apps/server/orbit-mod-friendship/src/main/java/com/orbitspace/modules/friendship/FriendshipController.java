package com.orbitspace.modules.friendship;

import com.orbitspace.kernel.AuthenticatedPrincipal;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.RateLimitPort;
import com.orbitspace.modules.friendship.FriendshipDtos.FriendListResponse;
import com.orbitspace.modules.friendship.FriendshipDtos.FriendRequestDto;
import com.orbitspace.modules.friendship.FriendshipDtos.FriendRequestListResponse;
import com.orbitspace.modules.friendship.FriendshipDtos.RequestDirection;
import com.orbitspace.modules.friendship.FriendshipDtos.SendFriendRequestBody;
import com.orbitspace.modules.friendship.FriendshipDtos.UserSearchResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FriendshipController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final FriendshipService friendships;

    public FriendshipController(FriendshipService friendships) {
        this.friendships = friendships;
    }

    @GetMapping("/api/v1/users/search")
    public UserSearchResponse searchUsers(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @RequestParam("q") String query,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request,
            HttpServletResponse response) {
        enforceRateLimit(
                friendships.checkSearchRateLimit(account.userId(), request.getRemoteAddr()),
                response);
        return friendships.searchUsers(account.userId(), query, limit);
    }

    @PostMapping("/api/v1/friend-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public FriendRequestDto sendFriendRequest(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody SendFriendRequestBody body,
            HttpServletResponse response) {
        enforceRateLimit(friendships.checkSendRequestRateLimit(account.userId()), response);
        return friendships.sendFriendRequest(account.userId(), idempotencyKey, body.targetUserId());
    }

    @GetMapping("/api/v1/friend-requests")
    public FriendRequestListResponse listFriendRequests(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @RequestParam(value = "direction", required = false) RequestDirection direction) {
        return friendships.listFriendRequests(account.userId(), direction);
    }

    @PostMapping("/api/v1/friend-requests/{requestId}/accept")
    public FriendRequestDto acceptFriendRequest(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String requestId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        return friendships.acceptFriendRequest(account.userId(), idempotencyKey, requestId);
    }

    @PostMapping("/api/v1/friend-requests/{requestId}/reject")
    public FriendRequestDto rejectFriendRequest(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String requestId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        return friendships.rejectFriendRequest(account.userId(), idempotencyKey, requestId);
    }

    @PostMapping("/api/v1/friend-requests/{requestId}/cancel")
    public FriendRequestDto cancelFriendRequest(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String requestId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
        return friendships.cancelFriendRequest(account.userId(), idempotencyKey, requestId);
    }

    @GetMapping("/api/v1/friends")
    public FriendListResponse listFriends(
            @AuthenticationPrincipal AuthenticatedPrincipal account) {
        return friendships.listFriends(account.userId());
    }

    @DeleteMapping("/api/v1/friends/{friendUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFriend(
            @AuthenticationPrincipal AuthenticatedPrincipal account,
            @PathVariable String friendUserId) {
        friendships.removeFriend(account.userId(), friendUserId);
    }

    private static void enforceRateLimit(
            RateLimitPort.Decision decision, HttpServletResponse response) {
        if (decision != null && !decision.allowed()) {
            long seconds = Math.max(1L, decision.retryAfter().plusMillis(999).toSeconds());
            response.setHeader("Retry-After", Long.toString(seconds));
            throw new DomainException(KernelErrorCode.RATE_LIMITED);
        }
    }
}
