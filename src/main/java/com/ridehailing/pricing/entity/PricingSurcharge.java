package com.ridehailing.pricing.entity;

import com.ridehailing.common.domain.CarType;
import com.ridehailing.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalTime;

/** A charge added on top of the fare when its conditions match; a row, never a Java class per fee. */
@Entity
@Table(name = "pricing_surcharges", catalog = "pricing_schema")
@Getter
@Setter
public class PricingSurcharge extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null applies to every pricing rule, which is what a city wide fee wants. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pricing_rule_id")
    private PricingRule pricingRule;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 10)
    private SurchargeType chargeType;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "min_distance_km", precision = 7, scale = 2)
    private BigDecimal minDistanceKm;

    @Column(name = "max_distance_km", precision = 7, scale = 2)
    private BigDecimal maxDistanceKm;

    /** Null applies to every category. */
    @Enumerated(EnumType.STRING)
    @Column(name = "car_type", length = 20)
    private CarType carType;

    @Column(name = "active_from_time")
    private LocalTime activeFromTime;

    @Column(name = "active_to_time")
    private LocalTime activeToTime;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
