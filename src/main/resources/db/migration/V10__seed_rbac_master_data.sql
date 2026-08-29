-- ---------------------------------------------------------------------------
-- V10 : RBAC master data.
-- Authorization is permission based. Roles are only containers of permissions,
-- so adding a role never requires a Java change.
-- ---------------------------------------------------------------------------

INSERT INTO rbac_schema.roles (code, name, description) VALUES
    ('ADMIN',  'Administrator', 'Full platform administration'),
    ('USER',   'Rider',         'Books and manages their own rides'),
    ('DRIVER', 'Driver',        'Serves rides and reports their own location');

INSERT INTO rbac_schema.permissions (code, resource, action, description) VALUES
    ('USER_CREATE',            'users',         'CREATE',          'Create a user account'),
    ('USER_READ',              'users',         'READ',            'Read user accounts'),
    ('USER_UPDATE',            'users',         'UPDATE',          'Update user accounts'),
    ('USER_DELETE',            'users',         'DELETE',          'Deactivate user accounts'),

    ('DRIVER_CREATE',          'drivers',       'CREATE',          'Onboard a driver'),
    ('DRIVER_READ',            'drivers',       'READ',            'Read driver profiles'),
    ('DRIVER_UPDATE',          'drivers',       'UPDATE',          'Update driver profiles'),
    ('DRIVER_LOCATION_UPDATE', 'drivers',       'LOCATION_UPDATE', 'Publish driver GPS position'),
    ('DRIVER_STATUS_UPDATE',   'drivers',       'STATUS_UPDATE',   'Go online / offline'),

    ('VEHICLE_CREATE',         'vehicles',      'CREATE',          'Register a vehicle'),
    ('VEHICLE_READ',           'vehicles',      'READ',            'Read vehicles'),
    ('VEHICLE_UPDATE',         'vehicles',      'UPDATE',          'Update vehicles'),

    ('RIDE_CREATE',            'rides',         'CREATE',          'Book a ride'),
    ('RIDE_READ',              'rides',         'READ',            'Read rides'),
    ('RIDE_START',             'rides',         'START',           'Start an assigned ride'),
    ('RIDE_COMPLETE',          'rides',         'COMPLETE',        'Complete a started ride'),
    ('RIDE_CANCEL',            'rides',         'CANCEL',          'Cancel a ride'),

    ('COUPON_CREATE',          'coupons',       'CREATE',          'Create coupons'),
    ('COUPON_READ',            'coupons',       'READ',            'Read coupons'),
    ('COUPON_DELETE',          'coupons',       'DELETE',          'Deactivate coupons'),
    ('COUPON_VALIDATE',        'coupons',       'VALIDATE',        'Validate a coupon against a fare'),

    ('PRICING_READ',           'pricing',       'READ',            'Read pricing rules'),
    ('PRICING_CREATE',         'pricing',       'CREATE',          'Create pricing rules'),
    ('PRICING_UPDATE',         'pricing',       'UPDATE',          'Update pricing rules'),

    ('CONFIGURATION_READ',     'configuration', 'READ',            'Read business configuration'),
    ('CONFIGURATION_UPDATE',   'configuration', 'UPDATE',          'Update business configuration'),

    ('AUDIT_READ',             'audit',         'READ',            'Read the audit trail');

-- ADMIN : every permission.
INSERT INTO rbac_schema.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_schema.roles r
         CROSS JOIN rbac_schema.permissions p
WHERE r.code = 'ADMIN';

-- USER : own account, own rides, coupon and pricing lookups.
INSERT INTO rbac_schema.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_schema.roles r
         JOIN rbac_schema.permissions p
              ON p.code IN ('USER_READ', 'RIDE_CREATE', 'RIDE_READ', 'RIDE_CANCEL',
                            'COUPON_READ', 'COUPON_VALIDATE', 'PRICING_READ')
WHERE r.code = 'USER';

-- DRIVER : own profile, own vehicles, own location, own rides.
INSERT INTO rbac_schema.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_schema.roles r
         JOIN rbac_schema.permissions p
              ON p.code IN ('DRIVER_READ', 'DRIVER_UPDATE', 'DRIVER_LOCATION_UPDATE', 'DRIVER_STATUS_UPDATE',
                            'VEHICLE_CREATE', 'VEHICLE_READ', 'VEHICLE_UPDATE',
                            'RIDE_READ', 'RIDE_START', 'RIDE_COMPLETE', 'RIDE_CANCEL')
WHERE r.code = 'DRIVER';
