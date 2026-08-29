package com.ridehailing.pricing.repository;

import com.ridehailing.pricing.entity.PricingRule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Every read loads the whole aggregate in one round trip through an entity
 * graph: fetch joining two collections in one JPQL query would raise
 * MultipleBagFetchException, and quoting a fare must never lazily page in tiers.
 */
public interface PricingRuleRepository extends JpaRepository<PricingRule, Long> {

    @EntityGraph(attributePaths = {"distanceTiers", "carTypeMultipliers"})
    Optional<PricingRule> findByCode(String code);

    @EntityGraph(attributePaths = {"distanceTiers", "carTypeMultipliers"})
    Optional<PricingRule> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"distanceTiers", "carTypeMultipliers"})
    @Query("select r from PricingRule r order by r.code")
    List<PricingRule> findAllWithDetails();

    boolean existsByCode(String code);
}
