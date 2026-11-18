package com.orbitspace.kernel;

import java.time.Duration;

public interface RateLimitPort {
    record Decision(boolean allowed, Duration retryAfter) {}

    /** Atomically count this acquisition in the caller's sliding window. */
    Decision tryAcquire(String key, int limit, Duration window);
}
