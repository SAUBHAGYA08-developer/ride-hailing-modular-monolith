-- ---------------------------------------------------------------------------
-- V9 : Configuration module.
-- BUSINESS configuration only. Infrastructure settings (datasource URL, Redis
-- URL, JWT secret, server port) stay in application config / environment.
-- ---------------------------------------------------------------------------

CREATE TABLE configuration_schema.configurations (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    config_key   VARCHAR(100) NOT NULL,
    config_value VARCHAR(500) NOT NULL,
    value_type   VARCHAR(20)  NOT NULL,
    description  VARCHAR(255) NULL,
    editable     TINYINT(1)   NOT NULL DEFAULT 1,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by   VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by   VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    version      BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_configurations_key (config_key),
    CONSTRAINT ck_configurations_value_type CHECK (value_type IN ('STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN'))
) ENGINE = InnoDB;
