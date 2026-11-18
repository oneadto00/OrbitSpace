package com.orbitspace.platform;

import com.orbitspace.kernel.AsyncJobPort.Attempt;
import com.orbitspace.kernel.AsyncJobPort.Result;
import com.orbitspace.kernel.AsyncJobProcessor;
import com.orbitspace.kernel.AsyncJobStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

public class JobRunner implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private final ObjectProvider<AsyncJobProcessor> processors;
    private final JdbcJobStore jobs;
    private final StringRedisTemplate redis;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(4);
    private String cursor = "0-0";

    public JobRunner(
            ObjectProvider<AsyncJobProcessor> processors,
            JdbcJobStore jobs,
            StringRedisTemplate redis) {
        this.processors = processors;
        this.jobs = jobs;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${orbit.platform.worker-interval:PT2S}")
    public void poll() {
        Map<String, AsyncJobProcessor> available =
                processors
                        .orderedStream()
                        .collect(Collectors.toMap(AsyncJobProcessor::jobType, p -> p));
        if (available.isEmpty()) return;
        try {
            var batch =
                    redis.opsForStream()
                            .read(
                                    StreamReadOptions.empty().count(64),
                                    StreamOffset.create(
                                            OutboxRelay.JOB_STREAM, ReadOffset.from(cursor)));
            if (batch != null)
                for (var record : batch) {
                    if (capacity.availablePermits() == 0) break;
                    String jobId = (String) record.getValue().get("jobId");
                    if (jobId != null) submit(jobId, available);
                    cursor = record.getId().getValue();
                }
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn(
                    "Stream unavailable; checking durable jobs ({})", e.getClass().getSimpleName());
        }
        // SQL is authoritative across stream truncation, cursor loss, timed retries and expired
        // leases.
        for (String type : available.keySet()) {
            if (capacity.availablePermits() == 0) break;
            for (String id : jobs.due(type)) if (!submit(id, available)) break;
        }
    }

    private boolean submit(String id, Map<String, AsyncJobProcessor> available) {
        if (!capacity.tryAcquire()) return false;
        try {
            workers.execute(
                    () -> {
                        try {
                            jobs.claimForProcessors(id, available.keySet(), Duration.ofMinutes(5))
                                    .ifPresent(a -> run(a, available.get(a.job().type())));
                        } catch (RuntimeException e) {
                            log.warn(
                                    "Job attempt deferred (jobId={}, cause={})",
                                    id,
                                    e.getClass().getSimpleName());
                        } finally {
                            capacity.release();
                        }
                    });
            return true;
        } catch (RuntimeException e) {
            capacity.release();
            throw e;
        }
    }

    private void run(Attempt attempt, AsyncJobProcessor processor) {
        try {
            String object = processor.process(attempt);
            var job = attempt.job();
            jobs.acceptResult(
                    new Result(
                            job.jobId(),
                            attempt.leaseId(),
                            AsyncJobStatus.RUNNING,
                            job.inputVersion(),
                            job.resourceLifecycleVersion(),
                            object),
                    processor);
        } catch (Exception e) {
            jobs.retryLater(attempt, Instant.now().plusSeconds(30), "PROCESSOR_FAILURE");
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
