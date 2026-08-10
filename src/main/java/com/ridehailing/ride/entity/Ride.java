package com.ridehailing.ride.entity;

import com.ridehailing.common.domain.CarType;
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
 * A ride and the immutable pricing snapshot it was sold at.
 *
 * The pricing columns are copied from the fare quote at booking time on
 * purpose: editing a pricing rule or expiring a coupon later must never change
 * what a historical ride cost. user_id / driver_id / vehicle_id are plain ids
 * because those rows live in other schemas owned by other modules.
 */
@Entity
@Table(name = "rides", catalog = "ride_schema")
@Getter
@Setter
public class Ride extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RideStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_car_type", nullable = false, length = 20, updatable = false)
    private CarType requestedCarType;

    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_car_type", length = 20)
    private CarType assignedCarType;

    @Column(name = "pickup_latitude", nullable = false, precision = 10, scale = 7, updatable = false)
    private BigDecimal pickupLatitude;

    @Column(name = "pickup_longitude", nullable = false, precision = 10, scale = 7, updatable = false)
    private BigDecimal pickupLongitude;

    @Column(name = "pickup_address", length = 255, updatable = false)
    private String pickupAddress;

    @Column(name = "drop_latitude", nullable = false, precision = 10, scale = 7, updatable = false)
    private BigDecimal dropLatitude;

    @Column(name = "drop_longitude", nullable = false, precision = 10, scale = 7, updatable = false)
    private BigDecimal dropLongitude;

    @Column(name = "drop_address", length = 255, updatable = false)
    private String dropAddress;

    @Column(name = "distance_km", nullable = false, precision = 8, scale = 3, updatable = false)
    private BigDecimal distanceKm;

    // Pricing snapshot ------------------------------------------------------
    // Written once by RideCreationService from the FareQuote and never updated.

    @Column(name = "pricing_rule_code", nullable = false, length = 40, updatable = false)
    private String pricingRuleCode;

    @Column(name = "distance_fare", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal distanceFare;

    @Column(name = "car_type_multiplier", nullable = false, precision = 4, scale = 2, updatable = false)
    private BigDecimal carTypeMultiplier;

    @Column(name = "surge_multiplier", nullable = false, precision = 4, scale = 2, updatable = false)
    private BigDecimal surgeMultiplier;

    @Column(name = "minimum_fare", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal minimumFare;

    @Column(name = "minimum_fare_applied", nullable = false, updatable = false)
    private boolean minimumFareApplied;

    @Column(name = "fare_before_discount", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal fareBeforeDiscount;

    @Column(name = "coupon_code", length = 40, updatable = false)
    private String couponCode;

    @Column(name = "discount_amount", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal discountAmount;

    @Column(name = "total_fare", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal totalFare;

    /** Serialised FareQuote breakdown. Held as text so the ride module never re-derives a fare. */
    @Column(name = "fare_breakdown", columnDefinition = "json", updatable = false)
    private String fareBreakdown;

    // Lifecycle timestamps --------------------------------------------------

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private CancelledBy cancelledBy;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;
}
