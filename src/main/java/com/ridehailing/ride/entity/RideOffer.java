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

/** One candidate's turn at a REQUESTED ride; RideOfferService is the only writer. */
@Entity
@Table(name = "ride_offers", catalog = "ride_schema")
@Getter
@Setter
public class RideOffer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ride_id", nullable = false, updatable = false)
    private Long rideId;

    @Column(name = "driver_id", nullable = false, updatable = false)
    private Long driverId;

    @Column(name = "vehicle_id", nullable = false, updatable = false)
    private Long vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "car_type", nullable = false, length = 20, updatable = false)
    private CarType carType;

    @Column(name = "sequence", nullable = false, updatable = false)
    private int sequence;

    /** Straight-line km at ranking time, same approximation Ride#driverPickupDistanceKm already carries. */
    @Column(name = "pickup_distance_km", nullable = false, precision = 6, scale = 2, updatable = false)
    private BigDecimal pickupDistanceKm;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RideOfferStatus status;

    @Column(name = "offered_at", nullable = false, updatable = false)
    private Instant offeredAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;
}
