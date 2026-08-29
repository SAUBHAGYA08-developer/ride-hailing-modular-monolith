package com.ridehailing.pricing.service;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.common.geo.GeoUtils;
import com.ridehailing.pricing.api.FareQuote;
import com.ridehailing.pricing.dto.FareEstimateRequest;
import com.ridehailing.pricing.dto.FareEstimateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Prices a trip nobody has booked, and never fails because of a coupon: a rider
 * dragging a pin wants to know what the ride costs, and an error about a coupon
 * answers nothing about the trip. A rejected coupon is reported as data and the
 * trip quoted again without it, the way CouponService.validate turns a rejection
 * into a negative result.
 *
 * This is a bean of its own rather than a method on PricingService, and it is
 * deliberately not transactional. CouponService.evaluate throws and is itself
 * transactional, so if both attempts ran inside one transaction owned by this
 * class the first rejection would mark that transaction rollback-only and the
 * commit after the successful second attempt would die with
 * UnexpectedRollbackException. Going through the injected PricingService proxy
 * instead gives each attempt its own read only transaction - exactly how
 * RideBookingService already calls quote().
 */
@Service
@RequiredArgsConstructor
public class FareEstimateService {

    /**
     * The coupon failures an estimate absorbs. Anything else quote() can raise -
     * an unpriceable trip, a missing pricing rule - is a genuine fault and must
     * still reach the caller as an error.
     */
    private static final Set<ErrorCode> COUPON_REJECTIONS = EnumSet.of(
            ErrorCode.COUPON_NOT_FOUND, ErrorCode.COUPON_INACTIVE, ErrorCode.COUPON_EXPIRED,
            ErrorCode.COUPON_NOT_APPLICABLE, ErrorCode.COUPON_EXHAUSTED);

    private final PricingService pricingService;

    public FareEstimateResponse estimate(FareEstimateRequest request, Long userId) {
        BigDecimal distanceKm = tripDistance(request);
        String requestedCoupon = StringUtils.hasText(request.couponCode()) ? request.couponCode().trim() : null;

        String couponReason = null;
        String couponMessage = null;
        FareQuote quote;
        try {
            quote = quote(request, distanceKm, requestedCoupon, userId);
        } catch (BusinessException ex) {
            if (requestedCoupon == null || !COUPON_REJECTIONS.contains(ex.errorCode())) {
                throw ex;
            }
            couponReason = ex.errorCode().name();
            couponMessage = ex.getMessage();
            quote = quote(request, distanceKm, null, userId);
        }

        // A quote carries a code only when the coupon actually applied, so a
        // rejected one is echoed back from the request to keep the answer self
        // describing.
        boolean couponApplicable = quote.couponCode() != null;
        String reportedCoupon = couponApplicable ? quote.couponCode()
                : requestedCoupon == null ? null : requestedCoupon.toUpperCase(Locale.ROOT);

        return new FareEstimateResponse(quote.distanceKm(), quote.pricingRuleCode(), quote.pricingZoneCode(),
                quote.distanceFare(), quote.carTypeMultiplier(), quote.surgeMultiplier(), quote.minimumFare(),
                quote.minimumFareApplied(), quote.fareBeforeDiscount(), reportedCoupon, couponApplicable,
                couponReason, couponMessage, quote.discountAmount(), quote.totalFare());
    }

    private FareQuote quote(FareEstimateRequest request, BigDecimal distanceKm, String couponCode, Long userId) {
        return pricingService.quote(request.pickupLatitude(), request.pickupLongitude(), distanceKm,
                request.carType(), couponCode, userId);
    }

    /**
     * The same two checks a booking makes, so an estimate refuses exactly the
     * trips the booking that follows it would refuse.
     */
    private BigDecimal tripDistance(FareEstimateRequest request) {
        if (!GeoUtils.isValidLatitude(request.pickupLatitude()) || !GeoUtils.isValidLongitude(request.pickupLongitude())
                || !GeoUtils.isValidLatitude(request.dropLatitude())
                || !GeoUtils.isValidLongitude(request.dropLongitude())) {
            throw new BusinessException(ErrorCode.INVALID_TRIP, "Pickup or drop coordinates are out of range");
        }
        BigDecimal distanceKm = GeoUtils.distanceKm(request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());
        if (distanceKm.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_TRIP, "Pickup and drop locations must be different");
        }
        return distanceKm;
    }
}
