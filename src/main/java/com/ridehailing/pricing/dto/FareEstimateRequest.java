package com.ridehailing.pricing.dto;

import com.ridehailing.common.domain.CarType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The trip a CreateRideRequest describes, minus everything only a real booking
 * needs. There is deliberately no userId here either: per user coupon limits are
 * counted against the authenticated principal, so a caller must not be able to
 * price a coupon as somebody else.
 */
public record FareEstimateRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal pickupLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal pickupLongitude,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal dropLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal dropLongitude,
        @NotNull CarType carType,
        @Size(max = 40) String couponCode) {
}
