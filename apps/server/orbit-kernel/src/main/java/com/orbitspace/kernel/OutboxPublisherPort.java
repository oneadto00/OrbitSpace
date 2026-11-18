package com.orbitspace.kernel;

import java.time.Instant;

public interface OutboxPublisherPort {
    record Event(String eventId, String type, String aggregateType, String aggregateId,
                 String payloadJson, Instant occurredAt) {}

    /** Append in the caller's business transaction. Delivery begins after commit. */
    void publish(Event event);
}
