-- ---------------------------------------------------------------------------
-- V5 : Ride module.
-- rides carries a full immutable pricing snapshot: changing pricing_schema
-- later must never alter the fare of a historical ride.
-- ---------------------------------------------------------------------------

CREATE TABLE ride_schema.rides (
    id                   BIGINT         NOT NULL AUTO_INCREMENT,
    user_id              BIGINT         NOT NULL COMMENT 'user_schema.users.id - intentionally not a FK',
    driver_id            BIGINT         NULL     COMMENT 'driver_schema.drivers.id - intentionally not a FK',
    vehicle_id           BIGINT         NULL     COMMENT 'driver_schema.vehicles.id - intentionally not a FK',
    status               VARCHAR(20)    NOT NULL,

    requested_car_type   VARCHAR(20)    NOT NULL,
    assigned_car_type    VARCHAR(20)    NULL,

    pickup_latitude      DECIMAL(10, 7) NOT NULL,
    pickup_longitude     DECIMAL(10, 7) NOT NULL,
    pickup_address       VARCHAR(255)   NULL,
    drop_latitude        DECIMAL(10, 7) NOT NULL,
    drop_longitude       DECIMAL(10, 7) NOT NULL,
    drop_address         VARCHAR(255)   NULL,
    distance_km          DECIMAL(8, 3)  NOT NULL,

    -- Pricing snapshot -------------------------------------------------------
    pricing_rule_code    VARCHAR(40)    NOT NULL,
    distance_fare        DECIMAL(10, 2) NOT NULL,
    car_type_multiplier  DECIMAL(4, 2)  NOT NULL,
    surge_multiplier     DECIMAL(4, 2)  NOT NULL DEFAULT 1.00,
    minimum_fare         DECIMAL(10, 2) NOT NULL,
    minimum_fare_applied TINYINT(1)     NOT NULL DEFAULT 0,
    fare_before_discount DECIMAL(10, 2) NOT NULL,
    coupon_code          VARCHAR(40)    NULL,
    discount_amount      DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    total_fare           DECIMAL(10, 2) NOT NULL,
    fare_breakdown       JSON           NULL,

    requested_at         DATETIME(6)    NOT NULL,
    assigned_at          DATETIME(6)    NULL,
    started_at           DATETIME(6)    NULL,
    completed_at         DATETIME(6)    NULL,
    cancelled_at         DATETIME(6)    NULL,
    cancelled_by         VARCHAR(20)    NULL,
    cancellation_reason  VARCHAR(255)   NULL,

    created_at           DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by           VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    updated_at           DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by           VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    version              BIGINT         NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    -- GET /users/{id}/rides : newest first.
    KEY idx_rides_user (user_id, requested_at DESC),
    -- GET /drivers/{id}/rides : newest first.
    KEY idx_rides_driver (driver_id, requested_at DESC),
    CONSTRAINT ck_rides_status CHECK (status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'STARTED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_rides_requested_car_type CHECK (requested_car_type IN ('SEDAN', 'HATCHBACK')),
    CONSTRAINT ck_rides_assigned_car_type CHECK (assigned_car_type IS NULL OR assigned_car_type IN ('SEDAN', 'HATCHBACK')),
    CONSTRAINT ck_rides_distance CHECK (distance_km > 0),
    CONSTRAINT ck_rides_discount CHECK (discount_amount >= 0),
    CONSTRAINT ck_rides_total_fare CHECK (total_fare >= 0),
    CONSTRAINT ck_rides_cancelled_by CHECK (cancelled_by IS NULL OR cancelled_by IN ('USER', 'DRIVER', 'ADMIN', 'SYSTEM'))
) ENGINE = InnoDB;

-- Idempotent ride creation. The unique key is what actually prevents a
-- duplicate ride when a client retries POST /api/v1/rides.
CREATE TABLE ride_schema.idempotency_keys (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    ride_id         BIGINT       NULL,
    response_body   TEXT         NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_user_key (user_id, idempotency_key),
    -- Housekeeping sweep of expired records.
    KEY idx_idempotency_expires (expires_at),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
) ENGINE = InnoDB;
