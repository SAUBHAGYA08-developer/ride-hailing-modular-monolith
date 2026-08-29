package com.ridehailing.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Short lived coordination lock. It reduces contention; it is never the thing
 * that makes the system correct. Correctness always comes from MySQL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    /** Returns the lock token when acquired, empty when the lock is held elsewhere. */
    public Optional<String> acquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    /** Releases only if this caller still owns the lock. */
    public void release(String key, String token) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key), token);
        } catch (RuntimeException ex) {
            log.warn("Failed to release lock {} - it will expire by TTL", key, ex);
        }
    }
}
