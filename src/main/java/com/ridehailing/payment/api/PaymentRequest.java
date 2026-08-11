package com.ridehailing.payment.api;

import com.ridehailing.payment.entity.PaymentPurpose;

import java.math.BigDecimal;

/** Every field is resolved from the ride row, never from a client body, so a caller cannot redirect a charge. */
// Doubles as the strategy input: a strategy needs exactly these facts, and the factory already consumed the method.
public record PaymentRequest(Long rideId,
                             Long userId,
                             Long driverId,
                             BigDecimal amount,
                             PaymentPurpose purpose) {
}
