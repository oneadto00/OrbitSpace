package com.orbitspace.app.config;

import static com.orbitspace.kernel.ModuleSecurityCustomizer.Access.PUBLIC;

import com.orbitspace.kernel.ModuleSecurityCustomizer;
import com.orbitspace.kernel.ModuleSecurityCustomizer.Route;
import com.orbitspace.kernel.OutboxEventHandler;
import com.orbitspace.kernel.OutboxPublisherPort;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configures full-stack Actuator observability metrics and non-sensitive telemetry summaries for
 * chat persistence/delivery, sync failures, outbox backlog, document save ACK latency, async
 * worker queue wait/processing time, quota rejections, storage growth, and revocation propagation.
 */
@AutoConfiguration(afterName = {
    "com.orbitspace.platform.PlatformAutoConfiguration",
    "com.orbitspace.modules.integration.IntegrationAutoConfiguration",
    "com.orbitspace.modules.document.DocumentAutoConfiguration"
})
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@EnableScheduling
public class OrbitObservabilityAutoConfiguration {

    @Bean
    ModuleSecurityCustomizer actuatorObservabilityRoutes() {
        // Exposed only on the internal server port (Nginx public :8443 only proxies /api/, /ws/chat, /collab).
        return () ->
                List.of(
                        new Route("GET", "/actuator/health", PUBLIC, true),
                        new Route("GET", "/actuator/info", PUBLIC, true),
                        new Route("GET", "/actuator/metrics", PUBLIC, true),
                        new Route("GET", "/actuator/metrics/{requiredMetricName}", PUBLIC, true));
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    OrbitMetricsFilter orbitMetricsFilter(
            ObjectProvider<MeterRegistry> meterRegistryProvider, JdbcTemplate jdbc) {
        return new OrbitMetricsFilter(meterRegistryProvider, jdbc);
    }

    @Bean
    OutboxEventHandler orbitObservabilityOutboxHandler(
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new OrbitObservabilityOutboxHandler(meterRegistryProvider);
    }

    @Bean
    OrbitTelemetryCollector orbitTelemetryCollector(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<StringRedisTemplate> redisProvider,
            JdbcTemplate jdbc) {
        return new OrbitTelemetryCollector(meterRegistryProvider, redisProvider, jdbc);
    }

    /**
     * Records HTTP-boundary latencies and failure counts without logging credentials, query
     * strings, headers, or request/response bodies.
     */
    public static final class OrbitMetricsFilter extends OncePerRequestFilter {
        private static final Pattern CHAT_MESSAGES_PATH =
                Pattern.compile("^/api/v1/spaces/[^/]+/chat/messages$");
        private static final Pattern CHAT_SYNC_PATH =
                Pattern.compile("^/api/v1/spaces/[^/]+/chat/(events|messages)$");
        private static final Pattern JOB_ACTION_PATH =
                Pattern.compile("^/internal/v1/jobs/([^/]+)/(claim|result|fail)$");
        private static final Pattern UPLOAD_OR_WIP_PATH =
                Pattern.compile("^/api/v1/(?:files/uploads|spaces/[^/]+/(?:files/uploads|projects/[^/]+/(?:wip|backfill)))");

        private static final int MAX_TRACKED_JOBS = 2048;

        private final ObjectProvider<MeterRegistry> meterRegistryProvider;
        private final JdbcTemplate jdbc;
        private final ConcurrentHashMap<String, Instant> jobClaimTimes = new ConcurrentHashMap<>();

        OrbitMetricsFilter(
                ObjectProvider<MeterRegistry> meterRegistryProvider, JdbcTemplate jdbc) {
            this.meterRegistryProvider = Objects.requireNonNull(meterRegistryProvider);
            this.jdbc = Objects.requireNonNull(jdbc);
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                Timer.builder("orbit.chat.message.persist.duration").register(registry);
                Timer.builder("orbit.document.persist.ack.duration").register(registry);
                Timer.builder("orbit.async.jobs.queue.wait.duration").register(registry);
                Timer.builder("orbit.async.jobs.processing.duration").register(registry);
                Counter.builder("orbit.chat.sync.failures").register(registry);
                Counter.builder("orbit.upload.failures").register(registry);
                Counter.builder("orbit.auth.failures").register(registry);
                Counter.builder("orbit.quota.rejections").register(registry);
                Counter.builder("orbit.egress.blocked").register(registry);
            }
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            long startedNanos = System.nanoTime();
            try {
                chain.doFilter(request, response);
            } finally {
                recordHttpOutcome(request, response, System.nanoTime() - startedNanos);
            }
        }

        private void recordHttpOutcome(
                HttpServletRequest request, HttpServletResponse response, long durationNanos) {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry == null) {
                return;
            }
            String method = request.getMethod();
            String path = request.getRequestURI();
            int status = response.getStatus();

            if ("POST".equals(method) && CHAT_MESSAGES_PATH.matcher(path).matches()) {
                Timer.builder("orbit.chat.message.persist.duration")
                        .register(registry)
                        .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
            } else if ("GET".equals(method)
                    && status >= 400
                    && CHAT_SYNC_PATH.matcher(path).matches()) {
                Counter.builder("orbit.chat.sync.failures").register(registry).increment();
            } else if ("POST".equals(method) && "/internal/v1/collab/store-batch".equals(path)) {
                Timer.builder("orbit.document.persist.ack.duration")
                        .register(registry)
                        .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
            } else if ("POST".equals(method)) {
                Matcher jobMatcher = JOB_ACTION_PATH.matcher(path);
                if (jobMatcher.matches() && status >= 200 && status < 300) {
                    recordWorkerJobTiming(registry, jobMatcher.group(1), jobMatcher.group(2));
                }
            }

            if (status >= 400
                    && ("POST".equals(method) || "PUT".equals(method))
                    && UPLOAD_OR_WIP_PATH.matcher(path).find()) {
                Counter.builder("orbit.upload.failures").register(registry).increment();
            }
            if (status == 401 || status == 403) {
                Counter.builder("orbit.auth.failures").register(registry).increment();
            }
        }

        private void recordWorkerJobTiming(MeterRegistry registry, String jobId, String action) {
            Instant now = Instant.now();
            if ("claim".equals(action)) {
                if (jobClaimTimes.size() >= MAX_TRACKED_JOBS) {
                    jobClaimTimes.clear();
                }
                jobClaimTimes.put(jobId, now);
                try {
                    List<Timestamp> created = jdbc.queryForList(
                            "SELECT created_at FROM async_jobs WHERE job_id = ?",
                            Timestamp.class,
                            jobId);
                    if (!created.isEmpty() && created.getFirst() != null) {
                        Duration wait = Duration.between(created.getFirst().toInstant(), now);
                        Timer.builder("orbit.async.jobs.queue.wait.duration")
                                .register(registry)
                                .record(Math.max(0L, wait.toNanos()), TimeUnit.NANOSECONDS);
                    }
                } catch (RuntimeException ignored) {
                    // Best-effort telemetry must never disrupt request completion.
                }
            } else if ("result".equals(action) || "fail".equals(action)) {
                Instant claimedAt = jobClaimTimes.remove(jobId);
                if (claimedAt != null) {
                    Duration elapsed = Duration.between(claimedAt, now);
                    Timer.builder("orbit.async.jobs.processing.duration")
                            .register(registry)
                            .record(Math.max(0L, elapsed.toNanos()), TimeUnit.NANOSECONDS);
                }
            }
        }
    }

    /**
     * Observes outbox delivery latency for chat broadcasts, revocation propagation, and async job
     * dispatch without storing or logging message bodies or sensitive payloads.
     */
    public static final class OrbitObservabilityOutboxHandler implements OutboxEventHandler {
        private static final Logger log =
                LoggerFactory.getLogger(OrbitObservabilityOutboxHandler.class);
        private final ObjectProvider<MeterRegistry> meterRegistryProvider;

        OrbitObservabilityOutboxHandler(ObjectProvider<MeterRegistry> meterRegistryProvider) {
            this.meterRegistryProvider = Objects.requireNonNull(meterRegistryProvider);
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                Timer.builder("orbit.chat.message.delivery.duration").register(registry);
                Timer.builder("orbit.revocation.propagation.duration").register(registry);
                Timer.builder("orbit.outbox.job.dispatch.duration").register(registry);
                Counter.builder("orbit.revocation.events.total").register(registry);
            }
        }

        @Override
        public String eventTypePrefix() {
            return "";
        }

        @Override
        public void handle(OutboxPublisherPort.Event event) {
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry == null || event == null || event.occurredAt() == null) {
                return;
            }
            long latencyNanos =
                    Math.max(0L, Duration.between(event.occurredAt(), Instant.now()).toNanos());
            switch (event.type()) {
                case "CHAT_MESSAGE_CREATED" ->
                        Timer.builder("orbit.chat.message.delivery.duration")
                                .register(registry)
                                .record(latencyNanos, TimeUnit.NANOSECONDS);
                case "MEMBERSHIP_REVOKED" -> {
                    Timer.builder("orbit.revocation.propagation.duration")
                            .register(registry)
                            .record(latencyNanos, TimeUnit.NANOSECONDS);
                    Counter.builder("orbit.revocation.events.total").register(registry).increment();
                    log.info(
                            "Revocation outbox propagation completed (eventId={}, latencyMs={})",
                            event.eventId(),
                            TimeUnit.NANOSECONDS.toMillis(latencyNanos));
                }
                case "PLATFORM_JOB_READY" ->
                        Timer.builder("orbit.outbox.job.dispatch.duration")
                                .register(registry)
                                .record(latencyNanos, TimeUnit.NANOSECONDS);
                default -> {}
            }
        }
    }

    /**
     * Periodically refreshes aggregate database and Redis gauges and logs non-sensitive runtime
     * health summaries.
     */
    public static final class OrbitTelemetryCollector {
        private static final Logger log = LoggerFactory.getLogger(OrbitTelemetryCollector.class);
        private static final String JOB_STREAM = "orbit:async:jobs";

        private final ObjectProvider<StringRedisTemplate> redisProvider;
        private final JdbcTemplate jdbc;

        private final AtomicLong outboxBacklogCount = new AtomicLong();
        private final AtomicReference<Double> outboxOldestAgeSeconds = new AtomicReference<>(0.0);
        private final AtomicLong jobsPendingCount = new AtomicLong();
        private final AtomicLong jobsRunningCount = new AtomicLong();
        private final AtomicLong jobsRetryWaitCount = new AtomicLong();
        private final AtomicLong jobsFailedCount = new AtomicLong();
        private final AtomicReference<Double> jobsQueueOldestWaitSeconds =
                new AtomicReference<>(0.0);
        private final AtomicLong asyncStreamLength = new AtomicLong();
        private final AtomicLong immutableObjectCount = new AtomicLong();
        private final AtomicLong immutableObjectBytes = new AtomicLong();
        private final AtomicLong uncompactedDocumentCount = new AtomicLong();
        private final AtomicLong totalDocumentBatches = new AtomicLong();
        private final AtomicLong activeCollabSessions = new AtomicLong();
        private final AtomicLong revocationLedgerSequence = new AtomicLong();

        OrbitTelemetryCollector(
                ObjectProvider<MeterRegistry> meterRegistryProvider,
                ObjectProvider<StringRedisTemplate> redisProvider,
                JdbcTemplate jdbc) {
            this.redisProvider = Objects.requireNonNull(redisProvider);
            this.jdbc = Objects.requireNonNull(jdbc);
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry != null) {
                Gauge.builder("orbit.outbox.backlog.count", outboxBacklogCount, AtomicLong::get)
                        .register(registry);
                Gauge.builder(
                                "orbit.outbox.oldest.age.seconds",
                                outboxOldestAgeSeconds,
                                AtomicReference::get)
                        .register(registry);
                Gauge.builder("orbit.async.jobs.pending.count", jobsPendingCount, AtomicLong::get)
                        .register(registry);
                Gauge.builder("orbit.async.jobs.running.count", jobsRunningCount, AtomicLong::get)
                        .register(registry);
                Gauge.builder(
                                "orbit.async.jobs.retry_wait.count",
                                jobsRetryWaitCount,
                                AtomicLong::get)
                        .register(registry);
                Gauge.builder("orbit.async.jobs.failed.count", jobsFailedCount, AtomicLong::get)
                        .register(registry);
                Gauge.builder(
                                "orbit.async.jobs.queue.oldest.wait.seconds",
                                jobsQueueOldestWaitSeconds,
                                AtomicReference::get)
                        .register(registry);
                Gauge.builder("orbit.async.stream.length", asyncStreamLength, AtomicLong::get)
                        .register(registry);
                Gauge.builder(
                                "orbit.storage.objects.immutable.count",
                                immutableObjectCount,
                                AtomicLong::get)
                        .register(registry);
                Gauge.builder(
                                "orbit.storage.objects.immutable.bytes",
                                immutableObjectBytes,
                                AtomicLong::get)
                        .register(registry);
                Gauge.builder(
                                "orbit.document.uncompacted.count",
                                uncompactedDocumentCount,
                                AtomicLong::get)
                        .register(registry);
                Gauge.builder(
                                "orbit.document.batches.total",
                                totalDocumentBatches,
                                AtomicLong::get)
                        .register(registry);
                Gauge.builder(
                                "orbit.collab.sessions.active.count",
                                activeCollabSessions,
                                AtomicLong::get)
                        .register(registry);
                Gauge.builder(
                                "orbit.revocation.ledger.applied.sequence",
                                revocationLedgerSequence,
                                AtomicLong::get)
                        .register(registry);
            }
        }

        @Scheduled(
                initialDelayString = "${orbit.observability.initial-delay:PT5S}",
                fixedDelayString = "${orbit.observability.poll-interval:PT15S}")
        public void refreshMetrics() {
            try {
                Map<String, Object> outboxStats = jdbc.queryForMap(
                        """
                        SELECT COUNT(*) AS cnt,
                               COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6)) / 1000000.0, 0.0) AS oldest_sec
                        FROM outbox_events
                        WHERE status = 'PENDING'
                        """);
                outboxBacklogCount.set(asLong(outboxStats.get("cnt")));
                outboxOldestAgeSeconds.set(Math.max(0.0, asDouble(outboxStats.get("oldest_sec"))));

                long pending = 0, running = 0, retryWait = 0, failed = 0;
                List<Map<String, Object>> jobRows = jdbc.queryForList(
                        "SELECT status, COUNT(*) AS cnt FROM async_jobs GROUP BY status");
                for (Map<String, Object> row : jobRows) {
                    String st = String.valueOf(row.get("status"));
                    long cnt = asLong(row.get("cnt"));
                    switch (st) {
                        case "PENDING" -> pending = cnt;
                        case "RUNNING" -> running = cnt;
                        case "RETRY_WAIT" -> retryWait = cnt;
                        case "FAILED" -> failed = cnt;
                        default -> {}
                    }
                }
                jobsPendingCount.set(pending);
                jobsRunningCount.set(running);
                jobsRetryWaitCount.set(retryWait);
                jobsFailedCount.set(failed);

                Double oldestJobWait = jdbc.queryForObject(
                        """
                        SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6)) / 1000000.0, 0.0)
                        FROM async_jobs
                        WHERE status IN ('PENDING', 'RETRY_WAIT')
                        """,
                        Double.class);
                jobsQueueOldestWaitSeconds.set(
                        oldestJobWait != null ? Math.max(0.0, oldestJobWait) : 0.0);

                Map<String, Object> storageStats = jdbc.queryForMap(
                        """
                        SELECT COUNT(*) AS cnt, COALESCE(SUM(size_bytes), 0) AS total_bytes
                        FROM storage_objects
                        WHERE status = 'IMMUTABLE'
                        """);
                immutableObjectCount.set(asLong(storageStats.get("cnt")));
                immutableObjectBytes.set(asLong(storageStats.get("total_bytes")));

                Long uncompacted = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM documents WHERE lifecycle_state = 'ACTIVE' AND persisted_batch_seq > snapshot_seq",
                        Long.class);
                uncompactedDocumentCount.set(uncompacted != null ? uncompacted : 0L);

                Long batches = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_update_batches", Long.class);
                totalDocumentBatches.set(batches != null ? batches : 0L);

                Long collabSessions = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_sessions WHERE consumed = TRUE AND revoked = FALSE",
                        Long.class);
                activeCollabSessions.set(collabSessions != null ? collabSessions : 0L);

                Long ledgerSeq = jdbc.queryForObject(
                        "SELECT applied_sequence FROM revocation_ledger_state WHERE id = 1",
                        Long.class);
                revocationLedgerSequence.set(ledgerSeq != null ? ledgerSeq : 0L);

                StringRedisTemplate redis = redisProvider.getIfAvailable();
                if (redis != null) {
                    Long streamLen = redis.opsForStream().size(JOB_STREAM);
                    asyncStreamLength.set(streamLen != null ? streamLen : 0L);
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "Telemetry gauge refresh deferred (cause={})",
                        ex.getClass().getSimpleName());
            }
        }

        @Scheduled(
                initialDelayString = "${orbit.observability.log-initial-delay:PT60S}",
                fixedDelayString = "${orbit.observability.log-interval:PT5M}")
        public void logTelemetrySummary() {
            log.info(
                    "Orbit telemetry summary (outboxPending={}, outboxOldestSec={}, jobsPending={}, jobsRunning={}, jobsRetryWait={}, jobsFailed={}, jobOldestWaitSec={}, streamLen={}, immutableObjects={}, immutableBytes={}, uncompactedDocs={}, docBatches={}, activeCollabSessions={}, revocationSeq={})",
                    outboxBacklogCount.get(),
                    String.format(java.util.Locale.ROOT, "%.3f", outboxOldestAgeSeconds.get()),
                    jobsPendingCount.get(),
                    jobsRunningCount.get(),
                    jobsRetryWaitCount.get(),
                    jobsFailedCount.get(),
                    String.format(java.util.Locale.ROOT, "%.3f", jobsQueueOldestWaitSeconds.get()),
                    asyncStreamLength.get(),
                    immutableObjectCount.get(),
                    immutableObjectBytes.get(),
                    uncompactedDocumentCount.get(),
                    totalDocumentBatches.get(),
                    activeCollabSessions.get(),
                    revocationLedgerSequence.get());
        }

        private static long asLong(Object value) {
            return value instanceof Number n ? n.longValue() : 0L;
        }

        private static double asDouble(Object value) {
            return value instanceof Number n ? n.doubleValue() : 0.0;
        }
    }
}
