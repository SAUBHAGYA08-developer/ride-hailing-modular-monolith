package com.ridehailing.ride.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.coupon.service.CouponService;
import com.ridehailing.driver.service.DriverReservationService;
import com.ridehailing.driver.service.DriverService;
import com.ridehailing.payment.PaymentFeePolicy;
import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.api.PaymentSummary;
import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.entity.PaymentPurpose;
import com.ridehailing.payment.entity.PaymentStatus;
import com.ridehailing.payment.service.PaymentService;
import com.ridehailing.ride.CancellationFeePolicy;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * State transitions after assignment. The Ride @Version column supplies
 * optimistic locking, so no pessimistic lock is needed: a concurrent edit of
 * the same ride surfaces as CONCURRENT_MODIFICATION and the caller retries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RideLifecycleService {

    /** Used only if the configuration row cannot be read at all. */
    private static final BigDecimal DEFAULT_CANCELLATION_FEE = new BigDecimal("30.00");

    /** A cancellation fee cannot be cash: rider and driver never met. */
    private static final PaymentMethod DEFAULT_CANCELLATION_FEE_METHOD = PaymentMethod.UPI;

    private final RideRepository rideRepository;
    private final DriverReservationService driverReservationService;
    private final DriverService driverService;
    private final CouponService couponService;
    private final PaymentService paymentService;
    private final PaymentFeePolicy paymentFeePolicy;
    private final ConfigurationService configurationService;
    private final AuditService auditService;
    private final RideMapper rideMapper;

    @Transactional
    public RideResponse start(Long rideId, AuthPrincipal principal) {
        Ride ride = requireRide(rideId);
        requireAssignedDriverOrAdmin(ride, principal);
        return rideMapper.toResponse(transition(ride, RideStatus.STARTED, now -> ride.setStartedAt(now)));
    }

    /** A declined payment never undoes the completion: the ride stands, the row is FAILED, and the retry endpoint chases it. */
    // Safe only because a decline is a PaymentResult, not an exception; a dead database still rolls the whole thing back.
    @Transactional
    public RideResponse complete(Long rideId, AuthPrincipal principal, PaymentMethod paymentMethod) {
        Ride ride = requireRide(rideId);
        requireAssignedDriverOrAdmin(ride, principal);
        Ride completed = transition(ride, RideStatus.COMPLETED, now -> ride.setCompletedAt(now));

        // Frees the driver and increments their ride counter in one atomic
        // statement, so a replayed completion cannot double count.
        if (ride.getDriverId() != null) {
            if (!driverReservationService.completeRide(ride.getDriverId())) {
                log.warn("Driver {} was not BUSY when ride {} completed", ride.getDriverId(), rideId);
            }
            auditService.record(AuditEntities.DRIVER, ride.getDriverId(), AuditActions.DRIVER_STATUS_CHANGED,
                    Map.of("status", "BUSY"), Map.of("status", "AVAILABLE", "rideId", rideId));
            // The driver has been moving for the whole ride, so this is the
            // freshest position the snapshot will see until they next go online.
            driverService.captureLocationSnapshot(ride.getDriverId());
        }

        // After the release, which must not be delayed by anything; replay is guarded inside PaymentService.
        PaymentSummary payment = paymentService.collect(paymentMethod,
                paymentRequest(completed, PaymentPurpose.RIDE_FARE));
        log.info("Ride {} completed, fare {} collected by {} with status {}", rideId, payment.amount(),
                payment.method(), payment.status());
        collectMethodFeeIfDue(completed, payment);

        // Mapped only now, so the response carries the payment that was just written.
        return rideMapper.toResponse(completed);
    }

    /** Only a successful fare is a financial event worth surcharging, and only a non-zero fee is worth a row. */
    private void collectMethodFeeIfDue(Ride ride, PaymentSummary farePayment) {
        if (farePayment.status() != PaymentStatus.SUCCESS) {
            return;
        }
        BigDecimal fee = paymentFeePolicy.feeFor(farePayment.method());
        if (fee.signum() > 0) {
            paymentService.collect(farePayment.method(), methodFeeRequest(ride, fee));
        }
    }

    private PaymentRequest methodFeeRequest(Ride ride, BigDecimal fee) {
        return new PaymentRequest(ride.getId(), ride.getUserId(), ride.getDriverId(), fee,
                PaymentPurpose.PAYMENT_METHOD_FEE);
    }

    @Transactional
    public RideResponse cancel(Long rideId, AuthPrincipal principal, String reason) {
        Ride ride = requireRide(rideId);
        CancelledBy cancelledBy = resolveCanceller(ride, principal);

        Long driverId = ride.getDriverId();
        // Read before the transition overwrites it: the fee depends on whether a
        // driver had already been dispatched.
        RideStatus statusBeforeCancel = ride.getStatus();
        BigDecimal configuredFee = configurationService.getDecimal(
                ConfigKeys.CANCELLATION_FEE_AMOUNT, DEFAULT_CANCELLATION_FEE);
        long graceSeconds = configurationService.getInt(ConfigKeys.CANCELLATION_FEE_GRACE_SECONDS, 120);

        Ride cancelled = transition(ride, RideStatus.CANCELLED, now -> {
            ride.setCancelledAt(now);
            ride.setCancelledBy(cancelledBy);
            ride.setCancellationReason(reason);
            ride.setCancellationFee(CancellationFeePolicy.feeFor(cancelledBy, statusBeforeCancel,
                    ride.getAssignedAt(), now, configuredFee, graceSeconds));
        });

        if (driverId != null) {
            if (!driverReservationService.release(driverId)) {
                log.warn("Driver {} was not BUSY when ride {} was cancelled", driverId, rideId);
            }
            auditService.record(AuditEntities.DRIVER, driverId, AuditActions.DRIVER_STATUS_CHANGED,
                    Map.of("status", "BUSY"), Map.of("status", "AVAILABLE", "rideId", rideId));
            driverService.captureLocationSnapshot(driverId);
        }
        // Only a non-zero fee is a financial event, so a free cancellation leaves neither audit row nor payment row.
        if (ride.getCancellationFee() != null && ride.getCancellationFee().signum() > 0) {
            auditService.record(AuditEntities.RIDE, rideId, AuditActions.RIDE_STATUS_CHANGED, null,
                    Map.of("cancellationFee", ride.getCancellationFee(),
                            "cancelledBy", cancelledBy.name(),
                            "graceSeconds", graceSeconds));
            // Same module as the fare, distinguished only by purpose: a second collection path would be a second ledger.
            paymentService.collect(cancellationFeeMethod(),
                    paymentRequest(cancelled, PaymentPurpose.CANCELLATION_FEE));
        }
        // The rider keeps the coupon use they never got a ride for.
        couponService.reverse(rideId);
        return rideMapper.toResponse(cancelled);
    }

    /**
     * Assigned driver or ADMIN only, as for completion, and the purpose follows from the ride's own state.
     *
     * A method fee can outlive the fare it rides on: once RIDE_FARE has settled, retrying "the payment"
     * again must mean the still-outstanding fee, not a fare PaymentService would refuse as already settled.
     */
    @Transactional
    public PaymentSummary retryPayment(Long rideId, AuthPrincipal principal, PaymentMethod method) {
        Ride ride = requireRide(rideId);
        requireAssignedDriverOrAdmin(ride, principal);

        if (ride.getStatus() == RideStatus.CANCELLED) {
            return paymentService.retry(paymentRequest(ride, PaymentPurpose.CANCELLATION_FEE), method);
        }

        Optional<PaymentSummary> outstandingFee = paymentService.findLatest(rideId, PaymentPurpose.PAYMENT_METHOD_FEE)
                .filter(fee -> fee.status() != PaymentStatus.SUCCESS);
        if (outstandingFee.isPresent()) {
            PaymentMethod resolvedMethod = method != null ? method : outstandingFee.get().method();
            PaymentRequest feeRequest = methodFeeRequest(ride, paymentFeePolicy.feeFor(resolvedMethod));
            return paymentService.retry(feeRequest, method);
        }

        // A fare that only just succeeded on this retry has never had a fee attempted for it.
        PaymentSummary retriedFare = paymentService.retry(paymentRequest(ride, PaymentPurpose.RIDE_FARE), method);
        collectMethodFeeIfDue(ride, retriedFare);
        return retriedFare;
    }

    /** Every figure comes off the ride row, so no request body can redirect a charge. */
    private PaymentRequest paymentRequest(Ride ride, PaymentPurpose purpose) {
        BigDecimal amount = purpose == PaymentPurpose.CANCELLATION_FEE
                ? ride.getCancellationFee()
                : ride.getTotalFare();
        return new PaymentRequest(ride.getId(), ride.getUserId(), ride.getDriverId(), amount, purpose);
    }

    /** A typo degrades to UPI with a warning, as DriverMatchingStrategyResolver does: the rider has already cancelled. */
    private PaymentMethod cancellationFeeMethod() {
        String configured = configurationService.getString(ConfigKeys.PAYMENT_CANCELLATION_FEE_METHOD,
                DEFAULT_CANCELLATION_FEE_METHOD.name());
        try {
            return PaymentMethod.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown cancellation fee payment method '{}', falling back to {}", configured,
                    DEFAULT_CANCELLATION_FEE_METHOD);
            return DEFAULT_CANCELLATION_FEE_METHOD;
        }
    }

    /** Returns the entity, not a response, so callers can finish their side effects before anything is mapped. */
    private Ride transition(Ride ride, RideStatus target, java.util.function.Consumer<Instant> mutation) {
        RideStatus current = ride.getStatus();
        RideStateMachine.assertCanTransition(current, target);

        mutation.accept(Instant.now());
        ride.setStatus(target);
        Ride saved = rideRepository.saveAndFlush(ride);

        auditService.record(AuditEntities.RIDE, ride.getId(), AuditActions.RIDE_STATUS_CHANGED,
                Map.of("status", current.name()), Map.of("status", target.name()));
        return saved;
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
