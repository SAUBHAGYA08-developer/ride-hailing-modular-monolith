package com.ridehailing.driver.entity;

/**
 * Reservation state of a driver.
 *
 * BUSY is never chosen by the driver: it is set and cleared only by the atomic
 * compare-and-set the ride module performs when it claims or releases a driver.
 */
public enum DriverStatus {
    AVAILABLE,
    BUSY,
    OFFLINE
}
