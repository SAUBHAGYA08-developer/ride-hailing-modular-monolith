-- ---------------------------------------------------------------------------
-- V7 : Coupon module.
-- used_count is mutated with a guarded atomic UPDATE, never with a read
-- modify write, so a coupon can never be over-redeemed under concurrency.
-- ---------------------------------------------------------------------------

CREATE TABLE coupon_schema.coupons (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    code                VARCHAR(40)    NOT NULL,
    description         VARCHAR(255)   NULL,
    discount_type       VARCHAR(20)    NOT NULL,
    discount_value      DECIMAL(10, 2) NOT NULL,
    max_discount_amount DECIMAL(10, 2) NULL COMMENT 'cap for PERCENTAGE coupons',
    min_ride_amount     DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    status              VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    valid_from          DATETIME(6)    NULL,
    valid_until         DATETIME(6)    NULL,
    usage_limit         INT            NULL COMMENT 'NULL means unlimited',
    per_user_limit      INT            NULL COMMENT 'NULL means unlimited',
    used_count          INT            NOT NULL DEFAULT 0,
    created_at          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by          VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    updated_at          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by          VARCHAR(120)   NOT NULL DEFAULT 'SYSTEM',
    version             BIGINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupons_code (code),
    CONSTRAINT ck_coupons_type CHECK (discount_type IN ('PERCENTAGE', 'FLAT')),
    CONSTRAINT ck_coupons_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_coupons_value CHECK (discount_value > 0),
    CONSTRAINT ck_coupons_min_ride CHECK (min_ride_amount >= 0),
    CONSTRAINT ck_coupons_used CHECK (used_count >= 0),
    CONSTRAINT ck_coupons_validity CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from)
) ENGINE = InnoDB;

CREATE TABLE coupon_schema.coupon_redemptions (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    coupon_id       BIGINT         NOT NULL,
    user_id         BIGINT         NOT NULL COMMENT 'user_schema.users.id - intentionally not a FK',
    ride_id         BIGINT         NOT NULL COMMENT 'ride_schema.rides.id - intentionally not a FK',
    discount_amount DECIMAL(10, 2) NOT NULL,
    redeemed_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- A ride can consume at most one coupon, ever.
    UNIQUE KEY uk_coupon_redemption_ride (ride_id),
    -- Per user usage limit check.
    KEY idx_coupon_redemption_coupon_user (coupon_id, user_id),
    CONSTRAINT fk_coupon_redemption_coupon
        FOREIGN KEY (coupon_id) REFERENCES coupon_schema.coupons (id),
    CONSTRAINT ck_coupon_redemption_amount CHECK (discount_amount >= 0)
) ENGINE = InnoDB;
