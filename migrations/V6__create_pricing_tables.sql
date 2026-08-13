-- ---------------------------------------------------------------------------
-- V6 : Pricing module.
-- Every fare input lives here. Nothing about pricing is hardcoded in Java.
-- ---------------------------------------------------------------------------

CREATE TABLE pricing_schema.pricing_rules (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    code             VARCHAR(40)    NOT NULL,
    name             VARCHAR(100)   NOT NULL,
    minimum_fare     DECIMAL(10, 2) NOT NULL,
    surge_multiplier DECIMAL(4, 2)  NOT NULL DEFAULT 1.00,
    active           TINYINT(1)     NOT NULL DEFAULT 1,
    created_at       DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by       VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    updated_at       DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by       VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    version          BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pricing_rules_code (code),
    CONSTRAINT ck_pricing_rules_min_fare CHECK (minimum_fare >= 0),
    CONSTRAINT ck_pricing_rules_surge CHECK (surge_multiplier >= 1.00)
) ENGINE = InnoDB;

CREATE TABLE pricing_schema.pricing_distance_tiers (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    pricing_rule_id BIGINT         NOT NULL,
    from_km         DECIMAL(6, 2)  NOT NULL,
    to_km           DECIMAL(6, 2)  NULL COMMENT 'NULL means open ended tier',
    rate_per_km     DECIMAL(10, 2) NOT NULL,
    created_at      DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    updated_at      DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by      VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    version         BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pricing_tier_rule_from (pricing_rule_id, from_km),
    CONSTRAINT fk_pricing_tier_rule
        FOREIGN KEY (pricing_rule_id) REFERENCES pricing_schema.pricing_rules (id) ON DELETE CASCADE,
    CONSTRAINT ck_pricing_tier_range CHECK (from_km >= 0 AND (to_km IS NULL OR to_km > from_km)),
    CONSTRAINT ck_pricing_tier_rate CHECK (rate_per_km >= 0)
) ENGINE = InnoDB;

CREATE TABLE pricing_schema.pricing_car_type_multipliers (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    pricing_rule_id BIGINT        NOT NULL,
    car_type        VARCHAR(20)   NOT NULL,
    multiplier      DECIMAL(4, 2) NOT NULL,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      VARCHAR(120)  NOT NULL DEFAULT 'SYSTEM',
    updated_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by      VARCHAR(120)  NOT NULL DEFAULT 'SYSTEM',
    version         BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pricing_multiplier_rule_car (pricing_rule_id, car_type),
    CONSTRAINT fk_pricing_multiplier_rule
        FOREIGN KEY (pricing_rule_id) REFERENCES pricing_schema.pricing_rules (id) ON DELETE CASCADE,
    CONSTRAINT ck_pricing_multiplier_car_type CHECK (car_type IN ('SEDAN', 'HATCHBACK')),
    CONSTRAINT ck_pricing_multiplier_value CHECK (multiplier > 0)
) ENGINE = InnoDB;
