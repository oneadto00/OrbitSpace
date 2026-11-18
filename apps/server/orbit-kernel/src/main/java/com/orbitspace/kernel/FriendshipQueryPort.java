package com.orbitspace.kernel;

import java.util.Collection;
import java.util.Set;

/** Read-only cross-module SPI for verifying active friendships required by independent airdrop. */
public interface FriendshipQueryPort {
    boolean areFriends(String userIdA, String userIdB);

    Set<String> filterFriends(String userId, Collection<String> candidateUserIds);

    default void requireFriends(String userIdA, String userIdB) {
        if (!areFriends(userIdA, userIdB)) {
            throw new DomainException(KernelErrorCode.NOT_FRIENDS_FOR_AIRDROP);
        }
    }
}
