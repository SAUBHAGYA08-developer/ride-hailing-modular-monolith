package com.ridehailing.pricing.dto;

import java.math.BigDecimal;

public record DistanceTierResponse(Long id, BigDecimal fromKm, BigDecimal toKm, BigDecimal ratePerKm) {
}
