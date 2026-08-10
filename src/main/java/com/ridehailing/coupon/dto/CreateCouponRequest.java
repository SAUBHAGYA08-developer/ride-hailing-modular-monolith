package com.ridehailing.coupon.dto;

import com.ridehailing.coupon.entity.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateCouponRequest(

        @NotBlank @Size(max = 40) String code,

        @Size(max = 255) String description,

        @NotNull DiscountType discountType,

        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal discountValue,

        BigDecimal maxDiscountAmount,

        @NotNull @DecimalMin("0.0") BigDecimal minRideAmount,

        Instant validFrom,

        Instant validUntil,

        @Min(1) Integer usageLimit,

        @Min(1) Integer perUserLimit) {
}
