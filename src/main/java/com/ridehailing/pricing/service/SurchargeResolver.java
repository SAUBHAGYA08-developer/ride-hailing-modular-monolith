package com.ridehailing.pricing.service;

import com.ridehailing.common.domain.CarType;
import com.ridehailing.common.util.Money;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.pricing.SurchargePolicy;
import com.ridehailing.pricing.api.AppliedSurcharge;
import com.ridehailing.pricing.entity.PricingRule;
import com.ridehailing.pricing.entity.PricingSurcharge;
import com.ridehailing.pricing.repository.PricingSurchargeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** Turns surcharge rows into the lines that applied to one fare; adding a fee is a row, not a class. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurchargeResolver {

    private static final String SURCHARGES_ENABLED = "pricing.surcharges.enabled";
    private static final String SURCHARGE_ZONE = "pricing.surcharges.time.zone";
    private static final String DEFAULT_ZONE = "Asia/Kolkata";

    private final PricingSurchargeRepository surchargeRepository;
    private final ConfigurationService configurationService;

    /** Percentages read against fareBeforeDiscount, so a coupon cannot be used to neutralise a surcharge. */
    @Transactional(readOnly = true)
    public List<AppliedSurcharge> resolve(PricingRule rule, BigDecimal distanceKm, CarType carType,
                                          BigDecimal fareBeforeDiscount) {
        if (!configurationService.getBoolean(SURCHARGES_ENABLED, true)) {
            return List.of();
        }
        Long ruleId = rule == null ? null : rule.getId();
        List<PricingSurcharge> candidates = surchargeRepository.findApplicable(ruleId);
        if (candidates.isEmpty()) {
            return List.of();
        }

        LocalTime now = localNow();
        List<AppliedSurcharge> applied = new ArrayList<>(candidates.size());
        for (PricingSurcharge surcharge : candidates) {
            if (!SurchargePolicy.matches(surcharge, distanceKm, carType, now)) {
                continue;
            }
            BigDecimal amount = SurchargePolicy.amountFor(surcharge, fareBeforeDiscount);
            // A zero line is noise in a breakdown a rider is meant to read.
            if (amount.signum() <= 0) {
                continue;
            }
            applied.add(new AppliedSurcharge(surcharge.getCode(), surcharge.getName(),
                    surcharge.getChargeType().name(), surcharge.getAmount(), amount));
        }
        return applied;
    }

    public BigDecimal total(List<AppliedSurcharge> surcharges) {
        return Money.round(surcharges.stream()
                .map(AppliedSurcharge::amount)
                .reduce(Money.zero(), BigDecimal::add));
    }

    /** A night fee has to mean local night, so the window is evaluated in a configured zone, never in UTC. */
    private LocalTime localNow() {
        String zone = configurationService.getString(SURCHARGE_ZONE, DEFAULT_ZONE);
        try {
            return ZonedDateTime.now(ZoneId.of(zone)).toLocalTime();
        } catch (RuntimeException ex) {
            log.warn("Unusable surcharge time zone '{}', falling back to {}", zone, DEFAULT_ZONE);
            return ZonedDateTime.now(ZoneId.of(DEFAULT_ZONE)).toLocalTime();
        }
    }
}
