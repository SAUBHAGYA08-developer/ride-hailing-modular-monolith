-- ---------------------------------------------------------------------------
-- V16 : Cancellation fee.
--
-- The fee is stored on the ride rather than derived on read, for the same
-- reason the fare is: raising the configured amount next month must not change
-- what a rider was charged last month.
--
-- It is a separate column instead of an adjustment to total_fare. total_fare is
-- the quoted price of a trip that never happened, and the pricing snapshot is
-- documented as immutable after creation; overwriting it would destroy the
-- record of what the rider had agreed to pay.
-- ---------------------------------------------------------------------------

ALTER TABLE ride_schema.rides
    ADD COLUMN cancellation_fee DECIMAL(10, 2) NULL AFTER cancellation_reason,
    ADD CONSTRAINT ck_rides_cancellation_fee CHECK (cancellation_fee IS NULL OR cancellation_fee >= 0);

-- --------------------------- cancellation policy ---------------------------
-- Flat rather than a percentage of the fare: the cost being recovered is the
-- driver's wasted approach to the pickup, which does not grow with the length
-- of the trip the rider abandoned.
--
-- The grace window is measured from assigned_at, so a rider who changes their
-- mind immediately after booking pays nothing.

INSERT INTO configuration_schema.configurations (config_key, config_value, value_type, description) VALUES
    ('ride.cancellation.fee.amount',        '30.00', 'DECIMAL', 'Flat fee charged when a rider cancels after the grace window'),
    ('ride.cancellation.fee.grace.seconds', '120',   'INTEGER', 'Seconds after driver assignment during which cancellation is free');
