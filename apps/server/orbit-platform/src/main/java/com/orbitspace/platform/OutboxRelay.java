package com.orbitspace.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.OutboxEventHandler;
import com.orbitspace.kernel.OutboxPublisherPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

public class OutboxRelay implements OutboxPublisherPort {
    static final String JOB_READY = "PLATFORM_JOB_READY";
    static final String JOB_STREAM = "orbit:async:jobs";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final org.springframework.beans.factory.ObjectProvider<OutboxEventHandler> handlers;

    public OutboxRelay(
            JdbcTemplate db,
            TransactionTemplate tx,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            org.springframework.beans.factory.ObjectProvider<OutboxEventHandler> handlers) {
        this.db = db;
        this.tx = tx;
        this.redis = redis;
        this.mapper = mapper;
        this.handlers = handlers;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Event event) {
        String digest = PlatformValues.digest(PlatformValues.json(mapper, event));
        db.update(
                """
                INSERT INTO outbox_events(event_id,aggregate_type,aggregate_id,event_type,payload,input_digest,created_at)
                VALUES (?,?,?,?,CAST(? AS JSON),?,?) ON DUPLICATE KEY UPDATE event_id=event_id
                """,
                event.eventId(),
                event.aggregateType(),
                event.aggregateId(),
                event.type(),
                event.payloadJson(),
                digest,
                PlatformValues.time(event.occurredAt()));
        String stored =
                db.queryForObject(
                        "SELECT input_digest FROM outbox_events WHERE event_id=? FOR UPDATE",
                        String.class,
                        event.eventId());
        if (!digest.equals(stored)) throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Scheduled(fixedDelayString = "${orbit.platform.relay-interval:PT1S}")
    public void dispatchDue() {
        List<String> ids =
                db.queryForList(
                        """
                        SELECT event_id FROM outbox_events WHERE status='PENDING' AND next_retry_at<=CURRENT_TIMESTAMP(6)
                        ORDER BY next_retry_at,event_id LIMIT 64
                        """,
                        String.class);
        for (String id : ids) {
            try {
                tx.executeWithoutResult(status -> dispatchLocked(id));
            } catch (RuntimeException e) {
                db.update(
                        """
                        UPDATE outbox_events SET next_retry_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(retries,8))),CURRENT_TIMESTAMP(6)),
                            retries=retries+1 WHERE event_id=? AND status='PENDING'
                        """,
                        id);
                log.warn(
                        "Outbox delivery deferred (eventId={}, cause={})",
                        id,
                        e.getClass().getSimpleName());
            }
        }
    }

    private void dispatchLocked(String id) {
        List<Event> due =
                db.query(
                        """
                        SELECT event_id,event_type,aggregate_type,aggregate_id,payload,created_at FROM outbox_events
                        WHERE event_id=? AND status='PENDING' AND next_retry_at<=CURRENT_TIMESTAMP(6) FOR UPDATE SKIP LOCKED
                        """,
                        (rs, i) ->
                                new Event(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getTimestamp(6).toInstant()),
                        id);
        if (due.isEmpty()) return;
        Event event = due.getFirst();
        List<OutboxEventHandler> matching =
                handlers.orderedStream()
                        .filter(h -> event.type().startsWith(h.eventTypePrefix()))
                        .toList();
        for (OutboxEventHandler h : matching) h.handle(event);
        if (JOB_READY.equals(event.type())) {
            redis.opsForStream()
                    .add(
                            JOB_STREAM,
                            Map.of("jobId", event.aggregateId(), "eventId", event.eventId()));
            redis.opsForStream().trim(JOB_STREAM, 10000, true);
        } else {
            redis.convertAndSend("orbit:events", PlatformValues.json(mapper, event));
        }
        db.update("UPDATE outbox_events SET status='PUBLISHED' WHERE event_id=?", id);
    }
}
