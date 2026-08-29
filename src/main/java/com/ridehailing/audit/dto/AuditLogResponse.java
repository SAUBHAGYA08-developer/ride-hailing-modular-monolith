package com.ridehailing.audit.dto;

import java.time.Instant;

public record AuditLogResponse(Long id,
                               String entityType,
                               String entityId,
                               String action,
                               String oldValue,
                               String newValue,
                               String changedBy,
                               Instant changedAt,
                               String requestId,
                               String ipAddress) {
}
