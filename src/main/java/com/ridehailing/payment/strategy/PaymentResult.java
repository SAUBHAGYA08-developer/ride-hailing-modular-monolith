package com.ridehailing.payment.strategy;

import com.ridehailing.payment.entity.PaymentStatus;

/** A decline is a result, not an exception: the ride is over either way and must not be unwound. */
public record PaymentResult(PaymentStatus status, String reference, String failureReason) {

    public static PaymentResult success(String reference) {
        return new PaymentResult(PaymentStatus.SUCCESS, reference, null);
    }

    public static PaymentResult failed(String failureReason) {
        return new PaymentResult(PaymentStatus.FAILED, null, failureReason);
    }
}
