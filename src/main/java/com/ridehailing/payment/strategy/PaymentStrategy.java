package com.ridehailing.payment.strategy;

import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.entity.PaymentMethod;

/** Adding a method is an enum constant plus one @Component here; no service or controller changes. */
public interface PaymentStrategy {

    PaymentMethod method();

    /** Never throws for a decline, and never writes the payments table - PaymentService owns both. */
    PaymentResult collect(PaymentRequest request);
}
