-- V21 : AUTO and BIKE vehicle categories.

-- MySQL cannot modify a CHECK in place; it has to be dropped and re-added.
ALTER TABLE driver_schema.vehicles
    DROP CHECK ck_vehicles_car_type;
ALTER TABLE driver_schema.vehicles
    ADD CONSTRAINT ck_vehicles_car_type
        CHECK (car_type IN ('SEDAN', 'HATCHBACK', 'AUTO', 'BIKE'));

ALTER TABLE ride_schema.rides
    DROP CHECK ck_rides_requested_car_type;
ALTER TABLE ride_schema.rides
    ADD CONSTRAINT ck_rides_requested_car_type
        CHECK (requested_car_type IN ('SEDAN', 'HATCHBACK', 'AUTO', 'BIKE'));

ALTER TABLE ride_schema.rides
    DROP CHECK ck_rides_assigned_car_type;
ALTER TABLE ride_schema.rides
    ADD CONSTRAINT ck_rides_assigned_car_type
        CHECK (assigned_car_type IS NULL
            OR assigned_car_type IN ('SEDAN', 'HATCHBACK', 'AUTO', 'BIKE'));

ALTER TABLE pricing_schema.pricing_car_type_multipliers
    DROP CHECK ck_pricing_multiplier_car_type;
ALTER TABLE pricing_schema.pricing_car_type_multipliers
    ADD CONSTRAINT ck_pricing_multiplier_car_type
        CHECK (car_type IN ('SEDAN', 'HATCHBACK', 'AUTO', 'BIKE'));

-- Every rule needs a row: PricingService falls back to 1.00, so a missing row prices a bike as a sedan.
INSERT INTO pricing_schema.pricing_car_type_multipliers (pricing_rule_id, car_type, multiplier)
SELECT r.id, m.car_type, m.multiplier
FROM pricing_schema.pricing_rules r
         CROSS JOIN (SELECT 'AUTO' AS car_type, 0.65 AS multiplier
                     UNION ALL SELECT 'BIKE', 0.45) m
WHERE NOT EXISTS (SELECT 1
                  FROM pricing_schema.pricing_car_type_multipliers existing
                  WHERE existing.pricing_rule_id = r.id
                    AND existing.car_type = m.car_type);

-- Demo drivers inside the 5 km MG Road radius; same Driver@123 hash as V14. Kiran ~1.0 km, Suresh ~0.8 km.
INSERT INTO user_schema.users (email, password_hash, full_name, phone, role, status) VALUES
    ('kiran.rao@ridehailing.com',   '$2a$10$aGtNjwfhtiUZ0IlSMdISfOOycJtiuV8HdiuU.S16qgGPF0D6dvpJO', 'Kiran Rao',   '+919100000005', 'DRIVER', 'ACTIVE'),
    ('suresh.babu@ridehailing.com', '$2a$10$aGtNjwfhtiUZ0IlSMdISfOOycJtiuV8HdiuU.S16qgGPF0D6dvpJO', 'Suresh Babu', '+919100000006', 'DRIVER', 'ACTIVE');

INSERT INTO driver_schema.drivers
    (user_id, full_name, phone, license_number, status, rating,
     last_known_latitude, last_known_longitude, last_location_at)
SELECT u.id, d.full_name, d.phone, d.license_number, 'AVAILABLE', d.rating,
       d.latitude, d.longitude, CURRENT_TIMESTAMP(6)
FROM (SELECT 'kiran.rao@ridehailing.com'   AS email, 'Kiran Rao'   AS full_name, '+919100000005' AS phone, 'KA-DL-2022-0005' AS license_number, 4.50 AS rating, 12.9700000 AS latitude, 77.6040000 AS longitude
      UNION ALL SELECT 'suresh.babu@ridehailing.com', 'Suresh Babu', '+919100000006', 'KA-DL-2022-0006', 4.40, 12.9780000, 77.5975000) d
         JOIN user_schema.users u ON u.email = d.email;

INSERT INTO driver_schema.vehicles (driver_id, car_type, registration_number, make, model, color, active)
SELECT dr.id, v.car_type, v.registration_number, v.make, v.model, v.color, 1
FROM (SELECT 'KA-DL-2022-0005' AS license_number, 'AUTO' AS car_type, 'KA01IJ7890' AS registration_number, 'Bajaj' AS make, 'RE Compact' AS model, 'Yellow' AS color
      UNION ALL SELECT 'KA-DL-2022-0006', 'BIKE', 'KA01KL1234', 'Hero', 'Splendor', 'Black') v
         JOIN driver_schema.drivers dr ON dr.license_number = v.license_number;
