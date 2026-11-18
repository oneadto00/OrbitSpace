package com.orbitspace.identity;

import com.orbitspace.kernel.*;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class RequestLimits {
    private final RateLimitPort rate;
    RequestLimits(RateLimitPort rate) { this.rate = rate; }

    void apply(String operation, HttpServletRequest req, HttpServletResponse res, int ipLimit, String subject, int subjectLimit) {
        acquire(operation + ":ip:" + Secrets.hash(req.getRemoteAddr()), ipLimit, res);
        if (subject != null) acquire(operation + ":subject:" + Secrets.hash(subject), subjectLimit, res);
    }

    private void acquire(String key, int limit, HttpServletResponse res) {
        var decision = rate.tryAcquire("identity:" + key, limit, Duration.ofMinutes(1));
        if (decision.allowed()) return;
        long seconds = Math.max(1, decision.retryAfter().plusMillis(999).toSeconds());
        res.setHeader("Retry-After", Long.toString(seconds));
        throw new DomainException(KernelErrorCode.RATE_LIMITED);
    }
}
