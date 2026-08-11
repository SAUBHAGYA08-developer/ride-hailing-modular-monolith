package com.ridehailing.ride.dto;

import com.ridehailing.common.domain.CarType;
import com.ridehailing.driver.api.DriverSummary;
import com.ridehailing.driver.api.VehicleSummary;
import com.ridehailing.payment.api.PaymentSummary;
import com.ridehailing.ride.entity.RideStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Serialised into idempotency_keys.response_body and read back to replay a
 * duplicate booking, so this record must stay Jackson round-trippable.
 */
public record RideResponse(Long id,
                           RideStatus status,
                           Long userId,
                           Long driverId,
                           DriverSummary driver,
                           Long vehicleId,
                           // Null on paginated history: one lookup per row would be an N+1 against a remote database.
                           VehicleSummary vehicle,
                           CarType requestedCarType,
                           CarType assignedCarType,
                           boolean carTypeUpgraded,
                           BigDecimal distanceKm,
                           BigDecimal pickupLatitude,
                           BigDecimal pickupLongitude,
                           String pickupAddress,
                           BigDecimal dropLatitude,
                           BigDecimal dropLongitude,
                           String dropAddress,
                           FareSummary fare,
                           Instant requestedAt,
                           Instant assignedAt,
                           Instant startedAt,
                           Instant completedAt,
                           Instant cancelledAt,
                           String cancelledBy,
                           String cancellationReason,
                           /**
                            * Null unless the ride was cancelled. Kept out of
                            * FareSummary on purpose: that record is the pricing
                            * snapshot and never changes after creation, whereas
                            * this is decided at cancellation time.
                            */
                           BigDecimal cancellationFee,
                           /** The fare payment, null until completion; a decline shows as FAILED with its reason. */
                           PaymentSummary payment,
                           /** Straight-line km at assignment time, not road distance and not live. */
                           BigDecimal driverPickupDistanceKm,
                           /** Rounded up: an ETA that rounds down promises a driver who cannot arrive that soon. */
                           Integer estimatedPickupEtaMinutes) {
}
