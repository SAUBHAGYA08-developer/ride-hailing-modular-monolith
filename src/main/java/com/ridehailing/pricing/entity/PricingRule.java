package com.ridehailing.pricing.entity;

import com.ridehailing.common.jpa.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One complete fare policy. Every number the fare engine uses lives on this
 * aggregate or its children - nothing about pricing is expressed in Java.
 */
@Entity
@Table(name = "pricing_rules", catalog = "pricing_schema")
@Getter
@Setter
public class PricingRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "minimum_fare", nullable = false, precision = 10, scale = 2)
    private BigDecimal minimumFare;

    @Column(name = "surge_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal surgeMultiplier;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * Sets rather than lists: both collections are fetched in a single query and
     * bag semantics would make that a MultipleBagFetchException. The collection
     * instance itself is never replaced, otherwise orphan removal loses track of
     * the children it has to delete.
     */
    @OneToMany(mappedBy = "pricingRule", cascade = CascadeType.ALL, orphanRemoval = true)
    @Setter(AccessLevel.NONE)
    private Set<PricingDistanceTier> distanceTiers = new LinkedHashSet<>();

    @OneToMany(mappedBy = "pricingRule", cascade = CascadeType.ALL, orphanRemoval = true)
    @Setter(AccessLevel.NONE)
    private Set<PricingCarTypeMultiplier> carTypeMultipliers = new LinkedHashSet<>();

    public void addDistanceTier(PricingDistanceTier tier) {
        tier.setPricingRule(this);
        distanceTiers.add(tier);
    }

    public void addCarTypeMultiplier(PricingCarTypeMultiplier multiplier) {
        multiplier.setPricingRule(this);
        carTypeMultipliers.add(multiplier);
    }
}
