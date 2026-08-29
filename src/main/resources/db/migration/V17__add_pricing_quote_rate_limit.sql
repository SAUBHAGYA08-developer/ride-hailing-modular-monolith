-- ---------------------------------------------------------------------------
-- V17 : Fare estimate rate limit.
--
-- A quote is not a booking. It reserves no driver and redeems no coupon, and a
-- real client re-prices the trip every time the rider drags the pin, so reusing
-- the booking limit of 10 per minute would break the map long before it
-- protected anything. The window is the same minute every other limit uses.
-- ---------------------------------------------------------------------------

INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('api.rate-limit.quote.max',            '60', 'INTEGER', 'Fare estimates allowed per window per user'),
    ('api.rate-limit.quote.window.seconds', '60', 'INTEGER', 'Fare estimate rate limit window');
