package com.ridehailing.pricing.dto;

import com.ridehailing.common.domain.CarType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CarTypeMultiplierRequest(
        @NotNull CarType carType,
        @NotNull @DecimalMin("0.01") BigDecimal multiplier) {
}
