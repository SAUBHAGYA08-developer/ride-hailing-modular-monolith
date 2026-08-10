package com.ridehailing.pricing.api;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The result of pricing one trip.
 *
 * The ride module copies these fields onto the ride row and stores
 * {@code breakdown} as the ride's immutable pricing snapshot, so every value
 * here must be final and self describing.
 */
public record FareQuote(
        String pricingRuleCode,
        BigDecimal distanceKm,
        BigDecimal distanceFare,
        BigDecimal carTypeMultiplier,
        BigDecimal surgeMultiplier,
        BigDecimal minimumFare,
        boolean minimumFareApplied,
        BigDecimal fareBeforeDiscount,
        String couponCode,
        Long couponId,
        BigDecimal discountAmount,
        BigDecimal totalFare,
        Map<String, Object> breakdown) {
}
