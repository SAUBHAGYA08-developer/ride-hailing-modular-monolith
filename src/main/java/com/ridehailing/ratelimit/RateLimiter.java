package com.ridehailing.ratelimit;

import java.time.Duration;

/** Counting abstraction. Callers never touch Redis directly. */
public interface RateLimiter {

    RateLimitResult tryAcquire(String key, int limit, Duration window);
}
