package com.ridehailing.pricing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bean validation only guards each field in isolation. The relationships
 * between tiers - ordering, gaps, the single open ended tier - are business
 * rules and are checked in PricingRuleService.
 */
public record PricingRuleRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 100) String name,
        @NotNull @DecimalMin("0.0") BigDecimal minimumFare,
        @NotNull @DecimalMin("1.0") BigDecimal surgeMultiplier,
        boolean active,
        @NotEmpty @Valid List<DistanceTierRequest> tiers,
        @NotEmpty @Valid List<CarTypeMultiplierRequest> carTypeMultipliers) {
}
