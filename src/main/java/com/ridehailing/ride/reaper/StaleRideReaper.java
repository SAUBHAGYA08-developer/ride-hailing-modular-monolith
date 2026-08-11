package com.ridehailing.ride.reaper;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.coupon.service.CouponService;
import com.ridehailing.driver.service.DriverReservationService;
import com.ridehailing.ride.CancellationFeePolicy;
import com.ridehailing.ride.RideStateMachine;
import com.ridehailing.ride.entity.CancelledBy;
import com.ridehailing.ride.entity.Ride;
import com.ridehailing.ride.entity.RideStatus;
import com.ridehailing.ride.repository.RideRepository;
import com.ridehailing.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves one ride whose assigned driver has stopped reporting a position.
 *
 * Separate from {@link StaleRideReaperJob} so that each ride gets its own
 * transaction: one poisoned row must not roll back a whole sweep, and a
 * self-invoked @Transactional method on the job would bypass the proxy and run
 * with no transaction at all.
 *
 * Nothing here bypasses the normal cancel path. The transition goes through
 * {@link RideStateMachine}, the driver is handed back through
 * {@link DriverReservationService#release}, the coupon use is returned, and the
 * fee is decided by {@link CancellationFeePolicy}. A reaped ride is therefore
 * indistinguishable from a manually cancelled one except for its actor
 * (SYSTEM), its reason, and one extra audit row naming the signal that fired.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaleRideReaper {

    /** The two non-terminal states in which a driver is already holding a ride. */
    private static final Set<RideStatus> REAPABLE = EnumSet.of(RideStatus.DRIVER_ASSIGNED, RideStatus.STARTED);

    /** Only reached if the configuration row cannot be read at all; SYSTEM makes the fee zero either way. */
    private static final BigDecimal DEFAULT_CANCELLATION_FEE = new BigDecimal("30.00");

    private static final int DEFAULT_CANCELLATION_FEE_GRACE_SECONDS = 120;

    /** Recorded in every reap, so the trail says what the decision rested on rather than only what it did. */
    private static final String STALE_SIGNAL = "DRIVER_LOCATION_ABSENT";

    private static final String CANCELLATION_REASON = "Driver stopped reporting their location before pickup";

    private final RideRepository rideRepository;
    private final DriverReservationService driverReservationService;
    private final CouponService couponService;
    private final ConfigurationService configurationService;
    private final AuditService auditService;

    /**
     * @param liveDriverIds      drivers currently reporting a fresh position, from DriverLocationService
     * @param graceSeconds       ride.stale.grace.seconds, audited as the threshold that was crossed
     * @param locationTtlSeconds driver.location.ttl.seconds, audited so the signal can be interpreted later
     */
    @Transactional
    public StaleRideOutcome reap(Long rideId, Set<Long> liveDriverIds, long graceSeconds, int locationTtlSeconds) {
        Ride ride = rideRepository.findById(rideId).orElse(null);
        if (ride == null || !REAPABLE.contains(ride.getStatus()) || ride.getDriverId() == null) {
            return StaleRideOutcome.SKIPPED;
        }

        RideStatus status = ride.getStatus();
        Long driverId = ride.getDriverId();
        // Presence is re-checked inside the transaction against the authoritative driver id, not one the sweep carried in.
        if (liveDriverIds.contains(driverId)) {
            return StaleRideOutcome.SKIPPED;
        }

        Instant now = Instant.now();
        Instant stateSince = ride.getStartedAt() != null ? ride.getStartedAt() : ride.getAssignedAt();
        long staleSeconds = stateSince == null ? 0 : Math.max(0, Duration.between(stateSince, now).getSeconds());

        // The claim, shaped like DriverRepository#release: a status predicate and no read-then-write, so two instances cannot both resolve this ride.
        if (rideRepository.claimStale(rideId, status.name(), now, CurrentUser.actorName()) != 1) {
            log.debug("Ride {} was claimed by another sweep or left {} before it could be reaped", rideId, status);
            return StaleRideOutcome.SKIPPED;
        }

        Map<String, Object> evidence = Map.of(
                "signal", STALE_SIGNAL,
                "status", status.name(),
                "driverId", driverId,
                "staleSeconds", staleSeconds,
                "graceSeconds", graceSeconds,
                "locationTtlSeconds", locationTtlSeconds);

        // STARTED is flagged and left running: the rider may already be at the drop, CANCELLED is terminal so cancelling would strand the fare, and freeing a driver who could still have a passenger aboard is worse than a flag.
        if (status == RideStatus.STARTED) {
            auditService.record(AuditEntities.RIDE, rideId, AuditActions.RIDE_FLAGGED_STALE, null, evidence);
            log.warn("Ride {} is STARTED with driver {} unseen for {}s; flagged for an operator rather than cancelled",
                    rideId, driverId, staleSeconds);
            return StaleRideOutcome.FLAGGED;
        }

        // DRIVER_ASSIGNED is cancelled: nobody has been picked up, so the whole cost is a rebook, while a rider waiting for a car that will never arrive is the actual harm.
        return cancelBeforePickup(rideId, driverId, status, now, staleSeconds, graceSeconds, evidence);
    }

    private StaleRideOutcome cancelBeforePickup(Long rideId, Long driverId, RideStatus status, Instant now,
                                                long staleSeconds, long graceSeconds, Map<String, Object> evidence) {
        // Re-read because the claim cleared the persistence context; this also picks up the version it bumped.
        Ride ride = rideRepository.findById(rideId).orElse(null);
        if (ride == null) {
            log.error("Ride {} disappeared between the stale claim and the cancellation", rideId);
            return StaleRideOutcome.SKIPPED;
        }

        RideStateMachine.assertCanTransition(ride.getStatus(), RideStatus.CANCELLED);
        ride.setStatus(RideStatus.CANCELLED);
        ride.setCancelledAt(now);
        ride.setCancelledBy(CancelledBy.SYSTEM);
        ride.setCancellationReason(CANCELLATION_REASON);
        // Put through the policy with the real configured fee instead of hardcoding zero: only USER is ever charged, and being cancelled on by the system is not the rider's doing.
        ride.setCancellationFee(CancellationFeePolicy.feeFor(CancelledBy.SYSTEM, status, ride.getAssignedAt(), now,
                configurationService.getDecimal(ConfigKeys.CANCELLATION_FEE_AMOUNT, DEFAULT_CANCELLATION_FEE),
                configurationService.getInt(ConfigKeys.CANCELLATION_FEE_GRACE_SECONDS,
                        DEFAULT_CANCELLATION_FEE_GRACE_SECONDS)));
        rideRepository.saveAndFlush(ride);

        // The same action a manual cancellation writes, so one timeline covers both.
        auditService.record(AuditEntities.RIDE, rideId, AuditActions.RIDE_STATUS_CHANGED,
                Map.of("status", status.name()), Map.of("status", RideStatus.CANCELLED.name()));
        auditService.record(AuditEntities.RIDE, rideId, AuditActions.RIDE_REAPED_STALE, null, evidence);

        if (!driverReservationService.release(driverId)) {
            log.warn("Driver {} was not BUSY when ride {} was reaped", driverId, rideId);
        }
        auditService.record(AuditEntities.DRIVER, driverId, AuditActions.DRIVER_STATUS_CHANGED,
                Map.of("status", "BUSY"), Map.of("status", "AVAILABLE", "rideId", rideId));
        // No location snapshot, unlike a manual cancellation: having no live position is precisely why this ride was reaped.

        // The rider keeps the coupon use they never got a ride for, exactly as a manual cancellation gives it back.
        couponService.reverse(rideId);

        log.warn("Reaped ride {}: driver {} unseen for {}s past a {}s grace, cancelled with no fee",
                rideId, driverId, staleSeconds, graceSeconds);
        return StaleRideOutcome.CANCELLED;
    }
}
