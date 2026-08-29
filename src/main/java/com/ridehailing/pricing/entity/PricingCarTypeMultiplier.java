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

/** The factor applied to the distance fare for one vehicle category. */
@Entity
@Table(name = "pricing_car_type_multipliers", catalog = "pricing_schema")
@Getter
@Setter
public class PricingCarTypeMultiplier extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pricing_rule_id", nullable = false)
    private PricingRule pricingRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "car_type", nullable = false, length = 20)
    private CarType carType;

    @Column(name = "multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal multiplier;
}
