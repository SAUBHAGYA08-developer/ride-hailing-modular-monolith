package com.ridehailing.pricing.service;

import com.ridehailing.common.domain.CarType;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.common.util.Money;
import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.coupon.api.CouponDiscount;
import com.ridehailing.coupon.service.CouponService;
import com.ridehailing.pricing.api.FareQuote;
import com.ridehailing.pricing.entity.PricingCarTypeMultiplier;
import com.ridehailing.pricing.entity.PricingDistanceTier;
import com.ridehailing.pricing.entity.PricingRule;
import com.ridehailing.pricing.repository.PricingRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single place a fare is computed.
 *
 * Every input - minimum fare, tier rates, car type factors, surge - is read
 * from pricing_schema or from business configuration. The only literal here is
 * the multiplicative identity used when a factor is absent or switched off.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    /**
     * Not a price: the identity element of multiplication, i.e. what "no
     * adjustment" means. Scaled like the DECIMAL(4,2) multiplier columns so the
     * value the ride snapshot stores looks identical whether it came from a row
     * or from this fallback.
     */
    private static final BigDecimal NO_ADJUSTMENT = new BigDecimal("1.00");

    private final PricingRuleRepository pricingRuleRepository;
    private final ConfigurationService configurationService;
    private final CouponService couponService;

    @Transactional(readOnly = true)
    public FareQuote quote(BigDecimal distanceKm, CarType requestedCarType, String couponCode, Long userId) {
        if (distanceKm == null || distanceKm.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Distance in kilometres must be zero or greater");
        }
        if (requestedCarType == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A car type is required to quote a fare");
        }

        PricingRule rule = activeRule();

        List<Map<String, Object>> tierLines = new ArrayList<>();
        BigDecimal distanceFare = distanceFare(rule, distanceKm, tierLines);

        BigDecimal carTypeMultiplier = carTypeMultiplier(rule, requestedCarType);
        boolean surgeEnabled = configurationService.getBoolean(ConfigKeys.SURGE_ENABLED, false);
        BigDecimal surgeMultiplier = surgeEnabled ? rule.getSurgeMultiplier() : NO_ADJUSTMENT;

        // Rounded once, after both factors: rounding in between would apply the
        // half up step twice to the same amount.
        BigDecimal adjustedFare = Money.round(distanceFare.multiply(carTypeMultiplier).multiply(surgeMultiplier));

        BigDecimal minimumFare = Money.round(rule.getMinimumFare());
        boolean minimumFareApplied = adjustedFare.compareTo(minimumFare) < 0;
        BigDecimal fareBeforeDiscount = minimumFareApplied ? minimumFare : adjustedFare;

        String appliedCouponCode = null;
        Long couponId = null;
        BigDecimal discountAmount = Money.zero();
        if (StringUtils.hasText(couponCode)) {
            // The coupon module owns validity; an unusable coupon surfaces as its
            // own BusinessException and must reach the caller unchanged.
            CouponDiscount discount = couponService.evaluate(couponCode.trim(), fareBeforeDiscount, userId);
            appliedCouponCode = discount.code();
            couponId = discount.couponId();
            // Capped at the fare so the recorded discount always equals what the
            // rider actually saved.
            discountAmount = Money.nonNegative(discount.discountAmount()).min(fareBeforeDiscount);
        }
        BigDecimal totalFare = Money.nonNegative(fareBeforeDiscount.subtract(discountAmount));

        Map<String, Object> breakdown = breakdown(rule, distanceKm, tierLines, distanceFare, carTypeMultiplier,
                requestedCarType, surgeEnabled, surgeMultiplier, minimumFare, minimumFareApplied, fareBeforeDiscount,
                appliedCouponCode, discountAmount, totalFare);

        return new FareQuote(rule.getCode(), distanceKm, distanceFare, carTypeMultiplier, surgeMultiplier,
                minimumFare, minimumFareApplied, fareBeforeDiscount, appliedCouponCode, couponId,
                discountAmount, totalFare, breakdown);
    }

    private PricingRule activeRule() {
        String ruleCode = configurationService.getString(ConfigKeys.PRICING_ACTIVE_RULE);
        PricingRule rule = pricingRuleRepository.findByCode(ruleCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRICING_RULE_NOT_FOUND,
                        "Pricing rule " + ruleCode + " does not exist"));
        if (!rule.isActive()) {
            throw new BusinessException(ErrorCode.PRICING_RULE_NOT_FOUND,
                    "Pricing rule " + ruleCode + " is not active");
        }
        return rule;
    }

    /**
     * Slab pricing: each tier bills only the part of the trip that falls inside
     * its own half open interval, so 6 km over 0-2, 2-5 and 5+ bills 2, 3 and 1
     * kilometres. Each line is rounded on its own so the breakdown the rider
     * sees adds up exactly to the distance fare.
     */
    private BigDecimal distanceFare(PricingRule rule, BigDecimal distanceKm, List<Map<String, Object>> tierLines) {
        BigDecimal distanceFare = Money.zero();
        for (PricingDistanceTier tier : sortedTiers(rule)) {
            BigDecimal upperBound = tier.getToKm() == null ? distanceKm : tier.getToKm().min(distanceKm);
            BigDecimal billableKm = upperBound.subtract(tier.getFromKm());
            if (billableKm.signum() <= 0) {
                continue;
            }
            BigDecimal amount = Money.round(billableKm.multiply(tier.getRatePerKm()));
            distanceFare = distanceFare.add(amount);

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("fromKm", tier.getFromKm());
            line.put("toKm", tier.getToKm());
            line.put("ratePerKm", tier.getRatePerKm());
            line.put("kmBilled", billableKm);
            line.put("amount", amount);
            tierLines.add(Collections.unmodifiableMap(line));
        }
        return distanceFare;
    }

    private List<PricingDistanceTier> sortedTiers(PricingRule rule) {
        return rule.getDistanceTiers().stream()
                .sorted(Comparator.comparing(PricingDistanceTier::getFromKm))
                .toList();
    }

    private BigDecimal carTypeMultiplier(PricingRule rule, CarType carType) {
        return rule.getCarTypeMultipliers().stream()
                .filter(multiplier -> multiplier.getCarType() == carType)
                .map(PricingCarTypeMultiplier::getMultiplier)
                .findFirst()
                .orElse(NO_ADJUSTMENT);
    }

    /**
     * The ride stores this map verbatim as its pricing snapshot, so it carries
     * every input the fare was derived from and only JSON native values.
     */
    private Map<String, Object> breakdown(PricingRule rule,
                                          BigDecimal distanceKm,
                                          List<Map<String, Object>> tierLines,
                                          BigDecimal distanceFare,
                                          BigDecimal carTypeMultiplier,
                                          CarType carType,
                                          boolean surgeEnabled,
                                          BigDecimal surgeMultiplier,
                                          BigDecimal minimumFare,
                                          boolean minimumFareApplied,
                                          BigDecimal fareBeforeDiscount,
                                          String couponCode,
                                          BigDecimal discountAmount,
                                          BigDecimal totalFare) {
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("pricingRuleCode", rule.getCode());
        breakdown.put("pricingRuleName", rule.getName());
        breakdown.put("distanceKm", distanceKm);
        breakdown.put("tiers", List.copyOf(tierLines));
        breakdown.put("distanceFare", distanceFare);
        breakdown.put("carType", carType.name());
        breakdown.put("carTypeMultiplier", carTypeMultiplier);
        breakdown.put("surgeEnabled", surgeEnabled);
        breakdown.put("surgeMultiplier", surgeMultiplier);
        breakdown.put("minimumFare", minimumFare);
        breakdown.put("minimumFareApplied", minimumFareApplied);
        breakdown.put("fareBeforeDiscount", fareBeforeDiscount);
        breakdown.put("couponCode", couponCode);
        breakdown.put("discountAmount", discountAmount);
        breakdown.put("totalFare", totalFare);
        return Collections.unmodifiableMap(breakdown);
    }
}
