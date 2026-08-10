package com.ridehailing.ratelimit;

import com.ridehailing.common.exception.RateLimitExceededException;
import com.ridehailing.common.web.RequestContext;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Resolves the DB configured limit for a policy and enforces it. */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimiter rateLimiter;
    private final ConfigurationService configurationService;

    public void enforce(RateLimitPolicy policy) {
        int limit = configurationService.getInt(policy.maxConfigKey(), policy.defaultMax());
        int windowSeconds = configurationService.getInt(policy.windowConfigKey(), policy.defaultWindowSeconds());

        RateLimitResult result = rateLimiter.tryAcquire(
                RedisRateLimitKey.of(policy, resolveSubject(policy)), limit, Duration.ofSeconds(windowSeconds));

        if (!result.allowed()) {
            throw new RateLimitExceededException(
                    "Rate limit exceeded: at most " + limit + " requests per " + windowSeconds + " seconds",
                    result.retryAfterSeconds());
        }
    }

    private String resolveSubject(RateLimitPolicy policy) {
        if (policy.subject() == RateLimitPolicy.Subject.IP) {
            String ip = RequestContext.clientIp();
            return ip == null ? "unknown" : ip;
        }
        return CurrentUser.current()
                .map(principal -> String.valueOf(principal.userId()))
                .orElseGet(() -> {
                    String ip = RequestContext.clientIp();
                    return "anonymous:" + (ip == null ? "unknown" : ip);
                });
    }
}
