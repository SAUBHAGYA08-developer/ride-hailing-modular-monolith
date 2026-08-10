package com.ridehailing.configuration;

/** Canonical names of every DB driven business setting. */
public final class ConfigKeys {

    public static final String RIDE_SEARCH_RADIUS_KM = "ride.search.radius.km";
    public static final String MATCHING_STRATEGY = "matching.strategy";
    public static final String PRICING_ACTIVE_RULE = "pricing.active.rule";
    public static final String SURGE_ENABLED = "surge.enabled";
    public static final String DRIVER_LOCATION_TTL_SECONDS = "driver.location.ttl.seconds";

    public static final String RATE_LIMIT_RIDE_MAX = "api.rate-limit.ride.max";
    public static final String RATE_LIMIT_RIDE_WINDOW_SECONDS = "api.rate-limit.ride.window.seconds";
    public static final String RATE_LIMIT_LOCATION_MAX = "api.rate-limit.location.max";
    public static final String RATE_LIMIT_LOCATION_WINDOW_SECONDS = "api.rate-limit.location.window.seconds";
    public static final String RATE_LIMIT_LOGIN_MAX = "api.rate-limit.login.max";
    public static final String RATE_LIMIT_LOGIN_WINDOW_SECONDS = "api.rate-limit.login.window.seconds";
    public static final String RATE_LIMIT_COUPON_MAX = "api.rate-limit.coupon.max";
    public static final String RATE_LIMIT_COUPON_WINDOW_SECONDS = "api.rate-limit.coupon.window.seconds";
    public static final String RATE_LIMIT_ADMIN_MAX = "api.rate-limit.admin.max";
    public static final String RATE_LIMIT_ADMIN_WINDOW_SECONDS = "api.rate-limit.admin.window.seconds";

    public static final String IDEMPOTENCY_TTL_SECONDS = "idempotency.ttl.seconds";
    public static final String RIDE_CANCELLATION_ALLOWED_STATUSES = "ride.cancellation.allowed.statuses";
    public static final String BOOKING_LOCK_TTL_SECONDS = "ride.booking.lock.ttl.seconds";

    private ConfigKeys() {
    }
}
