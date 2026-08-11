package com.ridehailing.audit;

/** Business audit vocabulary. Technical operations deliberately have no entry. */
public final class AuditActions {

    public static final String DRIVER_STATUS_CHANGED = "DRIVER_STATUS_CHANGED";
    public static final String DRIVER_REGISTERED = "DRIVER_REGISTERED";
    public static final String VEHICLE_REGISTERED = "VEHICLE_REGISTERED";

    public static final String RIDE_CREATED = "RIDE_CREATED";
    public static final String RIDE_STATUS_CHANGED = "RIDE_STATUS_CHANGED";

    /** Filed against the Ride entity, so the money shows on the same timeline as the status changes that caused it. */
    public static final String PAYMENT_COLLECTED = "PAYMENT_COLLECTED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    public static final String COUPON_CREATED = "COUPON_CREATED";
    public static final String COUPON_STATUS_CHANGED = "COUPON_STATUS_CHANGED";
    public static final String COUPON_REDEEMED = "COUPON_REDEEMED";
    public static final String COUPON_REDEMPTION_REVERSED = "COUPON_REDEMPTION_REVERSED";

    public static final String PRICING_RULE_CREATED = "PRICING_RULE_CREATED";
    public static final String PRICING_RULE_CHANGED = "PRICING_RULE_CHANGED";

    public static final String CONFIGURATION_CHANGED = "CONFIGURATION_CHANGED";

    private AuditActions() {
    }
}
