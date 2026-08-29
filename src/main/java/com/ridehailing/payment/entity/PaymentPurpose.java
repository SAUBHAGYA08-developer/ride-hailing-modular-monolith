package com.ridehailing.payment.entity;

/** A cancellation fee flows through this module too; the purpose is what keeps the two apart. */
public enum PaymentPurpose {
    RIDE_FARE,
    CANCELLATION_FEE,
    /** A per-method surcharge, collected as its own row alongside RIDE_FARE rather than folded into the fare. */
    PAYMENT_METHOD_FEE
}
