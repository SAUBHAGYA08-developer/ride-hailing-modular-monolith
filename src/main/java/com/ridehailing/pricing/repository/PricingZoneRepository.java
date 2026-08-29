package com.ridehailing.pricing.repository;

import com.ridehailing.pricing.entity.PricingZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PricingZoneRepository extends JpaRepository<PricingZone, Long> {

    boolean existsByCode(String code);

    Optional<PricingZone> findByCode(String code);

    /**
     * Candidate zones for a pickup, highest priority first.
     *
     * Only the rule header is fetched: resolution needs nothing but its code,
     * and the winning rule is then loaded with its tiers through the existing
     * findByCode path rather than duplicating that logic here. The row count is
     * one per city, so scanning them in memory beats a spatial query.
     */
    @Query("""
            select z from PricingZone z
            join fetch z.pricingRule
            where z.active = true
            order by z.priority desc, z.radiusKm asc
            """)
    List<PricingZone> findActiveWithRules();

    @Query("select z from PricingZone z join fetch z.pricingRule order by z.priority desc, z.code")
    List<PricingZone> findAllWithRules();
}
