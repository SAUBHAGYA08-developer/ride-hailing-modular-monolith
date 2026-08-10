package com.ridehailing.coupon.dto;

import com.ridehailing.coupon.entity.CouponStatus;
import com.ridehailing.coupon.entity.DiscountType;

import java.math.BigDecimal;
import java.time.Instant;

public record CouponResponse(Long id,
                             String code,
                             String description,
                             DiscountType discountType,
                             BigDecimal discountValue,
                             BigDecimal maxDiscountAmount,
                             BigDecimal minRideAmount,
                             CouponStatus status,
                             Instant validFrom,
                             Instant validUntil,
                             Integer usageLimit,
                             Integer perUserLimit,
                             int usedCount) {
}
