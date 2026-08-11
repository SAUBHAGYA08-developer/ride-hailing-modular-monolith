package com.ridehailing.ride.dto;

import com.ridehailing.common.domain.CarTypePolicy;
import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.driver.api.DriverSummary;
import com.ridehailing.driver.api.VehicleSummary;
import com.ridehailing.driver.service.DriverService;
import com.ridehailing.driver.service.VehicleService;
import com.ridehailing.payment.api.PaymentSummary;
import com.ridehailing.payment.entity.PaymentPurpose;
import com.ridehailing.payment.service.PaymentService;
import com.ridehailing.ride.entity.Ride;
import com.ridehailing.ride.entity.RideStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class RideMapper {

    /** Used only if the configuration row cannot be read, or reads as nonsense. */
    private static final int DEFAULT_PICKUP_SPEED_KMPH = 20;

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private final DriverService driverService;
    private final VehicleService vehicleService;
    private final PaymentService paymentService;
    private final ConfigurationService configurationService;

    public RideResponse toResponse(Ride ride) {
        return toResponse(ride, true);
    }

    /** History rows skip the vehicle lookup: one query per row is an N+1, and a list needs no number plate. */
    public RideResponse toListResponse(Ride ride) {
        return toResponse(ride, false);
    }

    private RideResponse toResponse(Ride ride, boolean includeVehicle) {
        DriverSummary driver = null;
        if (ride.getDriverId() != null) {
            try {
                driver = driverService.getSummary(ride.getDriverId());
            } catch (RuntimeException ex) {
                // A missing driver profile must not make an existing ride unreadable.
                log.warn("Could not load driver {} for ride {}", ride.getDriverId(), ride.getId());
            }
        }

        VehicleSummary vehicle = null;
        if (includeVehicle && ride.getVehicleId() != null) {
            try {
                vehicle = vehicleService.findSummary(ride.getVehicleId()).orElse(null);
            } catch (RuntimeException ex) {
                // A missing vehicle row must not make an existing ride unreadable.
                log.warn("Could not load vehicle {} for ride {}", ride.getVehicleId(), ride.getId());
            }
        }

        FareSummary fare = new FareSummary(ride.getPricingRuleCode(), ride.getPricingZoneCode(), ride.getDistanceFare(),
                ride.getCarTypeMultiplier(), ride.getSurgeMultiplier(), ride.getMinimumFare(),
                ride.isMinimumFareApplied(), ride.getFareBeforeDiscount(), ride.getCouponCode(),
                ride.getDiscountAmount(), ride.getTotalFare());

        return new RideResponse(ride.getId(), ride.getStatus(), ride.getUserId(), ride.getDriverId(), driver,
                ride.getVehicleId(), vehicle, ride.getRequestedCarType(), ride.getAssignedCarType(),
                CarTypePolicy.isUpgrade(ride.getRequestedCarType(), ride.getAssignedCarType()),
                ride.getDistanceKm(), ride.getPickupLatitude(), ride.getPickupLongitude(), ride.getPickupAddress(),
                ride.getDropLatitude(), ride.getDropLongitude(), ride.getDropAddress(), fare,
                ride.getRequestedAt(), ride.getAssignedAt(), ride.getStartedAt(), ride.getCompletedAt(),
                ride.getCancelledAt(), ride.getCancelledBy() == null ? null : ride.getCancelledBy().name(),
                ride.getCancellationReason(), ride.getCancellationFee(), farePayment(ride),
                ride.getDriverPickupDistanceKm(), pickupEtaMinutes(ride.getDriverPickupDistanceKm()));
    }

    /** Only a completed ride can have a fare payment, so the paginated history endpoints skip the lookup entirely. */
    private PaymentSummary farePayment(Ride ride) {
        if (ride.getId() == null || ride.getStatus() != RideStatus.COMPLETED) {
            return null;
        }
        return paymentService.findLatest(ride.getId(), PaymentPurpose.RIDE_FARE).orElse(null);
    }

    /** Rounded up, and a non-positive configured speed degrades to the default rather than dividing by zero. */
    private Integer pickupEtaMinutes(BigDecimal pickupDistanceKm) {
        if (pickupDistanceKm == null || pickupDistanceKm.signum() < 0) {
            return null;
        }
        int speedKmph = configurationService.getInt(ConfigKeys.PICKUP_AVERAGE_SPEED_KMPH, DEFAULT_PICKUP_SPEED_KMPH);
        if (speedKmph <= 0) {
            log.warn("Configured pickup speed {} is not usable, falling back to {} kmph", speedKmph,
                    DEFAULT_PICKUP_SPEED_KMPH);
            speedKmph = DEFAULT_PICKUP_SPEED_KMPH;
        }
        return pickupDistanceKm.multiply(MINUTES_PER_HOUR)
                .divide(BigDecimal.valueOf(speedKmph), 0, RoundingMode.CEILING)
                .intValue();
    }
}
