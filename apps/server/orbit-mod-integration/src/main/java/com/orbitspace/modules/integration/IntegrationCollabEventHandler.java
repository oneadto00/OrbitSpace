package com.orbitspace.modules.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.MembershipRevocationHook;
import com.orbitspace.kernel.OutboxPublisherPort.Event;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Fast post-commit notification. The durable outbox repeats deliveries lost during a crash. */
public class IntegrationCollabEventHandler implements MembershipRevocationHook {
    private static final Logger log = LoggerFactory.getLogger(IntegrationCollabEventHandler.class);
    private final ApplicationEventPublisher events;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final IntegrationCollabControlHandler documents;

    public IntegrationCollabEventHandler(ApplicationEventPublisher events, StringRedisTemplate redis, ObjectMapper json) {
        this.events = events;
        this.redis = redis;
        this.json = json;
        this.documents = new IntegrationCollabControlHandler("DOCUMENT_",redis,json);
    }

    public void onRevoked(Revocation revocation) {
        events.publishEvent(revocation);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void membershipChanged(Revocation revocation) {
        try {
            redis.convertAndSend("orbit:collab:control",json.writeValueAsString(Map.of(
                    "type","MEMBERSHIP_REVOKED","spaceId",revocation.spaceId(),
                    "userId",revocation.userId(),"membershipEpoch",revocation.endedMembershipEpoch())));
        } catch (Exception ex) {
            log.warn("Collaboration disconnect deferred to the outbox");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void documentChanged(Event event) {
        if (!event.type().startsWith("DOCUMENT_")) return;
        try {
            documents.handle(event);
        } catch (RuntimeException ex) {
            log.warn("Collaboration notification deferred to the outbox");
        }
    }
}
