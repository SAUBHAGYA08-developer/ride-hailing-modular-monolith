package com.ridehailing.pricing.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.pricing.dto.PricingZoneRequest;
import com.ridehailing.pricing.dto.PricingZoneResponse;
import com.ridehailing.pricing.entity.PricingRule;
import com.ridehailing.pricing.entity.PricingZone;
import com.ridehailing.pricing.repository.PricingRuleRepository;
import com.ridehailing.pricing.repository.PricingZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin management of pricing zones.
 *
 * Redrawing a zone never changes a historical fare: the ride stored the zone
 * code and the whole pricing snapshot at booking time.
 */
@Service
@RequiredArgsConstructor
public class PricingZoneService {

    private final PricingZoneRepository pricingZoneRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<PricingZoneResponse> findAll() {
        return pricingZoneRepository.findAllWithRules().stream().map(this::toResponse).toList();
    }

    @Transactional
    public PricingZoneResponse create(PricingZoneRequest request) {
        String code = normalise(request.code());
        if (pricingZoneRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.PRICING_RULE_ALREADY_EXISTS,
                    "Pricing zone " + code + " already exists");
        }
        PricingZone zone = apply(new PricingZone(), request, code);
        PricingZone saved = pricingZoneRepository.save(zone);
        auditService.record(AuditEntities.PRICING_RULE, "zone:" + saved.getCode(),
                AuditActions.PRICING_RULE_CREATED, null, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public PricingZoneResponse update(Long id, PricingZoneRequest request) {
        PricingZone zone = pricingZoneRepository.findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.PRICING_RULE_NOT_FOUND, "Pricing zone " + id + " does not exist"));

        String code = normalise(request.code());
        if (!zone.getCode().equals(code) && pricingZoneRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.PRICING_RULE_ALREADY_EXISTS,
                    "Pricing zone " + code + " already exists");
        }
        Map<String, Object> before = snapshot(zone);
        PricingZone saved = pricingZoneRepository.saveAndFlush(apply(zone, request, code));
        auditService.record(AuditEntities.PRICING_RULE, "zone:" + saved.getCode(),
                AuditActions.PRICING_RULE_CHANGED, before, snapshot(saved));
        return toResponse(saved);
    }

    private PricingZone apply(PricingZone zone, PricingZoneRequest request, String code) {
        PricingRule rule = pricingRuleRepository.findByCode(normalise(request.pricingRuleCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRICING_RULE_NOT_FOUND,
                        "Pricing rule " + request.pricingRuleCode() + " does not exist"));
        zone.setCode(code);
        zone.setName(request.name().trim());
        zone.setPricingRule(rule);
        zone.setCentreLatitude(request.centreLatitude());
        zone.setCentreLongitude(request.centreLongitude());
        zone.setRadiusKm(request.radiusKm());
        zone.setPriority(request.priority());
        zone.setActive(request.active());
        return zone;
    }

    private Map<String, Object> snapshot(PricingZone zone) {
        return Map.of("code", zone.getCode(),
                "pricingRuleCode", zone.getPricingRule().getCode(),
                "centreLatitude", zone.getCentreLatitude(),
                "centreLongitude", zone.getCentreLongitude(),
                "radiusKm", zone.getRadiusKm(),
                "priority", zone.getPriority(),
                "active", zone.isActive());
    }

    private String normalise(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private PricingZoneResponse toResponse(PricingZone zone) {
        return new PricingZoneResponse(zone.getId(), zone.getCode(), zone.getName(),
                zone.getPricingRule().getCode(), zone.getCentreLatitude(), zone.getCentreLongitude(),
                zone.getRadiusKm(), zone.getPriority(), zone.isActive(), zone.getUpdatedAt(), zone.getUpdatedBy());
    }
}
