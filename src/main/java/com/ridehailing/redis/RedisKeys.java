package com.ridehailing.redis;

/** Every Redis key the application uses is derived here. */
public final class RedisKeys {

    /** GEO set of live driver positions. */
    public static final String DRIVER_GEO_SET = "driver:locations";

    private RedisKeys() {
    }

    /** Presence marker with a TTL: absence means the GEO entry is stale. */
    public static String driverLocationFreshness(long driverId) {
        return "driver:location:fresh:" + driverId;
    }

    public static String configuration(String configKey) {
        return "config:" + configKey;
    }

    public static String rateLimit(String policy, String subject) {
        return "ratelimit:" + policy + ":" + subject;
    }

    public static String bookingLock(long userId) {
        return "lock:booking:user:" + userId;
    }
}
