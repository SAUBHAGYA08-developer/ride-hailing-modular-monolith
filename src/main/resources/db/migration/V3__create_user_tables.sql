-- ---------------------------------------------------------------------------
-- V3 : User module.
-- users is the single identity store for every principal in the platform
-- (ADMIN, USER, DRIVER). A driver profile in driver_schema points back here
-- through user_id, without a foreign key.
-- ---------------------------------------------------------------------------

CREATE TABLE user_schema.users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(150) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by    VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by    VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_phone (phone),
    CONSTRAINT ck_users_role   CHECK (role IN ('ADMIN', 'USER', 'DRIVER')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
) ENGINE = InnoDB;
