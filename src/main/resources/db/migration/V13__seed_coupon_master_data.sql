-- ---------------------------------------------------------------------------
-- V13 : Coupon master data.
-- ---------------------------------------------------------------------------

INSERT INTO coupon_schema.coupons
    (code, description, discount_type, discount_value, max_discount_amount, min_ride_amount, status, per_user_limit)
VALUES
    ('WELCOME10', '10% off, capped at 100', 'PERCENTAGE', 10.00, 100.00, 100.00, 'ACTIVE', NULL),
    ('FLAT50',    'Flat 50 off',            'FLAT',       50.00, NULL,   200.00, 'ACTIVE', NULL),
    ('FIRST100',  'Flat 100 off first ride','FLAT',      100.00, NULL,   300.00, 'ACTIVE', 1);
