package com.ridehailing.driver.api;

import com.ridehailing.common.domain.CarType;

import java.math.BigDecimal;

/**
 * A bookable driver together with the vehicle that would serve the ride.
 *
 * {@code version} is the row version read during candidate selection and must
 * be handed back to the reservation unchanged: the compare-and-set on that
 * value is what stops two riders being assigned the same driver.
 */
public record AvailableDriver(Long driverId, Long vehicleId, CarType carType, BigDecimal rating, long version) {
}
