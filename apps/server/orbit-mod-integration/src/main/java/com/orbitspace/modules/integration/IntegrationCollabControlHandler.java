package com.orbitspace.modules.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.OutboxEventHandler;
import com.orbitspace.kernel.OutboxPublisherPort.Event;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;

final class IntegrationCollabControlHandler implements OutboxEventHandler {
    private final String prefix;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    IntegrationCollabControlHandler(String prefix, StringRedisTemplate redis, ObjectMapper json) {
        this.prefix = prefix;
        this.redis = redis;
        this.json = json;
    }

    public String eventTypePrefix() { return prefix; }

    public void handle(Event event) {
        try {
            var payload = json.readTree(event.payloadJson());
            Map<String,Object> control;
            switch (event.type()) {
                case "MEMBERSHIP_REVOKED" -> control = Map.of("type",event.type(),
                        "spaceId",payload.required("spaceId").textValue(), "userId",payload.required("userId").textValue(),
                        "membershipEpoch",payload.required("endedMembershipEpoch").longValue());
                case "DOCUMENT_DELETED","DOCUMENT_PURGED","DOCUMENT_CHANGED" -> control = Map.of(
                        "type",event.type().equals("DOCUMENT_PURGED") ? "DOCUMENT_DELETED" : event.type(),
                        "documentId", payload.required("documentId").textValue());
                default -> { return; }
            }
            // Duplicate deliveries repeat an epoch-scoped disconnect or a durable-boundary reload.
            redis.convertAndSend("orbit:collab:control", json.writeValueAsString(control));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid collaboration event",ex);
        }
    }
}
