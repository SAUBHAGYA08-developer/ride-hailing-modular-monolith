package com.ridehailing.coupon.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.common.util.Money;
import com.ridehailing.coupon.api.CouponDiscount;
import com.ridehailing.coupon.dto.CouponResponse;
import com.ridehailing.coupon.dto.CouponValidationResponse;
import com.ridehailing.coupon.dto.CreateCouponRequest;
import com.ridehailing.coupon.entity.Coupon;
import com.ridehailing.coupon.entity.CouponRedemption;
import com.ridehailing.coupon.entity.CouponStatus;
import com.ridehailing.coupon.entity.DiscountType;
import com.ridehailing.coupon.repository.CouponRedemptionRepository;
import com.ridehailing.coupon.repository.CouponRepository;
import com.ridehailing.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Owns coupon_schema. The only module allowed to read or write coupons.
 *
 * The split between evaluate and redeem is deliberate: a quote may be built
 * many times and must never move usage, while redeem takes the use exactly
 * once and only from inside the transaction that creates the ride.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private static final BigDecimal MAX_PERCENTAGE = BigDecimal.valueOf(100);

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository couponRedemptionRepository;
    private final AuditService auditService;

    /**
     * Prices a coupon against a fare without consuming anything. The limit
     * checks here are advisory: between this call and booking the last use may
     * be taken by somebody else, which is why redeem re-checks atomically.
     */
    @Transactional(readOnly = true)
    public CouponDiscount evaluate(String code, BigDecimal fareAmount, Long userId) {
        String normalisedCode = normaliseCode(code);
        requireValidFare(fareAmount);

        Coupon coupon = couponRepository.findByCode(normalisedCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND,
                        "Coupon " + normalisedCode + " does not exist"));

        if (coupon.getStatus() != CouponStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.COUPON_INACTIVE, "Coupon " + normalisedCode + " is not active");
        }

        Instant now = Instant.now();
        if (coupon.getValidFrom() != null && now.isBefore(coupon.getValidFrom())) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED, "Coupon " + normalisedCode + " is not valid yet");
        }
        if (coupon.getValidUntil() != null && now.isAfter(coupon.getValidUntil())) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED, "Coupon " + normalisedCode + " has expired");
        }

        if (fareAmount.compareTo(coupon.getMinRideAmount()) < 0) {
            throw new BusinessException(ErrorCode.COUPON_NOT_APPLICABLE,
                    "Coupon " + normalisedCode + " requires a fare of at least " + Money.round(coupon.getMinRideAmount()));
        }

        if (coupon.getUsageLimit() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new BusinessException(ErrorCode.COUPON_EXHAUSTED,
                    "Coupon " + normalisedCode + " has reached its usage limit");
        }
        if (coupon.getPerUserLimit() != null
                && couponRedemptionRepository.countByCouponIdAndUserId(coupon.getId(), userId) >= coupon.getPerUserLimit()) {
            throw new BusinessException(ErrorCode.COUPON_EXHAUSTED,
                    "Coupon " + normalisedCode + " has already been used the maximum number of times");
        }

        return new CouponDiscount(coupon.getId(), coupon.getCode(), discountFor(coupon, fareAmount));
    }

    /**
     * Takes one use. Joins the caller's transaction on purpose: if the ride
     * insert fails afterwards the redemption must disappear with it, so
     * REQUIRES_NEW would be wrong here.
     */
    @Transactional
    public void redeem(Long couponId, Long userId, Long rideId, BigDecimal discountAmount) {
        int consumed = couponRepository.consumeOne(couponId, CurrentUser.actorName());
        if (consumed == 0) {
            throw new BusinessException(ErrorCode.COUPON_EXHAUSTED,
                    "Coupon " + couponId + " is no longer available");
        }

        CouponRedemption redemption = new CouponRedemption();
        redemption.setCouponId(couponId);
        redemption.setUserId(userId);
        redemption.setRideId(rideId);
        redemption.setDiscountAmount(Money.nonNegative(discountAmount));
        redemption.setRedeemedAt(Instant.now());
        couponRedemptionRepository.save(redemption);

        auditService.record(AuditEntities.COUPON, couponId, AuditActions.COUPON_REDEEMED, null,
                Map.of("userId", userId, "rideId", rideId, "discountAmount", redemption.getDiscountAmount()));
    }

    /**
     * Gives the use back when a ride is cancelled. Most rides carry no coupon,
     * so a missing redemption is the normal case and not an error.
     */
    @Transactional
    public void reverse(Long rideId) {
        CouponRedemption redemption = couponRedemptionRepository.findByRideId(rideId).orElse(null);
        if (redemption == null) {
            return;
        }

        Long couponId = redemption.getCouponId();
        couponRedemptionRepository.deleteByRideId(rideId);
        if (couponRepository.releaseOne(couponId, CurrentUser.actorName()) == 0) {
            log.warn("Coupon {} used_count was already 0 while reversing ride {}", couponId, rideId);
        }

        auditService.record(AuditEntities.COUPON, couponId, AuditActions.COUPON_REDEMPTION_REVERSED,
                Map.of("userId", redemption.getUserId(), "rideId", rideId,
                        "discountAmount", redemption.getDiscountAmount()),
                null);
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest request) {
        String code = normaliseCode(request.code());
        if (couponRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_EXISTS, "Coupon " + code + " already exists");
        }
        validateDefinition(request);

        Coupon coupon = new Coupon();
        coupon.setCode(code);
        coupon.setDescription(request.description());
        coupon.setDiscountType(request.discountType());
        coupon.setDiscountValue(Money.round(request.discountValue()));
        coupon.setMaxDiscountAmount(Money.round(request.maxDiscountAmount()));
        coupon.setMinRideAmount(Money.round(request.minRideAmount()));
        coupon.setStatus(CouponStatus.ACTIVE);
        coupon.setValidFrom(request.validFrom());
        coupon.setValidUntil(request.validUntil());
        coupon.setUsageLimit(request.usageLimit());
        coupon.setPerUserLimit(request.perUserLimit());

        Coupon saved = couponRepository.save(coupon);
        CouponResponse response = toResponse(saved);
        auditService.record(AuditEntities.COUPON, saved.getId(), AuditActions.COUPON_CREATED, null, response);
        return response;
    }

    /**
     * Soft delete. A coupon is never removed: coupon_redemptions references it
     * and the discount on a completed ride has to stay explainable. Repeating
     * the call on an already inactive coupon is a no-op.
     */
    @Transactional
    public void deactivate(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND,
                        "Coupon " + couponId + " does not exist"));
        if (coupon.getStatus() == CouponStatus.INACTIVE) {
            return;
        }

        coupon.setStatus(CouponStatus.INACTIVE);
        couponRepository.saveAndFlush(coupon);

        auditService.record(AuditEntities.COUPON, couponId, AuditActions.COUPON_STATUS_CHANGED,
                Map.of("status", CouponStatus.ACTIVE), Map.of("status", CouponStatus.INACTIVE));
    }

    /**
     * API facing wrapper. An inapplicable coupon is an answer, not a failure,
     * so the domain exception becomes a negative result rather than an error
     * response the client has to treat as a fault.
     */
    @Transactional(readOnly = true)
    public CouponValidationResponse validate(String code, BigDecimal fareAmount, Long userId) {
        try {
            CouponDiscount discount = evaluate(code, fareAmount, userId);
            BigDecimal payable = Money.nonNegative(fareAmount.subtract(discount.discountAmount()));
            return new CouponValidationResponse(true, discount.code(), discount.discountAmount(), payable, null, null);
        } catch (BusinessException ex) {
            BigDecimal payable = fareAmount == null ? Money.zero() : Money.nonNegative(fareAmount);
            String reportedCode = code == null ? null : code.trim().toUpperCase();
            return new CouponValidationResponse(false, reportedCode, Money.zero(), payable,
                    ex.errorCode().name(), ex.getMessage());
        }
    }

    private BigDecimal discountFor(Coupon coupon, BigDecimal fareAmount) {
        BigDecimal discount;
        if (coupon.getDiscountType() == DiscountType.FLAT) {
            discount = coupon.getDiscountValue();
        } else {
            // movePointLeft(2) is an exact divide by 100, so Money stays the only rounding step.
            discount = fareAmount.multiply(coupon.getDiscountValue()).movePointLeft(2);
            if (coupon.getMaxDiscountAmount() != null && discount.compareTo(coupon.getMaxDiscountAmount()) > 0) {
                discount = coupon.getMaxDiscountAmount();
            }
        }
        // A coupon reduces the fare, it never pays the rider.
        return Money.nonNegative(discount.min(fareAmount));
    }

    private void validateDefinition(CreateCouponRequest request) {
        if (request.discountType() == DiscountType.PERCENTAGE) {
            if (request.discountValue().compareTo(MAX_PERCENTAGE) > 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "A percentage discount must be within (0, 100]");
            }
            if (request.maxDiscountAmount() != null && request.maxDiscountAmount().signum() <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "maxDiscountAmount must be greater than zero");
            }
        } else if (request.maxDiscountAmount() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "maxDiscountAmount caps a percentage and is not meaningful for a FLAT coupon");
        }
        if (request.validFrom() != null && request.validUntil() != null
                && !request.validUntil().isAfter(request.validFrom())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "validUntil must be after validFrom");
        }
    }

    private String normaliseCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A coupon code is required");
        }
        return code.trim().toUpperCase();
    }

    private void requireValidFare(BigDecimal fareAmount) {
        if (fareAmount == null || fareAmount.signum() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A non negative fare amount is required");
        }
    }

    private CouponResponse toResponse(Coupon coupon) {
        return new CouponResponse(coupon.getId(), coupon.getCode(), coupon.getDescription(), coupon.getDiscountType(),
                coupon.getDiscountValue(), coupon.getMaxDiscountAmount(), coupon.getMinRideAmount(),
                coupon.getStatus(), coupon.getValidFrom(), coupon.getValidUntil(), coupon.getUsageLimit(),
                coupon.getPerUserLimit(), coupon.getUsedCount());
    }
}
