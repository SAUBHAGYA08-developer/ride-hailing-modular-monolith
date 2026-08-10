-- ---------------------------------------------------------------------------
-- V2 : RBAC module.
-- Roles and fine grained permissions. Users reference a role by CODE (not by
-- id) because users live in another schema and cross-schema FKs are forbidden.
-- ---------------------------------------------------------------------------

CREATE TABLE rbac_schema.roles (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255) NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by  VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by  VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_code (code)
) ENGINE = InnoDB;

CREATE TABLE rbac_schema.permissions (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(60)  NOT NULL,
    resource    VARCHAR(40)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    description VARCHAR(255) NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by  VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by  VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permissions_code (code),
    UNIQUE KEY uk_permissions_resource_action (resource, action)
) ENGINE = InnoDB;

CREATE TABLE rbac_schema.role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_permissions_permission (permission_id),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES rbac_schema.roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES rbac_schema.permissions (id) ON DELETE CASCADE
) ENGINE = InnoDB;
