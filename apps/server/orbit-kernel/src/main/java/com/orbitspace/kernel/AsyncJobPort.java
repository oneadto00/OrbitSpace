package com.orbitspace.kernel;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface AsyncJobPort {
    record Job(String jobId, String type, String spaceId, String resourceId, String actorId,
               String inputObjectKey, long inputVersion, long resourceLifecycleVersion) {}
    record Attempt(Job job, String leaseId, Instant leaseUntil) {}
    record Result(String jobId, String leaseId, AsyncJobStatus expectedJobStatus, long inputVersion,
                  long resourceLifecycleVersion, String resultObjectKey) {}
    record ResourceState(long lifecycleVersion, boolean processingAuthorized) {}
    enum Acceptance { ACCEPTED, ALREADY_ACCEPTED, STALE }

    /** Both calls participate in the business transaction. A duplicate submission must match its input. */
    void enqueue(Job job);
    void cancel(String jobId);

    /** Claim a due job or reclaim an expired lease with a new fencing token. */
    Optional<Attempt> claim(String jobId, Duration leaseDuration);

    /** Both failure paths must match the current RUNNING attempt and lease. */
    boolean retryLater(Attempt attempt, Instant nextAttemptAt, String failureCode);
    boolean fail(Attempt attempt, String failureCode);

    interface ResultTarget {
        /** Lock the current resource and processing grant for the duration of the acceptance transaction. */
        ResourceState lockAndCheck(Job job);
        void attachResult(Job job, String resultObjectKey);
    }

    /**
     * Within one transaction lock the persisted job, require an active RUNNING lease, expected status
     * and input version, then compare the live resource version and authorization from lockAndCheck.
     * Only then attach the result and mark SUCCEEDED. Identical accepted redeliveries do not republish.
     * Unreferenced rejected objects remain eligible for grace-period collection.
     */
    Acceptance acceptResult(Result result, ResultTarget target);
}
