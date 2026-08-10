package com.ridehailing.pricing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PricingZoneRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 40) String pricingRuleCode,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal centreLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal centreLongitude,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal radiusKm,
        int priority,
        boolean active) {
}
