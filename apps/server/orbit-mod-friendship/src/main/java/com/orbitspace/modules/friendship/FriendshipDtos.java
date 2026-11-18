package com.orbitspace.modules.friendship;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Immutable DTOs and request models for the P2 basic friendship domain. */
public final class FriendshipDtos {
    private FriendshipDtos() {}

    public enum FriendRequestStatus {
        PENDING,
        ACCEPTED,
        REJECTED,
        CANCELLED
    }

    public enum RequestDirection {
        INCOMING,
        OUTGOING,
        ALL
    }

    public record UserSearchHit(String userId, String displayName, String emailMasked) {}

    public record UserSearchResponse(List<UserSearchHit> items) {}

    public record SendFriendRequestBody(@NotBlank @Size(max = 160) String targetUserId) {}

    public record FriendRequestDto(
            String requestId,
            String requesterUserId,
            String recipientUserId,
            FriendRequestStatus status,
            Instant createdAt,
            Instant updatedAt) {}

    public record FriendRequestListResponse(List<FriendRequestDto> items) {}

    public record FriendSummary(String userId, String displayName, Instant becameFriendsAt) {}

    public record FriendListResponse(List<FriendSummary> items) {}
}
