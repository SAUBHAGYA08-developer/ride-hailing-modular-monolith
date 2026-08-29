package com.ridehailing.ride.matching;

/** A driver's historical response to offers: accepted out of every non-PENDING offer they ever received. */
public record DriverAcceptanceStats(Long driverId, long accepted, long total) {

    public double acceptanceRate() {
        return total == 0 ? 1.0 : (double) accepted / total;
    }
}
