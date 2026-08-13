-- ---------------------------------------------------------------------------
-- V12 : Business configuration master data.
-- Only business rules live here. Infrastructure settings never do.
-- ---------------------------------------------------------------------------

INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('ride.search.radius.km',                '5',        'DECIMAL', 'Radius used for the Redis GEO nearby driver search'),
    ('matching.strategy',                    'NEAREST',  'STRING',  'Driver matching strategy identifier'),
    ('pricing.active.rule',                  'STANDARD', 'STRING',  'Code of the pricing rule used for new rides'),
    ('surge.enabled',                        'false',    'BOOLEAN', 'Whether the pricing rule surge multiplier is applied'),
    ('driver.location.ttl.seconds',          '60',       'INTEGER', 'A driver GPS position older than this is treated as stale'),

    ('api.rate-limit.ride.max',              '10',       'INTEGER', 'Ride booking requests allowed per window per user'),
    ('api.rate-limit.ride.window.seconds',   '60',       'INTEGER', 'Ride booking rate limit window'),
    ('api.rate-limit.location.max',          '60',       'INTEGER', 'Location updates allowed per window per driver'),
    ('api.rate-limit.location.window.seconds','60',      'INTEGER', 'Location update rate limit window'),
    ('api.rate-limit.login.max',             '5',        'INTEGER', 'Login attempts allowed per window per IP'),
    ('api.rate-limit.login.window.seconds',  '60',       'INTEGER', 'Login rate limit window'),
    ('api.rate-limit.coupon.max',            '20',       'INTEGER', 'Coupon validations allowed per window per user'),
    ('api.rate-limit.coupon.window.seconds', '60',       'INTEGER', 'Coupon validation rate limit window'),
    ('api.rate-limit.admin.max',             '30',       'INTEGER', 'Administrative API calls allowed per window per admin'),
    ('api.rate-limit.admin.window.seconds',  '60',       'INTEGER', 'Administrative API rate limit window'),

    ('idempotency.ttl.seconds',              '86400',    'INTEGER', 'How long an Idempotency-Key result is replayable'),

    ('ride.cancellation.allowed.statuses',   'REQUESTED,DRIVER_ASSIGNED', 'STRING', 'Ride statuses a rider may cancel from'),
    ('ride.booking.lock.ttl.seconds',        '10',       'INTEGER', 'TTL of the per user booking coordination lock');
