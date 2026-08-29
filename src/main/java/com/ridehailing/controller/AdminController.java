package com.ridehailing.controller;

import com.ridehailing.audit.dto.AuditLogResponse;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.common.api.PageResponse;
import com.ridehailing.ratelimit.RateLimitPolicy;
import com.ridehailing.ratelimit.RateLimited;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditService auditService;

    @GetMapping("/audit-logs")
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    @RateLimited(RateLimitPolicy.ADMIN_API)
    public ApiResponse<PageResponse<AuditLogResponse>> auditLogs(@RequestParam(required = false) String entityType,
                                                                 @RequestParam(required = false) String entityId,
                                                                 @RequestParam(required = false) String requestId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.search(entityType, entityId, requestId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));
    }
}
