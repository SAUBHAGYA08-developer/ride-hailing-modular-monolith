package com.ridehailing.payment.entity;

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

/** One collection attempt; several may exist per (ride, purpose) but at most one SUCCESS - see PaymentService. */
// Extends BaseEntity, unlike CouponRedemption: a financial row needs created_by, and a real partner would later flip status.
@Entity
@Table(name = "payments", catalog = "payment_schema")
@Getter
@Setter
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Plain ids: these rows live in schemas owned by other modules, so no FK crosses the boundary.
    @Column(name = "ride_id", nullable = false, updatable = false)
    private Long rideId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** Null when the ride never had a driver, which only a cancellation can be. */
    @Column(name = "driver_id", updatable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20, updatable = false)
    private PaymentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20, updatable = false)
    private PaymentMethod method;

    /** Snapshotted from the ride, so reading it back never re-prices history. */
    @Column(name = "amount", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    /** The partner handle support quotes back. Null on a decline. */
    @Column(name = "reference", length = 64)
    private String reference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;
}
