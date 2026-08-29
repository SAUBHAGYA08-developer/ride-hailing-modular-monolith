package com.ridehailing.ride.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.coupon.service.CouponService;
import com.ridehailing.pricing.api.FareQuote;
import com.ridehailing.ride.dto.CreateRideRequest;
import com.ridehailing.ride.entity.Ride;
import com.ridehailing.ride.entity.RideStatus;
import com.ridehailing.ride.matching.DriverCandidate;
import com.ridehailing.ride.matching.QueuedCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The one short transaction of the booking flow: create the ride and consume
 * the coupon. No driver is claimed here any more - the ranked candidates are
 * queued and RideOfferService.dispatchNext offers them one at a time; a
 * driver is only actually reserved when they accept.
 *
 * It lives in its own bean so the orchestration in RideBookingService cannot
 * accidentally self-invoke a @Transactional method through {@code this} and
 * silently lose the transaction. Redis, pricing, audit and dispatch stay outside.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RideCreationService {

    private final com.ridehailing.ride.repository.RideRepository rideRepository;
    private final CouponService couponService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Ride createRequested(Long userId, CreateRideRequest request, BigDecimal distanceKm,
                                FareQuote quote, List<DriverCandidate> rankedCandidates) {
        if (rankedCandidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_DRIVER_IN_RADIUS,
                    "No available driver could be reserved for this request");
        }

        Instant now = Instant.now();
        Ride ride = new Ride();
        ride.setUserId(userId);
        ride.setStatus(RideStatus.REQUESTED);
        ride.setRequestedCarType(request.carType());
        ride.setCandidateQueue(serialiseQueue(rankedCandidates));
        ride.setPickupLatitude(request.pickupLatitude());
        ride.setPickupLongitude(request.pickupLongitude());
        ride.setPickupAddress(request.pickupAddress());
        ride.setDropLatitude(request.dropLatitude());
        ride.setDropLongitude(request.dropLongitude());
        ride.setDropAddress(request.dropAddress());
        ride.setDistanceKm(distanceKm);

        ride.setPricingRuleCode(quote.pricingRuleCode());
        ride.setPricingZoneCode(quote.pricingZoneCode());
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

        Ride saved = rideRepository.saveAndFlush(ride);

        if (quote.couponId() != null) {
            // Same transaction as the ride: a coupon must never be consumed by a
            // booking that failed to persist. Reversed by RideOfferService if no
            // driver ever accepts, exactly as a rider cancellation reverses it.
            couponService.redeem(quote.couponId(), userId, saved.getId(), quote.discountAmount());
        }
        return saved;
    }

    private String serialiseQueue(List<DriverCandidate> rankedCandidates) {
        List<QueuedCandidate> queue = rankedCandidates.stream()
                .map(c -> new QueuedCandidate(c.driverId(), c.vehicleId(), c.carType(), c.distanceKm()))
                .toList();
        try {
            return objectMapper.writeValueAsString(queue);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not store the candidate queue");
        }
    }

    private String serialiseBreakdown(FareQuote quote) {
        try {
            return objectMapper.writeValueAsString(quote.breakdown());
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not store the pricing snapshot");
        }
    }
}
