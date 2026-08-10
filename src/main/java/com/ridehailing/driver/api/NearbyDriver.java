package com.ridehailing.driver.api;

/**
 * A driver seen in the Redis GEO set, nearest first.
 *
 * A hit here proves proximity only. Availability is decided against MySQL,
 * which is the sole source of truth for reservation state.
 */
public record NearbyDriver(Long driverId, double distanceKm) {
}
