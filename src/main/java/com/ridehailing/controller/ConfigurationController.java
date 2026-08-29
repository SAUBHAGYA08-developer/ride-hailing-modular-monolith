package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.configuration.dto.ConfigurationResponse;
import com.ridehailing.configuration.dto.UpdateConfigurationRequest;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.ratelimit.RateLimitPolicy;
import com.ridehailing.ratelimit.RateLimited;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/configurations")
@RequiredArgsConstructor
public class ConfigurationController {

    private final ConfigurationService configurationService;

    @GetMapping
    @PreAuthorize("hasAuthority('CONFIGURATION_READ')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ApiResponse<List<ConfigurationResponse>> findAll() {
        return ApiResponse.ok(configurationService.findAll());
    }

    @GetMapping("/{key}")
    @PreAuthorize("hasAuthority('CONFIGURATION_READ')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ApiResponse<ConfigurationResponse> findByKey(@PathVariable String key) {
        return ApiResponse.ok(configurationService.findByKey(key));
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasAuthority('CONFIGURATION_UPDATE')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ApiResponse<ConfigurationResponse> update(@PathVariable String key,
                                                     @Valid @RequestBody UpdateConfigurationRequest request) {
        return ApiResponse.ok(configurationService.update(key, request.value()));
    }
}
