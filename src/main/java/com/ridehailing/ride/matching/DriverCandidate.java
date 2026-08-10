package com.ridehailing.ride.matching;

import com.ridehailing.common.domain.CarType;

import java.math.BigDecimal;

/**
 * A driver Redis reported nearby and MySQL confirmed is bookable.
 *
 * The version travels with the candidate so the reservation can be a
 * conditional UPDATE instead of a read-then-write race.
 */
public record DriverCandidate(Long driverId, Long vehicleId, CarType carType, BigDecimal rating,
                              double distanceKm, long version) {
}
