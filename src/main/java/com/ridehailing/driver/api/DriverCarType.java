package com.ridehailing.driver.api;

import com.ridehailing.common.domain.CarType;

/**
 * An active vehicle category a driver can serve, keyed by driver.
 *
 * Car types are fetched as their own flat rows and regrouped in memory. Joining
 * vehicles into the driver listing instead would duplicate a driver who has two
 * active vehicles and drop one who has none - both wrong for a fleet count.
 */
public record DriverCarType(Long driverId, CarType carType) {
}
