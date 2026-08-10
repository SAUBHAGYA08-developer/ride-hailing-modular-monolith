package com.ridehailing.ride.dto;

import com.ridehailing.common.domain.CarType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * There is deliberately no userId field: the rider is always the authenticated
 * principal, so a caller cannot book a ride in someone else's name.
 */
public record CreateRideRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal pickupLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal pickupLongitude,
        @Size(max = 255) String pickupAddress,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal dropLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal dropLongitude,
        @Size(max = 255) String dropAddress,
        @NotNull CarType carType,
        @Size(max = 40) String couponCode) {
}
