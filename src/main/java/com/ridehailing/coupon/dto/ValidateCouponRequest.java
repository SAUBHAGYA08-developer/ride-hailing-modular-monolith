package com.ridehailing.coupon.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** The user is taken from the authenticated principal, never from the body. */
public record ValidateCouponRequest(@NotNull @DecimalMin("0.0") BigDecimal fareAmount) {
}
