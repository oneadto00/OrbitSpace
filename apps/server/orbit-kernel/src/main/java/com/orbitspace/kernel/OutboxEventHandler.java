package com.orbitspace.kernel;

public interface OutboxEventHandler {
    /** Match the event type by prefix. Each matching handler must deduplicate by event ID. */
    String eventTypePrefix();

    void handle(OutboxPublisherPort.Event event);
}
