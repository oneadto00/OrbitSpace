package com.orbitspace.kernel;

import java.time.Duration;
import java.util.Optional;

public interface AtomicTokenStorePort {
    record TicketGrant(String userId, String sessionId, String spaceId, String roomId, long membershipEpoch) {}

    /** Issue an unpredictable, URL-safe, short-lived ticket bound to exactly one room. */
    String issueTicket(TicketGrant grant, Duration ttl);

    /** Remove and return as one atomic operation. Recheck the session and membership on admission. */
    Optional<TicketGrant> consumeTicket(String ticket);

    Optional<SpaceAuthorizationPort.Membership> findMembership(
            String spaceId, String userId, long expectedEpoch, long currentPermVersion);

    /** Only cache a value still current at insertion. A late read cannot cross an invalidation fence. */
    void cacheMembership(SpaceAuthorizationPort.Membership membership, Duration ttl);

    /** Atomically evict older values and advance the version fence, even on a cache miss. */
    void invalidateMembership(String spaceId, String userId, long newPermVersion);
}
