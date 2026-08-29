-- ---------------------------------------------------------------------------
-- V18 : Payment module. A ninth schema, because money is the likeliest part to
-- be lifted into its own service later. Grants are already ON *.*, so no new
-- privilege is needed.
-- ---------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS payment_schema DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- No unique key on (ride_id, purpose): MySQL has no partial unique index, and a plain
-- one would forbid the failed attempt a retry sits beside. PaymentService enforces
-- "at most one SUCCESS" instead, and the index below makes that check one lookup.
CREATE TABLE payment_schema.payments (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    ride_id        BIGINT         NOT NULL COMMENT 'ride_schema.rides.id - intentionally not a FK',
    user_id        BIGINT         NOT NULL COMMENT 'user_schema.users.id - intentionally not a FK',
    driver_id      BIGINT         NULL     COMMENT 'driver_schema.drivers.id - intentionally not a FK',
    purpose        VARCHAR(20)    NOT NULL COMMENT 'RIDE_FARE or CANCELLATION_FEE',
    method         VARCHAR(20)    NOT NULL COMMENT 'CASH, UPI, CARD, WALLET or NETBANKING',
    amount         DECIMAL(10, 2) NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    reference      VARCHAR(64)    NULL     COMMENT 'partner handle, NULL on a decline',
    failure_reason VARCHAR(255)   NULL,
    collected_at   DATETIME(6)    NOT NULL,

    created_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by     VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    updated_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by     VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    version        BIGINT         NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    -- The idempotency probe and the per-ride read, in one index.
    KEY idx_payments_ride_purpose (ride_id, purpose, status),
    -- MySQL allows repeated NULLs here, so declines coexist while a reference stays unique.
    UNIQUE KEY uk_payments_reference (reference),
    CONSTRAINT ck_payments_purpose CHECK (purpose IN ('RIDE_FARE', 'CANCELLATION_FEE')),
    CONSTRAINT ck_payments_method CHECK (method IN ('CASH', 'UPI', 'CARD', 'WALLET', 'NETBANKING')),
    CONSTRAINT ck_payments_status CHECK (status IN ('SUCCESS', 'FAILED')),
    CONSTRAINT ck_payments_amount CHECK (amount >= 0),
    -- A settled payment without a reference would be unanswerable in support.
    CONSTRAINT ck_payments_reference CHECK (status <> 'SUCCESS' OR reference IS NOT NULL)
) ENGINE = InnoDB;

-- Configuration, not a random number, so the decline path is reproducible on demand.
-- 'NONE' rather than '' because ConfigurationService rejects a blank STRING on update.
INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('payment.simulated.failure.methods', 'NONE', 'STRING', 'CSV of payment methods the simulated partner must decline; NONE means all succeed'),
    ('payment.cancellation.fee.method',   'UPI',  'STRING', 'Method used to collect a cancellation fee, which is never collected in cash');

-- ADMIN was granted every permission by a CROSS JOIN in V10, which covered only the codes existing then.
INSERT INTO rbac_schema.permissions (code, resource, action, description) VALUES
    ('PAYMENT_READ',    'payments', 'READ',    'Read the payments of a ride'),
    ('PAYMENT_COLLECT', 'payments', 'COLLECT', 'Collect or retry a ride payment');

INSERT INTO rbac_schema.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_schema.roles r
         JOIN rbac_schema.permissions p
              ON p.code IN ('PAYMENT_READ', 'PAYMENT_COLLECT')
WHERE r.code IN ('ADMIN', 'DRIVER');

-- A rider may see what they were charged but never report that it arrived.
INSERT INTO rbac_schema.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_schema.roles r
         JOIN rbac_schema.permissions p
              ON p.code = 'PAYMENT_READ'
WHERE r.code = 'USER';
