package com.ridehailing.coupon.api;

import java.math.BigDecimal;

/**
 * The outcome of applying a coupon to a fare. This is the only coupon type the
 * pricing and ride modules depend on; nothing outside this module sees the
 * Coupon entity.
 */
public record CouponDiscount(Long couponId, String code, BigDecimal discountAmount) {
}
