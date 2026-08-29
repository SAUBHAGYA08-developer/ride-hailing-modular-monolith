package com.ridehailing.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.audit.dto.AuditLogResponse;
import com.ridehailing.audit.entity.AuditLog;
import com.ridehailing.audit.repository.AuditLogRepository;
import com.ridehailing.common.api.PageResponse;
import com.ridehailing.common.web.RequestContext;
import com.ridehailing.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Writes the business audit trail.
 *
 * The write runs in its own transaction and never propagates a failure: an
 * audit problem must not roll back or fail a completed business operation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String entityType, Object entityId, String action, Object oldValue, Object newValue) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setEntityType(entityType);
            auditLog.setEntityId(String.valueOf(entityId));
            auditLog.setAction(action);
            auditLog.setOldValue(serialize(oldValue));
            auditLog.setNewValue(serialize(newValue));
            auditLog.setChangedBy(CurrentUser.actorName());
            auditLog.setChangedAt(Instant.now());
            auditLog.setRequestId(RequestContext.requestId());
            auditLog.setIpAddress(RequestContext.clientIp());
            auditLogRepository.save(auditLog);
        } catch (RuntimeException ex) {
            log.error("Failed to write audit record {}/{} {}", entityType, entityId, action, ex);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(String entityType, String entityId, String requestId, Pageable pageable) {
        if (StringUtils.hasText(requestId)) {
            return PageResponse.from(auditLogRepository.findByRequestIdOrderByChangedAtDesc(requestId, pageable), this::toResponse);
        }
        if (StringUtils.hasText(entityType) && StringUtils.hasText(entityId)) {
            return PageResponse.from(
                    auditLogRepository.findByEntityTypeAndEntityIdOrderByChangedAtDesc(entityType, entityId, pageable),
                    this::toResponse);
        }
        if (StringUtils.hasText(entityType)) {
            return PageResponse.from(auditLogRepository.findByEntityTypeOrderByChangedAtDesc(entityType, pageable), this::toResponse);
        }
        return PageResponse.from(auditLogRepository.findAll(pageable), this::toResponse);
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getEntityType(), log.getEntityId(), log.getAction(),
                log.getOldValue(), log.getNewValue(), log.getChangedBy(), log.getChangedAt(),
                log.getRequestId(), log.getIpAddress());
    }

    private String serialize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Could not serialise audit payload of type {}", value.getClass().getName(), ex);
            return String.valueOf(value);
        }
    }
}
