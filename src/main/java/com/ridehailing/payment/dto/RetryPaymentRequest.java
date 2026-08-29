package com.ridehailing.payment.dto;

import com.ridehailing.payment.entity.PaymentMethod;

/** Nullable method: omitting it retries the same way, supplying it switches instrument after a decline. */
// No rideId and no amount: the ride comes from the path and the amount from the ride row.
public record RetryPaymentRequest(PaymentMethod paymentMethod) {
}
