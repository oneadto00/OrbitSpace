package com.orbitspace.kernel;

public interface ChatUnreadQueryPort {
    /** Count eligible actual messages after max(join baseline, last read) in the current member epoch. */
    long countUnread(String spaceId, String userId, long membershipEpoch);
}
