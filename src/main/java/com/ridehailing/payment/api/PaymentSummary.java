package com.ridehailing.payment.api;

import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.entity.PaymentPurpose;
import com.ridehailing.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** What callers and clients may see of a payment; the Payment entity never leaves this module. */
// Nested inside RideResponse, which is serialised into idempotency_keys.response_body, so keep it Jackson round-trippable.
public record PaymentSummary(Long id,
                             Long rideId,
                             PaymentPurpose purpose,
                             PaymentMethod method,
                             PaymentStatus status,
                             BigDecimal amount,
                             String reference,
                             String failureReason,
                             Instant collectedAt) {
}
