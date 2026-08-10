package com.ridehailing.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Fixed window counter. INCR and PEXPIRE run in one Lua call so a window can
 * never be created without a TTL, which would leak keys and block a caller
 * permanently.
 *
 * If Redis is unavailable the limiter fails open: rate limiting protects
 * capacity, and refusing all traffic because the protection is down would be a
 * self inflicted outage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return { current, redis.call('PTTL', KEYS[1]) }
            """, List.class);

    private final StringRedisTemplate redis;

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitResult tryAcquire(String key, int limit, Duration window) {
        try {
            List<Long> result = redis.execute(INCREMENT_SCRIPT, List.of(key), String.valueOf(window.toMillis()));
            if (result == null || result.size() < 2) {
                return RateLimitResult.allowed(limit, limit);
            }
            long current = result.get(0);
            long ttlMillis = result.get(1);
            if (current > limit) {
                return RateLimitResult.denied(limit, Math.max(1, (ttlMillis + 999) / 1000));
            }
            return RateLimitResult.allowed(limit, limit - current);
        } catch (RuntimeException ex) {
            log.error("Rate limiter unavailable for key {} - failing open", key, ex);
            return RateLimitResult.allowed(limit, limit);
        }
    }
}
