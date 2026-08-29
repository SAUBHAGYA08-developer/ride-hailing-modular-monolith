-- ---------------------------------------------------------------------------
-- V15 : City based pricing.
--
-- A zone is a circle (centre + radius) that maps a pickup point to a pricing
-- rule. A circle is deliberate: it needs no spatial index or GEOMETRY type,
-- and city level pricing does not need street accurate boundaries. Overlaps
-- are resolved by priority, so a small high priority zone (an airport) can sit
-- inside a large city zone later without changing any Java.
--
-- The pickup decides the zone, never the drop: the pickup is fixed for the
-- whole life of the ride, so the fare cannot change under the rider.
-- ---------------------------------------------------------------------------

CREATE TABLE pricing_schema.pricing_zones (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    code             VARCHAR(40)    NOT NULL,
    name             VARCHAR(100)   NOT NULL,
    pricing_rule_id  BIGINT         NOT NULL,
    centre_latitude  DECIMAL(10, 7) NOT NULL,
    centre_longitude DECIMAL(10, 7) NOT NULL,
    radius_km        DECIMAL(7, 2)  NOT NULL,
    priority         INT            NOT NULL DEFAULT 0 COMMENT 'higher wins when zones overlap',
    active           TINYINT(1)     NOT NULL DEFAULT 1,
    created_at       DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by       VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    updated_at       DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by       VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    version          BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pricing_zones_code (code),
    -- Resolution reads every active zone once per quote; the row count is tiny
    -- (one per city) so this index is enough and a spatial index is not.
    KEY idx_pricing_zones_active (active, priority),
    CONSTRAINT fk_pricing_zones_rule
        FOREIGN KEY (pricing_rule_id) REFERENCES pricing_schema.pricing_rules (id),
    CONSTRAINT ck_pricing_zones_latitude  CHECK (centre_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_pricing_zones_longitude CHECK (centre_longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_pricing_zones_radius    CHECK (radius_km > 0)
) ENGINE = InnoDB;

-- The zone that priced a ride is part of the immutable snapshot: redrawing a
-- zone later must not change what a historical ride was charged under.
ALTER TABLE ride_schema.rides
    ADD COLUMN pricing_zone_code VARCHAR(40) NULL AFTER pricing_rule_code;

-- --------------------------- city pricing rules ----------------------------

INSERT INTO pricing_schema.pricing_rules (code, name, minimum_fare, surge_multiplier, active) VALUES
    ('DELHI_STANDARD', 'Delhi Standard Pricing', 60.00, 1.00, 1),
    ('PUNE_STANDARD',  'Pune Standard Pricing',  45.00, 1.00, 1);

INSERT INTO pricing_schema.pricing_distance_tiers (pricing_rule_id, from_km, to_km, rate_per_km)
SELECT r.id, t.from_km, t.to_km, t.rate_per_km
FROM pricing_schema.pricing_rules r
         JOIN (SELECT 'DELHI_STANDARD' AS code, 0.00 AS from_km, 2.00 AS to_km, 12.00 AS rate_per_km
               UNION ALL SELECT 'DELHI_STANDARD', 2.00, 5.00,  9.00
               UNION ALL SELECT 'DELHI_STANDARD', 5.00, NULL,  6.00
               UNION ALL SELECT 'PUNE_STANDARD',  0.00, 2.00,  9.00
               UNION ALL SELECT 'PUNE_STANDARD',  2.00, 5.00,  7.00
               UNION ALL SELECT 'PUNE_STANDARD',  5.00, NULL,  5.00) t
              ON t.code = r.code;

INSERT INTO pricing_schema.pricing_car_type_multipliers (pricing_rule_id, car_type, multiplier)
SELECT r.id, m.car_type, m.multiplier
FROM pricing_schema.pricing_rules r
         JOIN (SELECT 'DELHI_STANDARD' AS code, 'SEDAN' AS car_type, 1.00 AS multiplier
               UNION ALL SELECT 'DELHI_STANDARD', 'HATCHBACK', 0.90
               UNION ALL SELECT 'PUNE_STANDARD',  'SEDAN',     1.00
               UNION ALL SELECT 'PUNE_STANDARD',  'HATCHBACK', 0.90) m
              ON m.code = r.code;

-- -------------------------------- city zones -------------------------------
-- Bangalore keeps the existing STANDARD rule, so seeded demo fares are
-- unchanged. Radii are generous enough to cover each metropolitan area.

INSERT INTO pricing_schema.pricing_zones
    (code, name, pricing_rule_id, centre_latitude, centre_longitude, radius_km, priority, active)
SELECT z.code, z.name, r.id, z.centre_latitude, z.centre_longitude, z.radius_km, z.priority, 1
FROM (SELECT 'BANGALORE' AS code, 'Bengaluru' AS name, 'STANDARD'       AS rule_code, 12.9716000 AS centre_latitude, 77.5946000 AS centre_longitude, 40.00 AS radius_km, 100 AS priority
      UNION ALL SELECT 'DELHI', 'Delhi NCR', 'DELHI_STANDARD', 28.6139000, 77.2090000, 50.00, 100
      UNION ALL SELECT 'PUNE',  'Pune',      'PUNE_STANDARD',  18.5204000, 73.8567000, 30.00, 100) z
         JOIN pricing_schema.pricing_rules r ON r.code = z.rule_code;
