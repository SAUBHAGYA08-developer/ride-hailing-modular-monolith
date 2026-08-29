package com.ridehailing.ride.dto;

import com.ridehailing.payment.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;

/** Asked at completion, not at booking: only the driver can say how they were actually paid. */
// Mandatory rather than defaulting to CASH - guessing how money moved would turn a client bug into a bogus cash ledger.
public record CompleteRideRequest(@NotNull PaymentMethod paymentMethod) {
}
