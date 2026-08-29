package com.ridehailing.ratelimit;

public record RateLimitResult(boolean allowed, int limit, long remaining, long retryAfterSeconds) {

    public static RateLimitResult allowed(int limit, long remaining) {
        return new RateLimitResult(true, limit, Math.max(remaining, 0), 0);
    }

    public static RateLimitResult denied(int limit, long retryAfterSeconds) {
        return new RateLimitResult(false, limit, 0, Math.max(retryAfterSeconds, 1));
    }
}
