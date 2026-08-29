package com.ridehailing.ride.dto;

import java.math.BigDecimal;

/** The pricing snapshot as stored on the ride. It never changes after creation. */
public record FareSummary(String pricingRuleCode,
                          String pricingZoneCode,
                          BigDecimal distanceFare,
                          BigDecimal carTypeMultiplier,
                          BigDecimal surgeMultiplier,
                          BigDecimal minimumFare,
                          boolean minimumFareApplied,
                          BigDecimal fareBeforeDiscount,
                          String couponCode,
                          BigDecimal discountAmount,
                          BigDecimal totalFare) {
}
