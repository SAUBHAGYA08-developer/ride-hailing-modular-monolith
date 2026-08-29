-- ---------------------------------------------------------------------------
-- V19 : fleet read permission for the live-driver view.
--
-- A separate permission rather than a reuse of DRIVER_READ: V10 grants
-- DRIVER_READ to the DRIVER role so a driver can open their own profile, so
-- guarding a fleet-wide listing with it would let any driver enumerate every
-- colleague on the platform. Resource/action is ('drivers', 'FLEET_READ'),
-- which is a new pair and therefore satisfies uk_permissions_resource_action.
-- ---------------------------------------------------------------------------

INSERT INTO rbac_schema.permissions (code, resource, action, description) VALUES
    ('DRIVER_FLEET_READ', 'drivers', 'FLEET_READ', 'Read the whole fleet and live driver presence counts');

-- ADMIN only. V10 gave ADMIN every permission with a CROSS JOIN, but that ran
-- against the rows existing then, so each later permission needs its own grant.
INSERT INTO rbac_schema.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM rbac_schema.roles r
         JOIN rbac_schema.permissions p
              ON p.code = 'DRIVER_FLEET_READ'
WHERE r.code = 'ADMIN';
