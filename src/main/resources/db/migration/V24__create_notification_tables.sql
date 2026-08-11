-- V24 : device push tokens, so a notification has somewhere to go.

CREATE SCHEMA IF NOT EXISTS notification_schema DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE notification_schema.device_tokens (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    token        VARCHAR(255) NOT NULL,
    platform     VARCHAR(10)  NOT NULL,
    active       TINYINT(1)   NOT NULL DEFAULT 1,
    last_seen_at DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by   VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by   VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    version      BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- One row per device: re-registering the same handset must update, never accumulate.
    UNIQUE KEY uk_device_tokens_token (token),
    KEY idx_device_tokens_user (user_id, active),
    CONSTRAINT ck_device_tokens_platform CHECK (platform IN ('ANDROID', 'IOS', 'WEB'))
) ENGINE = InnoDB;

-- FCM stays off until credentials exist; the log channel keeps events observable meanwhile.
INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('notification.enabled',     'true',  'BOOLEAN', 'Whether domain events are dispatched to notification channels'),
    ('notification.fcm.enabled', 'false', 'BOOLEAN', 'Whether the Firebase channel is used; needs provider credentials in the environment');
