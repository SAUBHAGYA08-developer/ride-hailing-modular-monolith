package com.ridehailing.pricing.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PricingZoneResponse(Long id,
                                  String code,
                                  String name,
                                  String pricingRuleCode,
                                  BigDecimal centreLatitude,
                                  BigDecimal centreLongitude,
                                  BigDecimal radiusKm,
                                  int priority,
                                  boolean active,
                                  Instant updatedAt,
                                  String updatedBy) {
}
