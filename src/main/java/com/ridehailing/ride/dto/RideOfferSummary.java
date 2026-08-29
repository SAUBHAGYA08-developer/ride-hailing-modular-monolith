package com.ridehailing.ride.dto;

import com.ridehailing.common.domain.CarType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a driver's app sees for an incoming offer, before they have any right
 * to read the ride itself through GET /rides/{id} - RideQueryService only
 * grants that once driverId is set, which an offer deliberately precedes.
 */
public record RideOfferSummary(Long offerId,
                               Long rideId,
                               CarType carType,
                               BigDecimal pickupLatitude,
                               BigDecimal pickupLongitude,
                               String pickupAddress,
                               String dropAddress,
                               BigDecimal pickupDistanceKm,
                               Integer pickupEtaMinutes,
                               Instant expiresAt) {
}
