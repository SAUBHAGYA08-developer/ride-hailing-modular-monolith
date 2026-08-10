package com.ridehailing.ride.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.coupon.service.CouponService;
import com.ridehailing.driver.service.DriverReservationService;
import com.ridehailing.pricing.api.FareQuote;
import com.ridehailing.ride.dto.CreateRideRequest;
import com.ridehailing.ride.entity.Ride;
import com.ridehailing.ride.entity.RideStatus;
import com.ridehailing.ride.matching.DriverCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The one short transaction of the booking flow: claim a driver, create the
 * ride, consume the coupon.
 *
 * It lives in its own bean so the orchestration in RideBookingService cannot
 * accidentally self-invoke a @Transactional method through {@code this} and
 * silently lose the transaction. Redis, pricing and audit stay outside.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RideCreationService {

    private final com.ridehailing.ride.repository.RideRepository rideRepository;
    private final DriverReservationService driverReservationService;
    private final CouponService couponService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Ride reserveAndCreate(Long userId, CreateRideRequest request, BigDecimal distanceKm,
                                 FareQuote quote, List<DriverCandidate> rankedCandidates) {

        DriverCandidate winner = null;
        for (DriverCandidate candidate : rankedCandidates) {
            // 0 rows means a competing booking claimed this driver between the
            // availability read and now: that is expected, not an error.
            if (driverReservationService.reserve(candidate.driverId(), candidate.version())) {
                winner = candidate;
                break;
            }
            log.debug("Driver {} was taken by a competing booking, trying the next candidate", candidate.driverId());
        }
        if (winner == null) {
            throw new BusinessException(ErrorCode.NO_DRIVER_IN_RADIUS,
                    "No available driver could be reserved for this request");
        }

        Instant now = Instant.now();
        Ride ride = new Ride();
        ride.setUserId(userId);
        ride.setDriverId(winner.driverId());
        ride.setVehicleId(winner.vehicleId());
        ride.setStatus(RideStatus.DRIVER_ASSIGNED);
        ride.setRequestedCarType(request.carType());
        ride.setAssignedCarType(winner.carType());
        ride.setPickupLatitude(request.pickupLatitude());
        ride.setPickupLongitude(request.pickupLongitude());
        ride.setPickupAddress(request.pickupAddress());
        ride.setDropLatitude(request.dropLatitude());
        ride.setDropLongitude(request.dropLongitude());
        ride.setDropAddress(request.dropAddress());
        ride.setDistanceKm(distanceKm);

        ride.setPricingRuleCode(quote.pricingRuleCode());
        ride.setDistanceFare(quote.distanceFare());
        ride.setCarTypeMultiplier(quote.carTypeMultiplier());
        ride.setSurgeMultiplier(quote.surgeMultiplier());
        ride.setMinimumFare(quote.minimumFare());
        ride.setMinimumFareApplied(quote.minimumFareApplied());
        ride.setFareBeforeDiscount(quote.fareBeforeDiscount());
        ride.setCouponCode(quote.couponCode());
        ride.setDiscountAmount(quote.discountAmount());
        ride.setTotalFare(quote.totalFare());
        ride.setFareBreakdown(serialiseBreakdown(quote));

        ride.setRequestedAt(now);
        ride.setAssignedAt(now);

        Ride saved = rideRepository.saveAndFlush(ride);

        if (quote.couponId() != null) {
            // Same transaction as the ride: a coupon must never be consumed by a
            // booking that failed to persist.
            couponService.redeem(quote.couponId(), userId, saved.getId(), quote.discountAmount());
        }
        return saved;
    }

    private String serialiseBreakdown(FareQuote quote) {
        try {
            return objectMapper.writeValueAsString(quote.breakdown());
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not store the pricing snapshot");
        }
    }
}
