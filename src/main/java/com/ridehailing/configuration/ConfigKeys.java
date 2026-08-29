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
    public static final String RATE_LIMIT_QUOTE_MAX = "api.rate-limit.quote.max";
    public static final String RATE_LIMIT_QUOTE_WINDOW_SECONDS = "api.rate-limit.quote.window.seconds";
    public static final String RATE_LIMIT_ADMIN_MAX = "api.rate-limit.admin.max";
    public static final String RATE_LIMIT_ADMIN_WINDOW_SECONDS = "api.rate-limit.admin.window.seconds";

    public static final String IDEMPOTENCY_TTL_SECONDS = "idempotency.ttl.seconds";
    public static final String RIDE_CANCELLATION_ALLOWED_STATUSES = "ride.cancellation.allowed.statuses";
    public static final String CANCELLATION_FEE_AMOUNT = "ride.cancellation.fee.amount";
    public static final String CANCELLATION_FEE_GRACE_SECONDS = "ride.cancellation.fee.grace.seconds";
    public static final String BOOKING_LOCK_TTL_SECONDS = "ride.booking.lock.ttl.seconds";
    public static final String PICKUP_AVERAGE_SPEED_KMPH = "ride.pickup.average.speed.kmph";

    /** Kill switch for the stale ride reaper, so a demo can freeze the world without a redeploy. */
    public static final String RIDE_REAPER_ENABLED = "ride.reaper.enabled";
    public static final String RIDE_STALE_GRACE_SECONDS = "ride.stale.grace.seconds";
    public static final String RIDE_REAPER_BATCH_SIZE = "ride.reaper.batch.size";

    public static final String PAYMENT_SIMULATED_FAILURE_METHODS = "payment.simulated.failure.methods";
    public static final String PAYMENT_CANCELLATION_FEE_METHOD = "payment.cancellation.fee.method";

    /** Followed by the lowercase method name, e.g. "payment.fee.netbanking" - see PaymentFeePolicy. */
    public static final String PAYMENT_FEE_PREFIX = "payment.fee.";

    public static final String RIDE_OFFER_TIMEOUT_SECONDS = "ride.offer.timeout.seconds";
    public static final String RIDE_OFFER_REAPER_ENABLED = "ride.offer.reaper.enabled";
    public static final String RIDE_OFFER_REAPER_BATCH_SIZE = "ride.offer.reaper.batch.size";

    public static final String MATCHING_SCORE_WEIGHT_ETA = "matching.score.weight.eta";
    public static final String MATCHING_SCORE_WEIGHT_RATING = "matching.score.weight.rating";
    public static final String MATCHING_SCORE_WEIGHT_ACCEPTANCE = "matching.score.weight.acceptance";

    private ConfigKeys() {
    }
}
