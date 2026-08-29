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

/** One slab of the distance ladder. The interval is half open: [fromKm, toKm). */
@Entity
@Table(name = "pricing_distance_tiers", catalog = "pricing_schema")
@Getter
@Setter
public class PricingDistanceTier extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pricing_rule_id", nullable = false)
    private PricingRule pricingRule;

    @Column(name = "from_km", nullable = false, precision = 6, scale = 2)
    private BigDecimal fromKm;

    /** Null marks the open ended top tier - every kilometre beyond fromKm bills at this rate. */
    @Column(name = "to_km", precision = 6, scale = 2)
    private BigDecimal toKm;

    @Column(name = "rate_per_km", nullable = false, precision = 10, scale = 2)
    private BigDecimal ratePerKm;
}
