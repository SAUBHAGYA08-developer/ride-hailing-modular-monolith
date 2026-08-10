package com.ridehailing.driver.entity;

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
 * Driver profile. {@code userId} points at user_schema.users without a FK so
 * the two schemas stay independently deployable.
 *
 * The last known coordinates are a coarse recovery snapshot written only when a
 * driver changes status. Live GPS lives in Redis; persisting every ping would
 * turn this row into a write hot spot and churn the reservation version.
 */
@Entity
@Table(name = "drivers", catalog = "driver_schema")
@Getter
@Setter
public class Driver extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "license_number", nullable = false, length = 40, updatable = false)
    private String licenseNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DriverStatus status = DriverStatus.OFFLINE;

    @Column(name = "rating", nullable = false, precision = 3, scale = 2)
    private BigDecimal rating = new BigDecimal("5.00");

    @Column(name = "total_rides", nullable = false)
    private int totalRides;

    @Column(name = "last_known_latitude", precision = 10, scale = 7)
    private BigDecimal lastKnownLatitude;

    @Column(name = "last_known_longitude", precision = 10, scale = 7)
    private BigDecimal lastKnownLongitude;

    @Column(name = "last_location_at")
    private Instant lastLocationAt;
}
