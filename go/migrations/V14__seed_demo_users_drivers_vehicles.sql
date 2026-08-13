-- ---------------------------------------------------------------------------
-- V14 : Development demo data.
-- Passwords are BCrypt(cost 10) hashes. No plain text password is ever stored.
--   admin@ridehailing.com                       -> Admin@123
--   rahul@ / priya@ / amit@ ridehailing.com     -> User@123
--   all four driver accounts                    -> Driver@123
--
-- Driver coordinates are Bangalore, spread around the MG Road demo pickup
-- (12.9716, 77.5946) so the 5 km search radius is actually demonstrable:
--   Raj Kumar    SEDAN      ~0.6 km   inside radius
--   Amit Sharma  HATCHBACK  ~1.6 km   inside radius
--   Vikram Singh SEDAN      ~5.2 km   outside radius
--   Neha Verma   HATCHBACK ~10.5 km   outside radius
-- Booking a HATCHBACK while Amit Sharma is BUSY therefore upgrades the rider
-- to Raj Kumar's SEDAN at no extra charge.
-- ---------------------------------------------------------------------------

INSERT INTO user_schema.users (email, password_hash, full_name, phone, role, status) VALUES
    ('admin@ridehailing.com',        '$2a$10$VTrYkxkOo/ZmR96KkC9FpOSMUUxqR7pZfYge0hb3w1OWpvHXIB3Ze', 'Platform Admin', '+919000000001', 'ADMIN',  'ACTIVE'),
    ('rahul@ridehailing.com',        '$2a$10$7kXl1OIcn/0skidywtUOW.d01fKfLnU.ngJb7BifSvsoixAffCI96', 'Rahul Mehta',    '+919000000002', 'USER',   'ACTIVE'),
    ('priya@ridehailing.com',        '$2a$10$7kXl1OIcn/0skidywtUOW.d01fKfLnU.ngJb7BifSvsoixAffCI96', 'Priya Nair',     '+919000000003', 'USER',   'ACTIVE'),
    ('amit@ridehailing.com',         '$2a$10$7kXl1OIcn/0skidywtUOW.d01fKfLnU.ngJb7BifSvsoixAffCI96', 'Amit Joshi',     '+919000000004', 'USER',   'ACTIVE'),
    ('raj.kumar@ridehailing.com',    '$2a$10$aGtNjwfhtiUZ0IlSMdISfOOycJtiuV8HdiuU.S16qgGPF0D6dvpJO', 'Raj Kumar',      '+919100000001', 'DRIVER', 'ACTIVE'),
    ('amit.sharma@ridehailing.com',  '$2a$10$aGtNjwfhtiUZ0IlSMdISfOOycJtiuV8HdiuU.S16qgGPF0D6dvpJO', 'Amit Sharma',    '+919100000002', 'DRIVER', 'ACTIVE'),
    ('vikram.singh@ridehailing.com', '$2a$10$aGtNjwfhtiUZ0IlSMdISfOOycJtiuV8HdiuU.S16qgGPF0D6dvpJO', 'Vikram Singh',   '+919100000003', 'DRIVER', 'ACTIVE'),
    ('neha.verma@ridehailing.com',   '$2a$10$aGtNjwfhtiUZ0IlSMdISfOOycJtiuV8HdiuU.S16qgGPF0D6dvpJO', 'Neha Verma',     '+919100000004', 'DRIVER', 'ACTIVE');

INSERT INTO driver_schema.drivers
    (user_id, full_name, phone, license_number, status, rating,
     last_known_latitude, last_known_longitude, last_location_at)
SELECT u.id, d.full_name, d.phone, d.license_number, 'AVAILABLE', d.rating,
       d.latitude, d.longitude, CURRENT_TIMESTAMP(6)
FROM (SELECT 'raj.kumar@ridehailing.com'    AS email, 'Raj Kumar'    AS full_name, '+919100000001' AS phone, 'KA-DL-2019-0001' AS license_number, 4.80 AS rating, 12.9750000 AS latitude, 77.5990000 AS longitude
      UNION ALL SELECT 'amit.sharma@ridehailing.com',  'Amit Sharma',  '+919100000002', 'KA-DL-2020-0002', 4.60, 12.9820000, 77.6050000
      UNION ALL SELECT 'vikram.singh@ridehailing.com', 'Vikram Singh', '+919100000003', 'KA-DL-2018-0003', 4.90, 12.9352000, 77.6245000
      UNION ALL SELECT 'neha.verma@ridehailing.com',   'Neha Verma',   '+919100000004', 'KA-DL-2021-0004', 4.70, 12.9141000, 77.6788000) d
         JOIN user_schema.users u ON u.email = d.email;

INSERT INTO driver_schema.vehicles (driver_id, car_type, registration_number, make, model, color, active)
SELECT dr.id, v.car_type, v.registration_number, v.make, v.model, v.color, 1
FROM (SELECT 'KA-DL-2019-0001' AS license_number, 'SEDAN'     AS car_type, 'KA01AB1234' AS registration_number, 'Honda'  AS make, 'City'   AS model, 'White'  AS color
      UNION ALL SELECT 'KA-DL-2020-0002', 'HATCHBACK', 'KA01CD5678', 'Maruti', 'Swift',  'Red'
      UNION ALL SELECT 'KA-DL-2018-0003', 'SEDAN',     'KA01EF9012', 'Hyundai','Verna',  'Silver'
      UNION ALL SELECT 'KA-DL-2021-0004', 'HATCHBACK', 'KA01GH3456', 'Tata',   'Altroz', 'Blue') v
         JOIN driver_schema.drivers dr ON dr.license_number = v.license_number;
