package com.ridehailing.ratelimit;

import com.ridehailing.configuration.ConfigKeys;

/**
 * Every limited endpoint class. Limits are DB driven; the constants here are
 * only the fallback used when configuration cannot be read.
 */
public enum RateLimitPolicy {

    RIDE_CREATE(ConfigKeys.RATE_LIMIT_RIDE_MAX, ConfigKeys.RATE_LIMIT_RIDE_WINDOW_SECONDS, 10, 60, Subject.PRINCIPAL),
    DRIVER_LOCATION(ConfigKeys.RATE_LIMIT_LOCATION_MAX, ConfigKeys.RATE_LIMIT_LOCATION_WINDOW_SECONDS, 60, 60, Subject.PRINCIPAL),
    LOGIN(ConfigKeys.RATE_LIMIT_LOGIN_MAX, ConfigKeys.RATE_LIMIT_LOGIN_WINDOW_SECONDS, 5, 60, Subject.IP),
    COUPON_VALIDATE(ConfigKeys.RATE_LIMIT_COUPON_MAX, ConfigKeys.RATE_LIMIT_COUPON_WINDOW_SECONDS, 20, 60, Subject.PRINCIPAL),
    ADMIN_API(ConfigKeys.RATE_LIMIT_ADMIN_MAX, ConfigKeys.RATE_LIMIT_ADMIN_WINDOW_SECONDS, 30, 60, Subject.PRINCIPAL);

    public enum Subject {
        /** Limit applies per authenticated identity. */
        PRINCIPAL,
        /** Limit applies per client IP (used before authentication exists). */
        IP
    }

    private final String maxConfigKey;
    private final String windowConfigKey;
    private final int defaultMax;
    private final int defaultWindowSeconds;
    private final Subject subject;

    RateLimitPolicy(String maxConfigKey, String windowConfigKey, int defaultMax, int defaultWindowSeconds, Subject subject) {
        this.maxConfigKey = maxConfigKey;
        this.windowConfigKey = windowConfigKey;
        this.defaultMax = defaultMax;
        this.defaultWindowSeconds = defaultWindowSeconds;
        this.subject = subject;
    }

    public String maxConfigKey() {
        return maxConfigKey;
    }

    public String windowConfigKey() {
        return windowConfigKey;
    }

    public int defaultMax() {
        return defaultMax;
    }

    public int defaultWindowSeconds() {
        return defaultWindowSeconds;
    }

    public Subject subject() {
        return subject;
    }
}
