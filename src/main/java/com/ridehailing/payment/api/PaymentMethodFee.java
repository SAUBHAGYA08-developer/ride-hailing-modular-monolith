package com.ridehailing.payment.api;

import com.ridehailing.payment.entity.PaymentMethod;

import java.math.BigDecimal;

/** What a rider or driver may see before choosing a method: the flat surcharge it currently carries. */
public record PaymentMethodFee(PaymentMethod method, BigDecimal fee) {
}
