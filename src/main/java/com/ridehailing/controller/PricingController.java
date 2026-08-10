package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.pricing.dto.PricingRuleRequest;
import com.ridehailing.pricing.dto.PricingZoneRequest;
import com.ridehailing.pricing.dto.PricingZoneResponse;
import com.ridehailing.pricing.dto.PricingRuleResponse;
import com.ridehailing.pricing.service.PricingRuleService;
import com.ridehailing.pricing.service.PricingZoneService;
import com.ridehailing.ratelimit.RateLimitPolicy;
import com.ridehailing.ratelimit.RateLimited;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final PricingRuleService pricingRuleService;
    private final PricingZoneService pricingZoneService;

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('PRICING_READ')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ApiResponse<List<PricingRuleResponse>> findAll() {
        return ApiResponse.ok(pricingRuleService.findAll());
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAuthority('PRICING_CREATE')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ResponseEntity<ApiResponse<PricingRuleResponse>> create(@Valid @RequestBody PricingRuleRequest request) {
        PricingRuleResponse created = pricingRuleService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('PRICING_UPDATE')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ApiResponse<PricingRuleResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody PricingRuleRequest request) {
        return ApiResponse.ok(pricingRuleService.update(id, request));
    }

    /**
     * Zones map a pickup point to a pricing rule, so Delhi, Pune and Bengaluru
     * can charge different rates without any code change.
     */
    @GetMapping("/zones")
    @PreAuthorize("hasAuthority('PRICING_READ')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ApiResponse<List<PricingZoneResponse>> findAllZones() {
        return ApiResponse.ok(pricingZoneService.findAll());
    }

    @PostMapping("/zones")
    @PreAuthorize("hasAuthority('PRICING_CREATE')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ResponseEntity<ApiResponse<PricingZoneResponse>> createZone(@Valid @RequestBody PricingZoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(pricingZoneService.create(request)));
    }

    @PutMapping("/zones/{id}")
    @PreAuthorize("hasAuthority('PRICING_UPDATE')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ApiResponse<PricingZoneResponse> updateZone(@PathVariable Long id,
                                                       @Valid @RequestBody PricingZoneRequest request) {
        return ApiResponse.ok(pricingZoneService.update(id, request));
    }
}
