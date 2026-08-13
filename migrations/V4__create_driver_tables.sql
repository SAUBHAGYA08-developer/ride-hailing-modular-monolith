-- ---------------------------------------------------------------------------
-- V4 : Driver module.
-- drivers.status is the authoritative reservation state. Redis only ever holds
-- driver GPS positions; it is never the source of truth for availability.
-- Live GPS is deliberately NOT persisted here - it would be a write hot spot.
-- ---------------------------------------------------------------------------

CREATE TABLE driver_schema.drivers (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    user_id        BIGINT        NOT NULL COMMENT 'user_schema.users.id - intentionally not a FK',
    full_name      VARCHAR(120)  NOT NULL,
    phone          VARCHAR(20)   NOT NULL,
    license_number VARCHAR(40)   NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'OFFLINE',
    rating         DECIMAL(3, 2) NOT NULL DEFAULT 5.00,
    total_rides    INT           NOT NULL DEFAULT 0,
    last_known_latitude  DECIMAL(10, 7) NULL COMMENT 'coarse snapshot only - live GPS lives in Redis',
    last_known_longitude DECIMAL(10, 7) NULL,
    last_location_at     DATETIME(6)    NULL,
    created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     VARCHAR(120)  NOT NULL DEFAULT 'SYSTEM',
    updated_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by     VARCHAR(120)  NOT NULL DEFAULT 'SYSTEM',
    version        BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_drivers_user (user_id),
    UNIQUE KEY uk_drivers_phone (phone),
    UNIQUE KEY uk_drivers_license (license_number),
    -- Booking resolves Redis candidate ids against this column on every ride.
    KEY idx_drivers_status (status),
    CONSTRAINT ck_drivers_status      CHECK (status IN ('AVAILABLE', 'BUSY', 'OFFLINE')),
    CONSTRAINT ck_drivers_rating      CHECK (rating >= 0.00 AND rating <= 5.00),
    CONSTRAINT ck_drivers_total_rides CHECK (total_rides >= 0)
) ENGINE = InnoDB;

CREATE TABLE driver_schema.vehicles (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    driver_id           BIGINT       NOT NULL,
    car_type            VARCHAR(20)  NOT NULL,
    registration_number VARCHAR(20)  NOT NULL,
    make                VARCHAR(50)  NULL,
    model               VARCHAR(50)  NULL,
    color               VARCHAR(30)  NULL,
    active              TINYINT(1)   NOT NULL DEFAULT 1,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by          VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by          VARCHAR(120) NOT NULL DEFAULT 'SYSTEM',
    version             BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vehicles_registration (registration_number),
    -- Candidate resolution joins drivers to their active vehicle by car type.
    KEY idx_vehicles_driver_active (driver_id, active, car_type),
    CONSTRAINT fk_vehicles_driver
        FOREIGN KEY (driver_id) REFERENCES driver_schema.drivers (id),
    CONSTRAINT ck_vehicles_car_type CHECK (car_type IN ('SEDAN', 'HATCHBACK'))
) ENGINE = InnoDB;
