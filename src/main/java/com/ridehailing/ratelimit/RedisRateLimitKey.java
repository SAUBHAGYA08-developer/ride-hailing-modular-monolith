package com.ridehailing.ratelimit;

import com.ridehailing.redis.RedisKeys;

final class RedisRateLimitKey {

    private RedisRateLimitKey() {
    }

    static String of(RateLimitPolicy policy, String subject) {
        return RedisKeys.rateLimit(policy.name(), subject);
    }
}
