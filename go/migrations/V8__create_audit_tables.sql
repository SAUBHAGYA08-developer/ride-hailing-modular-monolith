-- ---------------------------------------------------------------------------
-- V8 : Audit module.
-- Business level audit trail only. Technical noise is deliberately excluded.
-- ---------------------------------------------------------------------------

CREATE TABLE audit_schema.audit_logs (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(60)  NOT NULL,
    entity_id   VARCHAR(64)  NOT NULL,
    action      VARCHAR(60)  NOT NULL,
    old_value   TEXT         NULL,
    new_value   TEXT         NULL,
    changed_by  VARCHAR(120) NOT NULL,
    changed_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    request_id  VARCHAR(64)  NULL,
    ip_address  VARCHAR(45)  NULL,
    PRIMARY KEY (id),
    -- "what happened to this entity" : the dominant audit query.
    KEY idx_audit_entity (entity_type, entity_id, changed_at),
    -- "what did this request do" : incident investigation.
    KEY idx_audit_request (request_id)
) ENGINE = InnoDB;
