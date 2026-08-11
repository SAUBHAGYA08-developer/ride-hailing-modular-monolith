-- ---------------------------------------------------------------------------
-- V22 : Stale ride tracking.
--
-- A driver whose app is killed mid-ride never calls the API again, so the ride
-- sits in DRIVER_ASSIGNED or STARTED forever and the driver stays BUSY. Redis
-- already knows they are gone - the freshness marker behind
-- driver.location.ttl.seconds expired - and until now nothing acted on it.
--
-- stale_flagged_at is a claim marker, not a report field: the conditional
-- UPDATE that stamps it only matches while it IS NULL, so two application
-- instances sweeping the same ride cannot both resolve it, and a STARTED ride
-- is surfaced exactly once rather than re-audited every minute forever.
-- ---------------------------------------------------------------------------

ALTER TABLE ride_schema.rides
    ADD COLUMN stale_flagged_at DATETIME(6) NULL AFTER cancellation_fee;

-- The sweep runs every minute, so it must not scan completed history to find the
-- handful of open rides. Only the status prefix is selective - the age predicate
-- is COALESCE(started_at, assigned_at), because the clock that matters is when
-- the ride entered its current state - and that prefix is the whole point: open
-- rides are a tiny slice of the table.
CREATE INDEX idx_rides_stale_sweep ON ride_schema.rides (status, assigned_at);

-- ------------------------------- reaper policy ------------------------------
-- The grace window is 15 minutes, not the 60 second location TTL. Redis absence
-- alone is a network blip; absence sustained far past the TTL is a dead app.
--
-- Enabled by default but switchable at runtime through PUT /configurations/{key},
-- so a demo can freeze the world without a redeploy. The batch cap bounds one
-- sweep: a bad night must not let a single run walk the whole table.

INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('ride.reaper.enabled',      'true', 'BOOLEAN', 'Whether the stale ride reaper resolves rides whose driver has gone dark'),
    ('ride.stale.grace.seconds', '900',  'INTEGER', 'Seconds a ride may sit in DRIVER_ASSIGNED or STARTED with an absent driver before it is reaped'),
    ('ride.reaper.batch.size',   '50',   'INTEGER', 'Maximum rides one reaper sweep may resolve');
