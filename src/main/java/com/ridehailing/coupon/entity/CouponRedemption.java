package com.ridehailing.coupon.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One consumed use of a coupon. Append only, which is why it deliberately does
 * not extend BaseEntity: there is nothing to update and therefore no
 * optimistic lock to hold. A reversal deletes the row rather than mutating it.
 *
 * userId and rideId are plain columns and not FKs: they point at rows owned by
 * user_schema and ride_schema, and modules never join across schema boundaries.
 */
@Entity
@Table(name = "coupon_redemptions", catalog = "coupon_schema")
@Getter
@Setter
public class CouponRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_id", nullable = false, updatable = false)
    private Long couponId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "ride_id", nullable = false, updatable = false)
    private Long rideId;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal discountAmount;

    @Column(name = "redeemed_at", nullable = false, updatable = false)
    private Instant redeemedAt;
}
