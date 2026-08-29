-- V25 : a per-method surcharge, collected as its own PAYMENT_METHOD_FEE payment
-- row (same pattern as CANCELLATION_FEE) rather than folded into the fare.

ALTER TABLE payment_schema.payments
    DROP CHECK ck_payments_purpose,
    ADD CONSTRAINT ck_payments_purpose CHECK (purpose IN ('RIDE_FARE', 'CANCELLATION_FEE', 'PAYMENT_METHOD_FEE'));

-- Flat amount per method, editable at runtime from the Configuration admin tab.
-- Placeholder defaults: tune from there, no redeploy needed.
INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('payment.fee.cash',        '15.00', 'DECIMAL', 'Extra flat fee charged when a ride is paid in CASH'),
    ('payment.fee.card',        '0.00',  'DECIMAL', 'Extra flat fee charged when a ride is paid by CARD'),
    ('payment.fee.upi',         '0.00',  'DECIMAL', 'Extra flat fee charged when a ride is paid by UPI'),
    ('payment.fee.wallet',      '0.00',  'DECIMAL', 'Extra flat fee charged when a ride is paid by WALLET'),
    ('payment.fee.netbanking',  '10.00', 'DECIMAL', 'Extra flat fee charged when a ride is paid by NETBANKING');
