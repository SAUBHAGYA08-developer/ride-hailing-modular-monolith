package com.ridehailing.ride.matching;

import com.ridehailing.common.domain.CarType;

/**
 * A ranked candidate waiting its turn on a ride's offer queue, persisted as
 * JSON on {@code rides.candidate_queue}.
 *
 * Deliberately leaner than {@link DriverCandidate}: {@code version} would go
 * stale sitting in a queue and must be re-read fresh at dispatch time instead,
 * and {@code rating} is not needed once ranking is done. {@code distanceKm}
 * survives into the queue because it was already an approximation snapshot at
 * ranking time even in the old eager-assign flow - see Ride#driverPickupDistanceKm.
 */
public record QueuedCandidate(Long driverId, Long vehicleId, CarType carType, double distanceKm) {
}
