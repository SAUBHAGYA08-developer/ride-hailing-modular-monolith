package com.ridehailing.pricing.dto;

import java.math.BigDecimal;

/**
 * The price of a trip nobody has booked. Nothing behind this record is held:
 * no driver is reserved and no coupon use is taken, so the numbers may move
 * before the rider actually books.
 *
 * An unusable coupon is an answer here rather than a failure, exactly as in
 * CouponValidationResponse - refusing to price the trip at all would tell the
 * rider nothing about the trip. couponReason carries the ErrorCode name so a
 * client can branch on it without parsing the message.
 */
public record FareEstimateResponse(
        BigDecimal distanceKm,
        String pricingRuleCode,
        String pricingZoneCode,
        BigDecimal distanceFare,
        BigDecimal carTypeMultiplier,
        BigDecimal surgeMultiplier,
        BigDecimal minimumFare,
        boolean minimumFareApplied,
        BigDecimal fareBeforeDiscount,
        String couponCode,
        boolean couponApplicable,
        String couponReason,
        String couponMessage,
        BigDecimal discountAmount,
        BigDecimal totalFare) {
}
