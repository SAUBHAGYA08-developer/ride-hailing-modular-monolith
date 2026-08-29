package com.ridehailing.pricing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** A null toKm declares the open ended top tier; only the highest tier may use it. */
public record DistanceTierRequest(
        @NotNull @DecimalMin("0.0") BigDecimal fromKm,
        BigDecimal toKm,
        @NotNull @DecimalMin("0.0") BigDecimal ratePerKm) {
}
