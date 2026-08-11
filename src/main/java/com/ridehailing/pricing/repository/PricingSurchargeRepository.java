package com.ridehailing.pricing.repository;

import com.ridehailing.pricing.entity.PricingSurcharge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PricingSurchargeRepository extends JpaRepository<PricingSurcharge, Long> {

    /** Rows for one rule plus the rule-agnostic ones, ordered so a breakdown reads the same every time. */
    @Query("""
            select s from PricingSurcharge s
            where s.active = true
              and (s.pricingRule is null or s.pricingRule.id = :ruleId)
            order by s.priority asc, s.code asc
            """)
    List<PricingSurcharge> findApplicable(@Param("ruleId") Long ruleId);

    List<PricingSurcharge> findAllByOrderByPriorityAscCodeAsc();
}
