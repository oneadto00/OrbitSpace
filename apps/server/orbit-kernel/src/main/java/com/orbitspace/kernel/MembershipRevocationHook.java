package com.orbitspace.kernel;

public interface MembershipRevocationHook {
    record Revocation(String spaceId, String userId, long endedMembershipEpoch, long newPermVersion) {}

    /** Invoked in the removal transaction. Invalidate persisted grants for the ended epoch. */
    void onRevoked(Revocation revocation);
}
