package com.ridehailing.ride.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.coupon.service.CouponService;
import com.ridehailing.driver.api.AvailableDriver;
import com.ridehailing.driver.service.DriverReservationService;
import com.ridehailing.driver.service.DriverService;
import com.ridehailing.ride.CancellationFeePolicy;
import com.ridehailing.ride.PickupEta;
import com.ridehailing.ride.RideStateMachine;
import com.ridehailing.ride.dto.RideMapper;
import com.ridehailing.ride.dto.RideOfferSummary;
import com.ridehailing.ride.dto.RideResponse;
import com.ridehailing.ride.entity.CancelledBy;
import com.ridehailing.ride.entity.Ride;
import com.ridehailing.ride.entity.RideOffer;
import com.ridehailing.ride.entity.RideOfferStatus;
import com.ridehailing.ride.entity.RideStatus;
import com.ridehailing.ride.matching.QueuedCandidate;
import com.ridehailing.ride.repository.RideOfferRepository;
import com.ridehailing.ride.repository.RideRepository;
import com.ridehailing.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a ranked candidate list into a real offer -> accept/decline exchange.
 *
 * Nothing here duplicates DriverReservationService's race-safety: dispatchNext
 * only creates a PENDING row, and the atomic conditional-UPDATE claim used by
 * the old eager-assign flow still runs, just later - inside accept() instead
 * of at ranking time. That is the only thing that moved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RideOfferService {

    private static final int PICKUP_DISTANCE_SCALE = 2;
    private static final int DEFAULT_OFFER_TIMEOUT_SECONDS = 15;
    private static final int DEFAULT_PICKUP_SPEED_KMPH = 20;
    private static final BigDecimal DEFAULT_CANCELLATION_FEE = new BigDecimal("30.00");
    private static final int DEFAULT_CANCELLATION_FEE_GRACE_SECONDS = 120;
    private static final String NO_DRIVER_ACCEPTED_REASON = "No driver accepted the ride";

    private final RideRepository rideRepository;
    private final RideOfferRepository rideOfferRepository;
    private final DriverReservationService driverReservationService;
    private final DriverService driverService;
    private final CouponService couponService;
    private final ConfigurationService configurationService;
    private final AuditService auditService;
    private final RideMapper rideMapper;
    private final ObjectMapper objectMapper;

    /**
     * Pops the next still-available candidate off the ride's queue and offers
     * it; cancels the ride once the queue is exhausted. Called right after a
     * ride is created REQUESTED, and again on every decline or expiry.
     */
    @Transactional
    public void dispatchNext(Long rideId) {
        Ride ride = rideRepository.findById(rideId).orElse(null);
        if (ride == null || ride.getStatus() != RideStatus.REQUESTED) {
            // Already resolved by a concurrent accept, or already cancelled - nothing left to advance.
            return;
        }

        List<QueuedCandidate> queue = readQueue(ride);
        while (!queue.isEmpty()) {
            QueuedCandidate next = queue.remove(0);
            List<AvailableDriver> stillAvailable = driverReservationService.findAvailableCandidates(
                    Set.of(next.driverId()), List.of(next.carType()));
            if (!stillAvailable.isEmpty()) {
                offer(ride, stillAvailable.get(0), next, queue);
                return;
            }
            log.debug("Candidate {} for ride {} is no longer available, trying the next one",
                    next.driverId(), rideId);
        }
        cancelNoDriverAccepted(ride);
    }

    /**
     * Accepted driver only: verifies the offer is theirs, reserves them, and moves the ride to DRIVER_ASSIGNED.
     *
     * noRollbackFor matters on the losing path below: resolveOffer(EXPIRED) and
     * dispatchNext are same-class calls, so they share this method's
     * transaction rather than opening their own (the usual Spring
     * self-invocation gotcha - see RideCreationService's own note on it).
     * Rolling back on the thrown BusinessException would silently undo the
     * advance to the next candidate along with the error; nothing before the
     * throw needs undoing since the failed reserve() never wrote anything.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public RideResponse accept(Long rideId, AuthPrincipal principal) {
        Long driverId = driverService.requireDriverIdForUser(principal.userId());
        RideOffer offer = requirePendingOffer(rideId, driverId);

        List<AvailableDriver> available = driverReservationService.findAvailableCandidates(
                Set.of(driverId), List.of(offer.getCarType()));
        boolean reserved = !available.isEmpty() && driverReservationService.reserve(driverId, available.get(0).version());
        if (!reserved) {
            resolveOffer(offer, RideOfferStatus.EXPIRED);
            dispatchNext(rideId);
            throw new BusinessException(ErrorCode.RIDE_OFFER_ALREADY_RESOLVED,
                    "This offer is no longer available - it may have just expired or been claimed elsewhere");
        }
        resolveOffer(offer, RideOfferStatus.ACCEPTED);

        Ride ride = rideRepository.findById(rideId).orElseThrow(() -> new BusinessException(
                ErrorCode.RIDE_NOT_FOUND, "Ride " + rideId + " does not exist"));
        RideStateMachine.assertCanTransition(ride.getStatus(), RideStatus.DRIVER_ASSIGNED);
        Instant now = Instant.now();
        ride.setStatus(RideStatus.DRIVER_ASSIGNED);
        ride.setDriverId(driverId);
        ride.setVehicleId(offer.getVehicleId());
        ride.setAssignedCarType(offer.getCarType());
        ride.setDriverPickupDistanceKm(offer.getPickupDistanceKm());
        ride.setAssignedAt(now);
        ride.setCandidateQueue(null);
        Ride saved = rideRepository.saveAndFlush(ride);

        auditService.record(AuditEntities.RIDE, rideId, AuditActions.RIDE_OFFER_ACCEPTED, null,
                Map.of("driverId", driverId));
        auditService.record(AuditEntities.RIDE, rideId, AuditActions.RIDE_STATUS_CHANGED,
                Map.of("status", RideStatus.REQUESTED.name()), Map.of("status", RideStatus.DRIVER_ASSIGNED.name()));
        auditService.record(AuditEntities.DRIVER, driverId, AuditActions.DRIVER_STATUS_CHANGED,
                Map.of("status", "AVAILABLE"), Map.of("status", "BUSY", "rideId", rideId));

        return rideMapper.toResponse(saved);
    }

    /** Declined driver: just resolves the offer and hands the ride to the next candidate. */
    @Transactional
    public void decline(Long rideId, AuthPrincipal principal) {
        Long driverId = driverService.requireDriverIdForUser(principal.userId());
        RideOffer offer = requirePendingOffer(rideId, driverId);
        resolveOffer(offer, RideOfferStatus.DECLINED);
        auditService.record(AuditEntities.RIDE, rideId, AuditActions.RIDE_OFFER_DECLINED, null,
                Map.of("driverId", driverId));
        dispatchNext(rideId);
    }

    /**
     * What a driver's app polls to discover an incoming offer, since a REQUESTED
     * ride has no driverId yet and so isn't readable through GET /rides/{id}
     * for anyone but the rider - see RideQueryService#getById.
     */
    @Transactional(readOnly = true)
    public Optional<RideOfferSummary> findPendingOfferSummary(Long driverId) {
        return rideOfferRepository.findByDriverIdAndStatus(driverId, RideOfferStatus.PENDING)
                .flatMap(offer -> rideRepository.findById(offer.getRideId()).map(ride -> toSummary(offer, ride)));
    }

    private RideOfferSummary toSummary(RideOffer offer, Ride ride) {
        int speedKmph = configurationService.getInt(ConfigKeys.PICKUP_AVERAGE_SPEED_KMPH, DEFAULT_PICKUP_SPEED_KMPH);
        if (speedKmph <= 0) {
            speedKmph = DEFAULT_PICKUP_SPEED_KMPH;
        }
        return new RideOfferSummary(offer.getId(), ride.getId(), offer.getCarType(),
                ride.getPickupLatitude(), ride.getPickupLongitude(), ride.getPickupAddress(), ride.getDropAddress(),
                offer.getPickupDistanceKm(), PickupEta.minutesFor(offer.getPickupDistanceKm(), speedKmph),
                offer.getExpiresAt());
    }

    private RideOffer requirePendingOffer(Long rideId, Long driverId) {
        RideOffer offer = rideOfferRepository.findByRideIdAndStatus(rideId, RideOfferStatus.PENDING)
                .orElseThrow(() -> new BusinessException(ErrorCode.RIDE_OFFER_NOT_FOUND,
                        "Ride " + rideId + " has no pending offer"));
        if (!offer.getDriverId().equals(driverId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "This offer was not made to you");
        }
        return offer;
    }

    private void resolveOffer(RideOffer offer, RideOfferStatus status) {
        offer.setStatus(status);
        offer.setRespondedAt(Instant.now());
        rideOfferRepository.saveAndFlush(offer);
    }

    private void offer(Ride ride, AvailableDriver available, QueuedCandidate candidate, List<QueuedCandidate> remainingQueue) {
        int timeoutSeconds = configurationService.getInt(
                ConfigKeys.RIDE_OFFER_TIMEOUT_SECONDS, DEFAULT_OFFER_TIMEOUT_SECONDS);
        Instant now = Instant.now();

        RideOffer offer = new RideOffer();
        offer.setRideId(ride.getId());
        offer.setDriverId(available.driverId());
        offer.setVehicleId(available.vehicleId());
        offer.setCarType(available.carType());
        offer.setSequence((int) rideOfferRepository.countByRideId(ride.getId()) + 1);
        // valueOf, not new BigDecimal(double), which would persist binary artefacts; not Money either, this is not money.
        offer.setPickupDistanceKm(BigDecimal.valueOf(candidate.distanceKm())
                .setScale(PICKUP_DISTANCE_SCALE, RoundingMode.HALF_UP));
        offer.setStatus(RideOfferStatus.PENDING);
        offer.setOfferedAt(now);
        offer.setExpiresAt(now.plusSeconds(Math.max(timeoutSeconds, 1)));
        rideOfferRepository.saveAndFlush(offer);

        ride.setCandidateQueue(writeQueue(remainingQueue));
        rideRepository.saveAndFlush(ride);

        auditService.record(AuditEntities.RIDE, ride.getId(), AuditActions.RIDE_OFFER_DISPATCHED, null,
                Map.of("driverId", available.driverId(), "expiresAt", offer.getExpiresAt().toString()));
    }

    private void cancelNoDriverAccepted(Ride ride) {
        RideStatus statusBefore = ride.getStatus();
        RideStateMachine.assertCanTransition(statusBefore, RideStatus.CANCELLED);
        Instant now = Instant.now();

        ride.setStatus(RideStatus.CANCELLED);
        ride.setCancelledAt(now);
        ride.setCancelledBy(CancelledBy.SYSTEM);
        ride.setCancellationReason(NO_DRIVER_ACCEPTED_REASON);
        ride.setCandidateQueue(null);
        // Always zero here: CancellationFeePolicy only charges a DRIVER_ASSIGNED cancellation, and this ride never got one.
        ride.setCancellationFee(CancellationFeePolicy.feeFor(CancelledBy.SYSTEM, statusBefore, ride.getAssignedAt(), now,
                configurationService.getDecimal(ConfigKeys.CANCELLATION_FEE_AMOUNT, DEFAULT_CANCELLATION_FEE),
                configurationService.getInt(ConfigKeys.CANCELLATION_FEE_GRACE_SECONDS,
                        DEFAULT_CANCELLATION_FEE_GRACE_SECONDS)));
        rideRepository.saveAndFlush(ride);

        auditService.record(AuditEntities.RIDE, ride.getId(), AuditActions.RIDE_STATUS_CHANGED,
                Map.of("status", statusBefore.name()), Map.of("status", RideStatus.CANCELLED.name()));
        couponService.reverse(ride.getId());
        log.warn("Ride {} cancelled: no driver accepted", ride.getId());
    }

    private List<QueuedCandidate> readQueue(Ride ride) {
        String json = ride.getCandidateQueue();
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(json, new TypeReference<List<QueuedCandidate>>() {
            }));
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Could not read the candidate queue for ride " + ride.getId());
        }
    }

    private String writeQueue(List<QueuedCandidate> queue) {
        try {
            return objectMapper.writeValueAsString(queue);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not store the candidate queue");
        }
    }
}
