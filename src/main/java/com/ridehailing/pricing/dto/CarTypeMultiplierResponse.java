package com.ridehailing.pricing.dto;

import com.ridehailing.common.domain.CarType;

import java.math.BigDecimal;

public record CarTypeMultiplierResponse(Long id, CarType carType, BigDecimal multiplier) {
}
