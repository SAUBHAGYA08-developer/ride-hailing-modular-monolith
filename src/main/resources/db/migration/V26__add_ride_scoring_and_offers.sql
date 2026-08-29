-- V26 : real offer/accept/decline dispatch (prerequisite for driver scoring's
-- acceptance-rate signal) plus the scoring strategy's tunable weights.

-- The remaining ranked candidates not yet offered for a REQUESTED ride, so a
-- decline or a timeout can advance to the next one without re-ranking.
ALTER TABLE ride_schema.rides
    ADD COLUMN candidate_queue JSON NULL AFTER assigned_car_type;

CREATE TABLE ride_schema.ride_offers (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    ride_id      BIGINT       NOT NULL,
    driver_id    BIGINT       NOT NULL COMMENT 'driver_schema.drivers.id - intentionally not a FK',
    vehicle_id   BIGINT       NOT NULL COMMENT 'driver_schema.vehicles.id - intentionally not a FK',
    car_type     VARCHAR(20)  NOT NULL,
    sequence     INT          NOT NULL COMMENT 'position in the ranked candidate list, for audit/debugging',
    pickup_distance_km DECIMAL(6, 2) NOT NULL COMMENT 'straight-line km at ranking time, same approximation as rides.driver_pickup_distance_km',
    status       VARCHAR(20)  NOT NULL COMMENT 'PENDING, ACCEPTED, DECLINED or EXPIRED',
    offered_at   DATETIME(6)  NOT NULL,
    expires_at   DATETIME(6)  NOT NULL,
    responded_at DATETIME(6)  NULL,

    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by   VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by   VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    version      BIGINT       NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    -- One ride has at most one PENDING offer at a time; this is the lookup both the accept/decline path and the reaper use.
    KEY idx_ride_offers_ride_status (ride_id, status),
    -- The acceptance-rate aggregate scans a driver's history ordered by nothing in particular, so an index on the pair is enough.
    KEY idx_ride_offers_driver_status (driver_id, status),
    -- The reaper's sweep: due offers regardless of which ride they belong to.
    KEY idx_ride_offers_status_expires (status, expires_at),
    CONSTRAINT fk_ride_offers_ride FOREIGN KEY (ride_id) REFERENCES ride_schema.rides (id) ON DELETE CASCADE,
    CONSTRAINT ck_ride_offers_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED'))
) ENGINE = InnoDB;

INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('ride.offer.timeout.seconds',        '15',   'INTEGER', 'How long a driver has to accept or decline an offered ride'),
    ('ride.offer.reaper.enabled',         'true', 'BOOLEAN', 'Whether the offer-expiry sweep runs at all'),
    ('ride.offer.reaper.batch.size',      '50',   'INTEGER', 'Max expired offers advanced in one sweep'),
    ('matching.score.weight.eta',         '0.4',  'DECIMAL', 'SCORE strategy weight for pickup ETA (lower ETA is better)'),
    ('matching.score.weight.rating',      '0.3',  'DECIMAL', 'SCORE strategy weight for driver rating'),
    ('matching.score.weight.acceptance',  '0.3',  'DECIMAL', 'SCORE strategy weight for driver acceptance rate');

INSERT INTO rbac_schema.permissions (code, resource, action, description) VALUES
    ('RIDE_OFFER_RESPOND', 'rides', 'OFFER_RESPOND', 'Accept or decline an offered ride');

INSERT INTO rbac_schema.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_schema.roles r
         JOIN rbac_schema.permissions p
              ON p.code = 'RIDE_OFFER_RESPOND'
WHERE r.code IN ('ADMIN', 'DRIVER');
