package com.ridehailing.payment.entity;

/** A cancellation fee flows through this module too; the purpose is what keeps the two apart. */
public enum PaymentPurpose {
    RIDE_FARE,
    CANCELLATION_FEE
}
