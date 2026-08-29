package com.ridehailing.pricing.entity;

import com.ridehailing.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * A circular area that maps a pickup point to a pricing rule.
 *
 * Cities are modelled as large circles; a smaller high priority zone (an
 * airport, a downtown surcharge area) can be added later inside one without
 * any code change.
 */
@Entity
@Table(name = "pricing_zones", catalog = "pricing_schema")
@Getter
@Setter
public class PricingZone extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pricing_rule_id", nullable = false)
    private PricingRule pricingRule;

    @Column(name = "centre_latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal centreLatitude;

    @Column(name = "centre_longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal centreLongitude;

    @Column(name = "radius_km", nullable = false, precision = 7, scale = 2)
    private BigDecimal radiusKm;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
