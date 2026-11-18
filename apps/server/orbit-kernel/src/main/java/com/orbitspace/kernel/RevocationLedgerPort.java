package com.orbitspace.kernel;

import java.time.Instant;
import java.util.List;

/** The recovery source and its verified high-water mark must survive restoration of an older business backup. */
public interface RevocationLedgerPort {
    enum Target { MEMBERSHIP_EPOCH, DEVICE_CREDENTIAL, RESOURCE_FORCE_PURGE }

    /** spaceId scopes the member, credential or resource identified by targetId. */
    record Revocation(String eventId, Target target, String spaceId, String targetId,
                      long version, Instant effectiveAt) {}
    record Entry(long sequence, Revocation revocation) {}

    /** Record a transactionally durable intent; deduplicate by eventId. Return the assigned sequence. */
    long append(Revocation revocation);

    /** Fail closed if the independent recovery source cannot certify completeness. */
    long verifiedHighWaterMark();

    /** Return ordered entries in (afterSequence, throughSequence] for idempotent recovery replay. */
    List<Entry> readAfter(long afterSequence, long throughSequence, int limit);
}
