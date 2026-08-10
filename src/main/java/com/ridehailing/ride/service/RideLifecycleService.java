package com.ridehailing.ride.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.coupon.service.CouponService;
import com.ridehailing.driver.service.DriverReservationService;
import com.ridehailing.driver.service.DriverService;
import com.ridehailing.ride.RideStateMachine;
import com.ridehailing.ride.dto.RideMapper;
import com.ridehailing.ride.dto.RideResponse;
import com.ridehailing.ride.entity.CancelledBy;
import com.ridehailing.ride.entity.Ride;
import com.ridehailing.ride.entity.RideStatus;
import com.ridehailing.ride.repository.RideRepository;
import com.ridehailing.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * State transitions after assignment. The Ride @Version column supplies
 * optimistic locking, so no pessimistic lock is needed: a concurrent edit of
 * the same ride surfaces as CONCURRENT_MODIFICATION and the caller retries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RideLifecycleService {

    private final RideRepository rideRepository;
    private final DriverReservationService driverReservationService;
    private final DriverService driverService;
    private final CouponService couponService;
    private final AuditService auditService;
    private final RideMapper rideMapper;

    @Transactional
    public RideResponse start(Long rideId, AuthPrincipal principal) {
        Ride ride = requireRide(rideId);
        requireAssignedDriverOrAdmin(ride, principal);
        return transition(ride, RideStatus.STARTED, now -> ride.setStartedAt(now));
    }

    @Transactional
    public RideResponse complete(Long rideId, AuthPrincipal principal) {
        Ride ride = requireRide(rideId);
        requireAssignedDriverOrAdmin(ride, principal);
        RideResponse response = transition(ride, RideStatus.COMPLETED, now -> ride.setCompletedAt(now));

        // Frees the driver and increments their ride counter in one atomic
        // statement, so a replayed completion cannot double count.
        if (ride.getDriverId() != null) {
            if (!driverReservationService.completeRide(ride.getDriverId())) {
                log.warn("Driver {} was not BUSY when ride {} completed", ride.getDriverId(), rideId);
            }
            auditService.record(AuditEntities.DRIVER, ride.getDriverId(), AuditActions.DRIVER_STATUS_CHANGED,
                    Map.of("status", "BUSY"), Map.of("status", "AVAILABLE", "rideId", rideId));
        }
        return response;
    }

    @Transactional
    public RideResponse cancel(Long rideId, AuthPrincipal principal, String reason) {
        Ride ride = requireRide(rideId);
        CancelledBy cancelledBy = resolveCanceller(ride, principal);

        Long driverId = ride.getDriverId();
        RideResponse response = transition(ride, RideStatus.CANCELLED, now -> {
            ride.setCancelledAt(now);
            ride.setCancelledBy(cancelledBy);
            ride.setCancellationReason(reason);
        });

        if (driverId != null) {
            if (!driverReservationService.release(driverId)) {
                log.warn("Driver {} was not BUSY when ride {} was cancelled", driverId, rideId);
            }
            auditService.record(AuditEntities.DRIVER, driverId, AuditActions.DRIVER_STATUS_CHANGED,
                    Map.of("status", "BUSY"), Map.of("status", "AVAILABLE", "rideId", rideId));
        }
        // The rider keeps the coupon use they never got a ride for.
        couponService.reverse(rideId);
        return response;
    }

    private RideResponse transition(Ride ride, RideStatus target, java.util.function.Consumer<Instant> mutation) {
        RideStatus current = ride.getStatus();
        RideStateMachine.assertCanTransition(current, target);

        mutation.accept(Instant.now());
        ride.setStatus(target);
        Ride saved = rideRepository.saveAndFlush(ride);

        auditService.record(AuditEntities.RIDE, ride.getId(), AuditActions.RIDE_STATUS_CHANGED,
                Map.of("status", current.name()), Map.of("status", target.name()));
        return rideMapper.toResponse(saved);
    }

    /** The assigned driver is resolved from the token, never from the request. */
    private void requireAssignedDriverOrAdmin(Ride ride, AuthPrincipal principal) {
        if (principal.isAdmin()) {
            return;
        }
        Long driverId = principal.isDriver()
                ? driverService.findDriverIdByUserId(principal.userId()).orElse(null)
                : null;
        if (driverId == null || !driverId.equals(ride.getDriverId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Only the assigned driver may change this ride");
        }
    }

    private CancelledBy resolveCanceller(Ride ride, AuthPrincipal principal) {
        if (principal.isAdmin()) {
            return CancelledBy.ADMIN;
        }
        if (principal.isDriver()) {
            Long driverId = driverService.findDriverIdByUserId(principal.userId()).orElse(null);
            if (driverId != null && driverId.equals(ride.getDriverId())) {
                return CancelledBy.DRIVER;
            }
        } else if (principal.userId().equals(ride.getUserId())) {
            return CancelledBy.USER;
        }
        throw new BusinessException(ErrorCode.ACCESS_DENIED, "You are not allowed to cancel this ride");
    }

    private Ride requireRide(Long rideId) {
        return rideRepository.findById(rideId).orElseThrow(
                () -> new BusinessException(ErrorCode.RIDE_NOT_FOUND, "Ride " + rideId + " does not exist"));
    }
}
