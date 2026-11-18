package com.orbitspace.platform;

import com.orbitspace.kernel.AsyncJobPort;
import com.orbitspace.kernel.AsyncJobStatus;
import com.orbitspace.kernel.DomainException;
import com.orbitspace.kernel.KernelErrorCode;
import com.orbitspace.kernel.OutboxPublisherPort;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class JdbcJobs implements AsyncJobPort {
    private final JdbcTemplate db;
    private final OutboxPublisherPort outbox;
    private static final int MAX_ATTEMPTS = 8;

    public JdbcJobs(JdbcTemplate db, OutboxPublisherPort outbox) {
        this.db = db;
        this.outbox = outbox;
    }

    private record Row(
            Job job,
            String status,
            String lease,
            boolean liveLease,
            Instant leaseUntil,
            String resultKey,
            int attempts) {}

    private Row map(ResultSet rs, int i) throws SQLException {
        Job job =
                new Job(
                        rs.getString("job_id"),
                        rs.getString("job_type"),
                        rs.getString("space_id"),
                        rs.getString("resource_id"),
                        rs.getString("actor_id"),
                        rs.getString("input_object_key"),
                        rs.getLong("input_version"),
                        rs.getLong("expected_resource_lifecycle_version"));
        var until = rs.getTimestamp("lease_until");
        return new Row(
                job,
                rs.getString("status"),
                rs.getString("lease_holder"),
                rs.getBoolean("live_lease"),
                until == null ? null : until.toInstant(),
                rs.getString("result_object_key"),
                rs.getInt("retries"));
    }

    private Optional<Row> lock(String id) {
        return db
                .query(
                        """
                        SELECT *,lease_until>CURRENT_TIMESTAMP(6) AS live_lease
                        FROM async_jobs WHERE job_id=? FOR UPDATE
                        """,
                        this::map,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Job job) {
        // The kernel has a single type field. It identifies the processor/resource family in P1.
        db.update(
                """
                INSERT INTO async_jobs(job_id,job_type,resource_type,space_id,resource_id,actor_id,
                    input_object_key,input_version,processor_version,expected_resource_lifecycle_version)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE job_id=job_id
                """,
                job.jobId(),
                job.type(),
                job.type(),
                job.spaceId(),
                job.resourceId(),
                job.actorId(),
                job.inputObjectKey(),
                job.inputVersion(),
                "1",
                job.resourceLifecycleVersion());
        Row stored = lock(job.jobId()).orElseThrow();
        if (!stored.job.equals(job))
            throw new DomainException(KernelErrorCode.IDEMPOTENCY_CONFLICT);
        // Pin immutable input in the job transaction until the task reaches a terminal state.
        if (stored.status.equals("PENDING")) {
            pin(job, "ASYNC_INPUT", job.inputObjectKey());
            Instant created =
                    db.queryForObject(
                                    "SELECT created_at FROM async_jobs WHERE job_id=?",
                                    java.sql.Timestamp.class,
                                    job.jobId())
                            .toInstant();
            String eventId = "job-" + PlatformValues.digest(job.jobId());
            outbox.publish(
                    new OutboxPublisherPort.Event(
                            eventId,
                            OutboxRelay.JOB_READY,
                            "ASYNC_JOB",
                            job.jobId(),
                            "{}",
                            created));
        }
    }

    private void pin(Job job, String owner, String key) {
        // Job lock serializes retries. Never use INSERT IGNORE with reference-count triggers.
        boolean exists =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                """
                                SELECT EXISTS(SELECT 1 FROM storage_object_references WHERE owner_type=? AND owner_id=? AND object_key=?)
                                """,
                                Boolean.class,
                                owner,
                                job.jobId(),
                                key));
        if (!exists)
            db.update(
                    "INSERT INTO storage_object_references(owner_type,owner_id,object_key) VALUES"
                        + " (?,?,?)",
                    owner,
                    job.jobId(),
                    key);
    }

    private void unpinInput(String jobId) {
        db.update(
                "DELETE FROM storage_object_references WHERE owner_type='ASYNC_INPUT' AND"
                    + " owner_id=?",
                jobId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(String jobId) {
        if (db.update(
                        """
                        UPDATE async_jobs SET status='CANCELLED',lease_holder=NULL,lease_until=NULL
                        WHERE job_id=? AND status IN ('PENDING','RUNNING','RETRY_WAIT')
                        """,
                        jobId)
                != 0) unpinInput(jobId);
    }

    @Override
    @Transactional
    public Optional<Attempt> claim(String jobId, Duration leaseDuration) {
        long micros = leaseDuration.toNanos() / 1000;
        if (micros <= 0) throw new IllegalArgumentException("Invalid lease duration");
        String lease = UUID.randomUUID().toString();
        int claimed =
                db.update(
                        """
                        UPDATE async_jobs SET status='RUNNING',lease_holder=?,
                            lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)),retries=retries+1
                        WHERE job_id=? AND ((status IN ('PENDING','RETRY_WAIT') AND next_retry_at<=CURRENT_TIMESTAMP(6))
                            OR (status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)))
                        """,
                        lease,
                        micros,
                        jobId);
        if (claimed == 0) return Optional.empty();
        Row row = lock(jobId).orElseThrow();
        if (row.attempts > MAX_ATTEMPTS) {
            db.update(
                    "UPDATE async_jobs SET"
                        + " status='FAILED',error_summary='RETRIES_EXHAUSTED',lease_holder=NULL,lease_until=NULL"
                        + " WHERE job_id=?",
                    jobId);
            unpinInput(jobId);
            return Optional.empty();
        }
        return Optional.of(new Attempt(row.job, lease, row.leaseUntil));
    }

    @Override
    @Transactional
    public boolean retryLater(Attempt attempt, Instant nextAttemptAt, String failureCode) {
        return endAttempt(attempt, true, nextAttemptAt, failureCode);
    }

    @Override
    @Transactional
    public boolean fail(Attempt attempt, String failureCode) {
        return endAttempt(attempt, false, Instant.now(), failureCode);
    }

    private boolean endAttempt(Attempt attempt, boolean retry, Instant next, String code) {
        String id = attempt.job().jobId();
        Optional<Row> locked = lock(id);
        if (locked.isEmpty()) return false;
        Row row = locked.get();
        if (!row.status.equals("RUNNING")
                || !row.liveLease
                || !Objects.equals(row.lease, attempt.leaseId())) return false;
        boolean retryable = retry && row.attempts < MAX_ATTEMPTS;
        String summary = code != null && code.matches("[A-Z_]{1,48}") ? code : "PROCESSOR_FAILURE";
        db.update(
                """
                UPDATE async_jobs SET status=?,lease_holder=NULL,lease_until=NULL,error_summary=?,next_retry_at=?
                WHERE job_id=?
                """,
                retryable ? "RETRY_WAIT" : "FAILED",
                summary,
                PlatformValues.time(next),
                id);
        if (!retryable) unpinInput(id);
        return true;
    }

    @Override
    @Transactional
    public Acceptance acceptResult(Result result, ResultTarget target) {
        Optional<Row> locked = lock(result.jobId());
        if (locked.isEmpty()) return Acceptance.STALE;
        Row row = locked.get();
        Job job = row.job;
        boolean sameAttempt =
                Objects.equals(row.lease, result.leaseId())
                        && job.inputVersion() == result.inputVersion()
                        && job.resourceLifecycleVersion() == result.resourceLifecycleVersion()
                        && result.expectedJobStatus() == AsyncJobStatus.RUNNING;
        if (sameAttempt
                && row.status.equals("SUCCEEDED")
                && Objects.equals(row.resultKey, result.resultObjectKey()))
            return Acceptance.ALREADY_ACCEPTED;
        // Rejected immutable objects retain their initial unreferenced_since and grace period.
        if (!sameAttempt || !row.status.equals("RUNNING") || !row.liveLease)
            return Acceptance.STALE;
        ResourceState live = target.lockAndCheck(job);
        if (live == null
                || !live.processingAuthorized()
                || live.lifecycleVersion() != job.resourceLifecycleVersion()) {
            cancel(job.jobId());
            return Acceptance.STALE;
        }
        boolean immutable =
                !db.queryForList(
                                """
                                SELECT object_key FROM storage_objects WHERE object_key=? AND status='IMMUTABLE' FOR UPDATE
                                """,
                                String.class,
                                result.resultObjectKey())
                        .isEmpty();
        if (!immutable) return Acceptance.STALE;
        // Recheck DB time after the resource lock (the lease might have expired while waiting).
        int won =
                db.update(
                        """
                        UPDATE async_jobs SET status='SUCCEEDED',result_object_key=?,error_summary=NULL
                        WHERE job_id=? AND lease_until>CURRENT_TIMESTAMP(6) AND status='RUNNING' AND lease_holder=?
                        """,
                        result.resultObjectKey(),
                        job.jobId(),
                        result.leaseId());
        if (won == 0) return Acceptance.STALE;
        target.attachResult(job, result.resultObjectKey());
        unpinInput(job.jobId());
        return Acceptance.ACCEPTED;
    }

    @Transactional
    public Optional<Attempt> claimForProcessors(
            String id, java.util.Set<String> types, Duration duration) {
        Optional<Row> row = lock(id);
        if (row.isEmpty() || !types.contains(row.get().job.type())) return Optional.empty();
        return claim(id, duration);
    }

    List<String> due(String jobType) {
        return db.queryForList(
                """
                SELECT job_id FROM async_jobs WHERE job_type=? AND (
                    (status IN ('PENDING','RETRY_WAIT') AND next_retry_at<=CURRENT_TIMESTAMP(6))
                    OR (status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)))
                ORDER BY next_retry_at,job_id LIMIT 32
                """,
                String.class,
                jobType);
    }
}
