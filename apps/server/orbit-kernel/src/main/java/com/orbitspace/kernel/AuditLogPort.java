package com.orbitspace.kernel;

import java.time.Instant;

public interface AuditLogPort {
    record Entry(String requestId, String actorId, String action, String resourceId,
                 String outcome, Instant occurredAt) {}

    /** Record safe identifiers and outcomes in the caller's transaction. */
    void append(Entry entry);
}
