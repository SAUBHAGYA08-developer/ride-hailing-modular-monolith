package com.ridehailing.pricing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PricingRuleResponse(Long id,
                                  String code,
                                  String name,
                                  BigDecimal minimumFare,
                                  BigDecimal surgeMultiplier,
                                  boolean active,
                                  List<DistanceTierResponse> tiers,
                                  List<CarTypeMultiplierResponse> carTypeMultipliers,
                                  Instant updatedAt,
                                  String updatedBy) {
}
