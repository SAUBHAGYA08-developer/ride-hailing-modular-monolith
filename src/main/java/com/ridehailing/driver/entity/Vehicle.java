package com.ridehailing.driver.entity;

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

/**
 * A driver's vehicle. The owning driver is a plain column rather than an
 * association: candidate selection reads vehicles by driver id in bulk and an
 * association would drag driver rows into that hot query.
 */
@Entity
@Table(name = "vehicles", catalog = "driver_schema")
@Getter
@Setter
public class Vehicle extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "driver_id", nullable = false, updatable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "car_type", nullable = false, length = 20)
    private CarType carType;

    @Column(name = "registration_number", nullable = false, length = 20, updatable = false)
    private String registrationNumber;

    @Column(name = "make", length = 50)
    private String make;

    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "color", length = 30)
    private String color;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
