-- ---------------------------------------------------------------------------
-- V11 : Pricing master data (STANDARD).
-- Minimum fare 50. Tiers 0-2 km @ 10/km, 2-5 km @ 8/km, 5+ km @ 5/km.
-- Car type factors SEDAN 1.0, HATCHBACK 0.9.
-- ---------------------------------------------------------------------------

INSERT INTO pricing_schema.pricing_rules (code, name, minimum_fare, surge_multiplier, active)
VALUES ('STANDARD', 'Standard Pricing', 50.00, 1.00, 1);

INSERT INTO pricing_schema.pricing_distance_tiers (pricing_rule_id, from_km, to_km, rate_per_km)
SELECT r.id, t.from_km, t.to_km, t.rate_per_km
FROM pricing_schema.pricing_rules r
         JOIN (SELECT 0.00 AS from_km, 2.00 AS to_km, 10.00 AS rate_per_km
               UNION ALL SELECT 2.00, 5.00, 8.00
               UNION ALL SELECT 5.00, NULL, 5.00) t
WHERE r.code = 'STANDARD';

INSERT INTO pricing_schema.pricing_car_type_multipliers (pricing_rule_id, car_type, multiplier)
SELECT r.id, m.car_type, m.multiplier
FROM pricing_schema.pricing_rules r
         JOIN (SELECT 'SEDAN' AS car_type, 1.00 AS multiplier
               UNION ALL SELECT 'HATCHBACK', 0.90) m
WHERE r.code = 'STANDARD';
