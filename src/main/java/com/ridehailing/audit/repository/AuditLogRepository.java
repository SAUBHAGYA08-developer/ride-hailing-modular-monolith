package com.ridehailing.audit.repository;

import com.ridehailing.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByEntityTypeAndEntityIdOrderByChangedAtDesc(String entityType, String entityId, Pageable pageable);

    Page<AuditLog> findByEntityTypeOrderByChangedAtDesc(String entityType, Pageable pageable);

    Page<AuditLog> findByRequestIdOrderByChangedAtDesc(String requestId, Pageable pageable);
}
