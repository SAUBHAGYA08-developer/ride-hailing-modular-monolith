package com.ridehailing.pricing.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.domain.CarType;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.pricing.dto.CarTypeMultiplierRequest;
import com.ridehailing.pricing.dto.CarTypeMultiplierResponse;
import com.ridehailing.pricing.dto.DistanceTierRequest;
import com.ridehailing.pricing.dto.DistanceTierResponse;
import com.ridehailing.pricing.dto.PricingRuleRequest;
import com.ridehailing.pricing.dto.PricingRuleResponse;
import com.ridehailing.pricing.entity.PricingCarTypeMultiplier;
import com.ridehailing.pricing.entity.PricingDistanceTier;
import com.ridehailing.pricing.entity.PricingRule;
import com.ridehailing.pricing.repository.PricingRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Administration of fare policies. Only this service writes pricing_schema. */
@Service
@RequiredArgsConstructor
public class PricingRuleService {

    private final PricingRuleRepository pricingRuleRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<PricingRuleResponse> findAll() {
        return pricingRuleRepository.findAllWithDetails().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PricingRuleResponse create(PricingRuleRequest request) {
        String code = normalisedCode(request);
        if (pricingRuleRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.PRICING_RULE_ALREADY_EXISTS,
                    "Pricing rule " + code + " already exists");
        }
        List<DistanceTierRequest> tiers = validatedTiers(request.tiers());
        validateCarTypeMultipliers(request.carTypeMultipliers());

        PricingRule rule = new PricingRule();
        rule.setCode(code);
        applyScalarFields(rule, request);
        applyChildren(rule, tiers, request.carTypeMultipliers());

        PricingRule saved = pricingRuleRepository.saveAndFlush(rule);
        PricingRuleResponse response = toResponse(saved);
        auditService.record(AuditEntities.PRICING_RULE, saved.getId(), AuditActions.PRICING_RULE_CREATED,
                null, response);
        return response;
    }

    /**
     * Rewrites a rule in place.
     *
     * Rides already created keep the fare they were quoted: each one persists its
     * own pricing snapshot, so nothing written here can re-price history. The
     * version column on the rule rejects a second administrator editing the same
     * rule from a stale read.
     */
    @Transactional
    public PricingRuleResponse update(Long id, PricingRuleRequest request) {
        PricingRule rule = pricingRuleRepository.findWithDetailsById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRICING_RULE_NOT_FOUND,
                        "Pricing rule " + id + " does not exist"));

        String code = normalisedCode(request);
        if (!rule.getCode().equals(code) && pricingRuleRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.PRICING_RULE_ALREADY_EXISTS,
                    "Pricing rule " + code + " already exists");
        }
        List<DistanceTierRequest> tiers = validatedTiers(request.tiers());
        validateCarTypeMultipliers(request.carTypeMultipliers());

        PricingRuleResponse before = toResponse(rule);

        rule.setCode(code);
        applyScalarFields(rule, request);

        rule.getDistanceTiers().clear();
        rule.getCarTypeMultipliers().clear();
        // The removals must reach the database before the replacements: the
        // (rule, from_km) and (rule, car_type) unique keys forbid the two
        // generations of rows overlapping inside a single flush.
        pricingRuleRepository.flush();
        applyChildren(rule, tiers, request.carTypeMultipliers());

        PricingRule saved = pricingRuleRepository.saveAndFlush(rule);
        PricingRuleResponse after = toResponse(saved);
        auditService.record(AuditEntities.PRICING_RULE, saved.getId(), AuditActions.PRICING_RULE_CHANGED,
                before, after);
        return after;
    }

    private String normalisedCode(PricingRuleRequest request) {
        return request.code().trim().toUpperCase(Locale.ROOT);
    }

    private void applyScalarFields(PricingRule rule, PricingRuleRequest request) {
        rule.setName(request.name().trim());
        rule.setMinimumFare(request.minimumFare());
        rule.setSurgeMultiplier(request.surgeMultiplier());
        rule.setActive(request.active());
    }

    private void applyChildren(PricingRule rule,
                               List<DistanceTierRequest> tiers,
                               List<CarTypeMultiplierRequest> multipliers) {
        for (DistanceTierRequest tierRequest : tiers) {
            PricingDistanceTier tier = new PricingDistanceTier();
            tier.setFromKm(tierRequest.fromKm());
            tier.setToKm(tierRequest.toKm());
            tier.setRatePerKm(tierRequest.ratePerKm());
            rule.addDistanceTier(tier);
        }
        for (CarTypeMultiplierRequest multiplierRequest : multipliers) {
            PricingCarTypeMultiplier multiplier = new PricingCarTypeMultiplier();
            multiplier.setCarType(multiplierRequest.carType());
            multiplier.setMultiplier(multiplierRequest.multiplier());
            rule.addCarTypeMultiplier(multiplier);
        }
    }

    /**
     * A tier set has to describe the distance line exactly once: a gap would
     * leave kilometres unbilled and an overlap would bill them twice. Returns
     * the tiers in billing order.
     */
    private List<DistanceTierRequest> validatedTiers(List<DistanceTierRequest> tiers) {
        List<DistanceTierRequest> sorted = tiers.stream()
                .sorted(Comparator.comparing(DistanceTierRequest::fromKm))
                .toList();

        if (sorted.get(0).fromKm().signum() != 0) {
            throw invalid("The lowest distance tier must start at 0 km");
        }
        for (int i = 0; i < sorted.size(); i++) {
            DistanceTierRequest tier = sorted.get(i);
            if (tier.toKm() == null) {
                if (i != sorted.size() - 1) {
                    throw invalid("Only the highest distance tier may be open ended");
                }
            } else if (tier.toKm().compareTo(tier.fromKm()) <= 0) {
                throw invalid("Distance tier " + tier.fromKm() + " - " + tier.toKm() + " ends before it starts");
            }
        }
        for (int i = 1; i < sorted.size(); i++) {
            // Every earlier tier is closed here: only the last one may be open ended.
            BigDecimal previousTo = sorted.get(i - 1).toKm();
            BigDecimal currentFrom = sorted.get(i).fromKm();
            if (previousTo.compareTo(currentFrom) != 0) {
                throw invalid("Distance tiers must be contiguous and must not overlap: expected the next tier to "
                        + "start at " + previousTo + " km but it starts at " + currentFrom + " km");
            }
        }
        return sorted;
    }

    private void validateCarTypeMultipliers(List<CarTypeMultiplierRequest> multipliers) {
        Set<CarType> seen = EnumSet.noneOf(CarType.class);
        for (CarTypeMultiplierRequest multiplier : multipliers) {
            if (!seen.add(multiplier.carType())) {
                throw invalid("Duplicate multiplier for car type " + multiplier.carType());
            }
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PRICING_RULE_INVALID, message);
    }

    private PricingRuleResponse toResponse(PricingRule rule) {
        List<DistanceTierResponse> tiers = rule.getDistanceTiers().stream()
                .sorted(Comparator.comparing(PricingDistanceTier::getFromKm))
                .map(tier -> new DistanceTierResponse(tier.getId(), tier.getFromKm(), tier.getToKm(), tier.getRatePerKm()))
                .toList();
        List<CarTypeMultiplierResponse> multipliers = rule.getCarTypeMultipliers().stream()
                .sorted(Comparator.comparing(PricingCarTypeMultiplier::getCarType))
                .map(multiplier -> new CarTypeMultiplierResponse(multiplier.getId(), multiplier.getCarType(),
                        multiplier.getMultiplier()))
                .toList();
        return new PricingRuleResponse(rule.getId(), rule.getCode(), rule.getName(), rule.getMinimumFare(),
                rule.getSurgeMultiplier(), rule.isActive(), tiers, multipliers,
                rule.getUpdatedAt(), rule.getUpdatedBy());
    }
}
