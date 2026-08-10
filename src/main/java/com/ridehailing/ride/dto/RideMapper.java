package com.ridehailing.ride.dto;

import com.ridehailing.common.domain.CarTypePolicy;
import com.ridehailing.driver.api.DriverSummary;
import com.ridehailing.driver.service.DriverService;
import com.ridehailing.ride.entity.Ride;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RideMapper {

    private final DriverService driverService;

    public RideResponse toResponse(Ride ride) {
        DriverSummary driver = null;
        if (ride.getDriverId() != null) {
            try {
                driver = driverService.getSummary(ride.getDriverId());
            } catch (RuntimeException ex) {
                // A missing driver profile must not make an existing ride unreadable.
                log.warn("Could not load driver {} for ride {}", ride.getDriverId(), ride.getId());
            }
        }

        FareSummary fare = new FareSummary(ride.getPricingRuleCode(), ride.getPricingZoneCode(), ride.getDistanceFare(),
                ride.getCarTypeMultiplier(), ride.getSurgeMultiplier(), ride.getMinimumFare(),
                ride.isMinimumFareApplied(), ride.getFareBeforeDiscount(), ride.getCouponCode(),
                ride.getDiscountAmount(), ride.getTotalFare());

        return new RideResponse(ride.getId(), ride.getStatus(), ride.getUserId(), ride.getDriverId(), driver,
                ride.getVehicleId(), ride.getRequestedCarType(), ride.getAssignedCarType(),
                CarTypePolicy.isUpgrade(ride.getRequestedCarType(), ride.getAssignedCarType()),
                ride.getDistanceKm(), ride.getPickupLatitude(), ride.getPickupLongitude(), ride.getPickupAddress(),
                ride.getDropLatitude(), ride.getDropLongitude(), ride.getDropAddress(), fare,
                ride.getRequestedAt(), ride.getAssignedAt(), ride.getStartedAt(), ride.getCompletedAt(),
                ride.getCancelledAt(), ride.getCancelledBy() == null ? null : ride.getCancelledBy().name(),
                ride.getCancellationReason());
    }
}
