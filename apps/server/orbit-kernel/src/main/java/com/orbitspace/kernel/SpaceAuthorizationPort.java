package com.orbitspace.kernel;

/** Deny with a DomainException if the member, epoch or minimum role is invalid. */
public interface SpaceAuthorizationPort {
    record Membership(String spaceId, String userId, SpaceRole role, long membershipEpoch, long permVersion) {}

    Membership requireMembership(String spaceId, String userId, long expectedEpoch, SpaceRole minimumRole);
}
