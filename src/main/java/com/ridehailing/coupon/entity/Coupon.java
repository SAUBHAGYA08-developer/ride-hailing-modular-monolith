package com.ridehailing.coupon.entity;

import com.ridehailing.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A promotional code. Never physically deleted once created: redemptions
 * reference it by FK, so retiring a coupon is a status change.
 *
 * usedCount is mutated exclusively by the guarded UPDATE in CouponRepository,
 * so it is mapped read only here and dirty checking can never write it back
 * with a stale value.
 */
@Entity
@Table(name = "coupons", catalog = "coupon_schema")
@Getter
@Setter
public class Coupon extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    /** Cap on the computed discount. Meaningful for PERCENTAGE only. */
    @Column(name = "max_discount_amount", precision = 10, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(name = "min_ride_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal minRideAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CouponStatus status = CouponStatus.ACTIVE;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    /** null means unlimited across all users. */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    /** null means unlimited per user. */
    @Column(name = "per_user_limit")
    private Integer perUserLimit;

    @Column(name = "used_count", nullable = false, insertable = false, updatable = false)
    private int usedCount;
}