-- V23 : configurable surcharges (long distance, night, rain) applied on top of the fare.

CREATE TABLE pricing_schema.pricing_surcharges (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    -- NULL means every rule: a city wide rain fee needs no row per rule.
    pricing_rule_id  BIGINT         NULL,
    code             VARCHAR(40)    NOT NULL,
    name             VARCHAR(100)   NOT NULL,
    charge_type      VARCHAR(10)    NOT NULL COMMENT 'FLAT or PERCENT',
    amount           DECIMAL(10, 2) NOT NULL,
    -- Distance window in km; NULL ends are open, so a long distance fee is just min_distance_km.
    min_distance_km  DECIMAL(7, 2)  NULL,
    max_distance_km  DECIMAL(7, 2)  NULL,
    -- NULL applies to every category.
    car_type         VARCHAR(20)    NULL,
    -- Local time window; a window that wraps midnight is allowed and handled in Java.
    active_from_time TIME           NULL,
    active_to_time   TIME           NULL,
    priority         INT            NOT NULL DEFAULT 0 COMMENT 'lower applies first, and orders the breakdown',
    active           TINYINT(1)     NOT NULL DEFAULT 1,
    created_at       DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by       VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    updated_at       DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by       VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    version          BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pricing_surcharge_rule_code (pricing_rule_id, code),
    KEY idx_pricing_surcharge_active (active, priority),
    CONSTRAINT fk_pricing_surcharge_rule
        FOREIGN KEY (pricing_rule_id) REFERENCES pricing_schema.pricing_rules (id) ON DELETE CASCADE,
    CONSTRAINT ck_pricing_surcharge_type CHECK (charge_type IN ('FLAT', 'PERCENT')),
    CONSTRAINT ck_pricing_surcharge_amount CHECK (amount >= 0),
    CONSTRAINT ck_pricing_surcharge_car_type
        CHECK (car_type IS NULL OR car_type IN ('SEDAN', 'HATCHBACK', 'AUTO', 'BIKE')),
    CONSTRAINT ck_pricing_surcharge_distance
        CHECK (min_distance_km IS NULL OR max_distance_km IS NULL OR max_distance_km > min_distance_km)
) ENGINE = InnoDB;

-- Rule-agnostic seeds. RAIN_FEE ships inactive: an operator turns it on when it actually rains.
INSERT INTO pricing_schema.pricing_surcharges
    (pricing_rule_id, code, name, charge_type, amount, min_distance_km, active_from_time, active_to_time, priority, active) VALUES
    (NULL, 'LONG_DISTANCE_FEE', 'Long distance fee', 'FLAT',    30.00, 15.00, NULL,       NULL,       10, 1),
    (NULL, 'NIGHT_FEE',         'Night fee',         'PERCENT', 10.00, NULL,  '23:00:00', '05:00:00', 20, 1),
    (NULL, 'RAIN_FEE',          'Rain fee',          'FLAT',    20.00, NULL,  NULL,       NULL,       30, 0);

INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('pricing.surcharges.enabled', 'true', 'BOOLEAN', 'Whether configured surcharges are applied to a fare'),
    ('payment.cash.handling.fee',  '0.00', 'DECIMAL', 'Collected as a separate CASH_HANDLING_FEE payment line when a ride is paid in cash');
