-- ---------------------------------------------------------------------------
-- V20 : How far the assigned driver was from the pickup.
-- Straight-line haversine km from the Redis GEO search, snapshotted at
-- assignment - not road distance, and not live.
-- ---------------------------------------------------------------------------

-- Nullable: a ride created by any path without a candidate distance must stay valid.
ALTER TABLE ride_schema.rides
    ADD COLUMN driver_pickup_distance_km DECIMAL(6, 2) NULL AFTER assigned_car_type,
    ADD CONSTRAINT ck_rides_driver_pickup_distance
        CHECK (driver_pickup_distance_km IS NULL OR driver_pickup_distance_km >= 0);

-- The ETA is derived on read rather than stored: unlike the fare, nobody is held to it.
INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('ride.pickup.average.speed.kmph', '20', 'INTEGER', 'Average city speed used to turn the pickup distance into an ETA');
