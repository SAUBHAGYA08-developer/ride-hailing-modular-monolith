package com.ridehailing.payment.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.common.util.Money;
import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.api.PaymentSummary;
import com.ridehailing.payment.entity.Payment;
import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.entity.PaymentPurpose;
import com.ridehailing.payment.entity.PaymentStatus;
import com.ridehailing.payment.repository.PaymentRepository;
import com.ridehailing.payment.strategy.PaymentResult;
import com.ridehailing.payment.strategy.PaymentStrategy;
import com.ridehailing.payment.strategy.PaymentStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns payment_schema. The only code allowed to read or write payments.
 *
 * Default propagation everywhere, like DriverReservationService: the ride row and
 * its money row must commit or roll back together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentStrategyFactory paymentStrategyFactory;
    private final AuditService auditService;

    /**
     * Idempotent by design: an existing SUCCESS row is returned unchanged and no strategy is asked twice.
     *
     * MySQL cannot express "at most one SUCCESS per (ride, purpose)" - no partial
     * unique index - and a plain unique key would forbid the failed attempt a
     * retry sits beside, so the check lives here; concurrent completions are
     * stopped a layer up by the ride's @Version.
     */
    @Transactional
    public PaymentSummary collect(PaymentMethod method, PaymentRequest request) {
        validate(request);

        Optional<Payment> alreadySettled = paymentRepository.findByRideIdAndPurposeAndStatus(
                request.rideId(), request.purpose(), PaymentStatus.SUCCESS);
        if (alreadySettled.isPresent()) {
            log.info("Ride {} {} was already collected, returning the original payment", request.rideId(),
                    request.purpose());
            return toSummary(alreadySettled.get());
        }

        PaymentStrategy strategy = paymentStrategyFactory.forMethod(method);
        PaymentResult result = strategy.collect(request);

        Payment payment = new Payment();
        payment.setRideId(request.rideId());
        payment.setUserId(request.userId());
        payment.setDriverId(request.driverId());
        payment.setPurpose(request.purpose());
        payment.setMethod(method);
        payment.setAmount(Money.round(request.amount()));
        payment.setStatus(result.status());
        payment.setReference(result.reference());
        payment.setFailureReason(result.failureReason());
        payment.setCollectedAt(Instant.now());
        Payment saved = paymentRepository.saveAndFlush(payment);

        recordAudit(saved);
        return toSummary(saved);
    }

    /** A different method is allowed: a rider whose card failed can still pay cash. */
    @Transactional
    public PaymentSummary retry(PaymentRequest request, PaymentMethod override) {
        List<Payment> attempts = paymentRepository.findByRideIdAndPurposeOrderByIdDesc(
                request.rideId(), request.purpose());
        if (attempts.isEmpty()) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                    "Ride " + request.rideId() + " has no " + request.purpose() + " payment to retry");
        }
        // A conflict rather than a silent no-op, so a broken client cannot retry for ever unnoticed.
        if (attempts.stream().anyMatch(payment -> payment.getStatus() == PaymentStatus.SUCCESS)) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_SETTLED,
                    "Ride " + request.rideId() + " " + request.purpose() + " has already been paid");
        }

        PaymentMethod method = override != null ? override : attempts.get(0).getMethod();
        log.info("Retrying {} for ride {} by {}", request.purpose(), request.rideId(), method);
        return collect(method, request);
    }

    @Transactional(readOnly = true)
    public List<PaymentSummary> findByRide(Long rideId) {
        return paymentRepository.findByRideIdOrderByIdAsc(rideId).stream()
                .map(this::toSummary)
                .toList();
    }

    /** The latest attempt, because a rider needs the decline that is still outstanding, not the first of three. */
    @Transactional(readOnly = true)
    public Optional<PaymentSummary> findLatest(Long rideId, PaymentPurpose purpose) {
        return paymentRepository.findByRideIdAndPurposeOrderByIdDesc(rideId, purpose).stream()
                .findFirst()
                .map(this::toSummary);
    }

    /** Runs before any strategy, so a partner is never handed a null or negative charge to interpret. */
    private void validate(PaymentRequest request) {
        if (request == null || request.rideId() == null || request.userId() == null || request.purpose() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A payment needs a ride, a payer and a purpose");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A payment amount must be present and non negative");
        }
    }

    /** Written here, as CouponService does, so a retry driven straight through this service still leaves a trail. */
    // Filed under the Ride entity so the money appears on the timeline audit-logs already returns for that ride.
    private void recordAudit(Payment payment) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("purpose", payment.getPurpose().name());
        details.put("method", payment.getMethod().name());
        details.put("amount", payment.getAmount());
        details.put("status", payment.getStatus().name());
        if (payment.getReference() != null) {
            details.put("reference", payment.getReference());
        }
        if (payment.getFailureReason() != null) {
            details.put("failureReason", payment.getFailureReason());
        }

        String action = payment.getStatus() == PaymentStatus.SUCCESS
                ? AuditActions.PAYMENT_COLLECTED
                : AuditActions.PAYMENT_FAILED;
        auditService.record(AuditEntities.RIDE, payment.getRideId(), action, null, details);
    }

    private PaymentSummary toSummary(Payment payment) {
        return new PaymentSummary(payment.getId(), payment.getRideId(), payment.getPurpose(), payment.getMethod(),
                payment.getStatus(), payment.getAmount(), payment.getReference(), payment.getFailureReason(),
                payment.getCollectedAt());
    }
}
