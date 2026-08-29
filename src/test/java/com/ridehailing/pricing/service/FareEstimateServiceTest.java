package com.ridehailing.pricing.service;

import com.ridehailing.common.domain.CarType;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.common.geo.GeoUtils;
import com.ridehailing.pricing.api.FareQuote;
import com.ridehailing.pricing.dto.FareEstimateRequest;
import com.ridehailing.pricing.dto.FareEstimateResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Estimating a fare for a trip nobody has booked.
 *
 * The interesting behaviour is what an estimate does with a coupon it cannot
 * use: it must still answer with the price of the trip. PricingService is mocked
 * because the arithmetic it performs is its own concern and needs a database;
 * what is asserted here is the decision this class makes around it.
 */
class FareEstimateServiceTest {

    private static final Long USER_ID = 42L;

    private static final BigDecimal PICKUP_LAT = new BigDecimal("12.9757");
    private static final BigDecimal PICKUP_LNG = new BigDecimal("77.6069");
    private static final BigDecimal DROP_LAT = new BigDecimal("12.8452");
    private static final BigDecimal DROP_LNG = new BigDecimal("77.6602");

    private final PricingService pricingService = mock(PricingService.class);
    private final FareEstimateService fareEstimateService = new FareEstimateService(pricingService);

    private static FareEstimateRequest request(String couponCode) {
        return new FareEstimateRequest(PICKUP_LAT, PICKUP_LNG, DROP_LAT, DROP_LNG, CarType.SEDAN, couponCode);
    }

    /** A quote priced without any coupon: the answer a rejected coupon falls back to. */
    private static FareQuote quoteWithoutCoupon() {
        return quote(null, null, "0.00", "187.43");
    }

    private static FareQuote quote(String appliedCouponCode, Long couponId, String discount, String total) {
        return new FareQuote("STANDARD", "BLR_CENTRAL", new BigDecimal("15.619"), new BigDecimal("187.43"),
                new BigDecimal("1.00"), new BigDecimal("1.00"), new BigDecimal("50.00"), false,
                new BigDecimal("187.43"), appliedCouponCode, couponId, new BigDecimal(discount),
                new BigDecimal(total), Map.of("distanceFare", new BigDecimal("187.43")));
    }

    @Test
    @DisplayName("an estimate for a trip with no coupon reports the quoted fare unchanged")
    void aTripWithoutACouponIsReportedAsQuoted() {
        when(pricingService.quote(any(), any(), any(), any(), isNull(), any())).thenReturn(quoteWithoutCoupon());

        FareEstimateResponse response = fareEstimateService.estimate(request(null), USER_ID);

        assertThat(response.pricingRuleCode()).isEqualTo("STANDARD");
        assertThat(response.pricingZoneCode()).isEqualTo("BLR_CENTRAL");
        assertThat(response.distanceKm()).isEqualByComparingTo("15.619");
        assertThat(response.distanceFare()).isEqualByComparingTo("187.43");
        assertThat(response.carTypeMultiplier()).isEqualByComparingTo("1.00");
        assertThat(response.surgeMultiplier()).isEqualByComparingTo("1.00");
        assertThat(response.minimumFare()).isEqualByComparingTo("50.00");
        assertThat(response.minimumFareApplied()).isFalse();
        assertThat(response.fareBeforeDiscount()).isEqualByComparingTo("187.43");
        assertThat(response.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(response.totalFare()).isEqualByComparingTo("187.43");
    }

    @Test
    @DisplayName("an estimate with no coupon asked for reports no coupon rather than a rejection")
    void noCouponRequestedMeansNoCouponReported() {
        when(pricingService.quote(any(), any(), any(), any(), isNull(), any())).thenReturn(quoteWithoutCoupon());

        FareEstimateResponse response = fareEstimateService.estimate(request(null), USER_ID);

        assertThat(response.couponCode()).isNull();
        assertThat(response.couponApplicable()).isFalse();
        assertThat(response.couponReason()).isNull();
        assertThat(response.couponMessage()).isNull();
    }

    @Test
    @DisplayName("the trip is priced over the great circle distance between the pickup and the drop")
    void theTripIsPricedOverTheGreatCircleDistance() {
        when(pricingService.quote(any(), any(), any(), any(), isNull(), any())).thenReturn(quoteWithoutCoupon());

        fareEstimateService.estimate(request(null), USER_ID);

        ArgumentCaptor<BigDecimal> distanceKm = ArgumentCaptor.forClass(BigDecimal.class);
        verify(pricingService).quote(eq(PICKUP_LAT), eq(PICKUP_LNG), distanceKm.capture(),
                eq(CarType.SEDAN), isNull(), eq(USER_ID));
        assertThat(distanceKm.getValue())
                .isEqualByComparingTo(GeoUtils.distanceKm(PICKUP_LAT, PICKUP_LNG, DROP_LAT, DROP_LNG));
    }

    @Test
    @DisplayName("a coupon that applies is reported as applied, with the discount it earned")
    void anApplicableCouponIsReportedAsApplied() {
        when(pricingService.quote(any(), any(), any(), any(), eq("SAVE50"), any()))
                .thenReturn(quote("SAVE50", 7L, "20.00", "167.43"));

        FareEstimateResponse response = fareEstimateService.estimate(request("SAVE50"), USER_ID);

        assertThat(response.couponApplicable()).isTrue();
        assertThat(response.couponCode()).isEqualTo("SAVE50");
        assertThat(response.couponReason()).isNull();
        assertThat(response.couponMessage()).isNull();
        assertThat(response.discountAmount()).isEqualByComparingTo("20.00");
        assertThat(response.totalFare()).isEqualByComparingTo("167.43");
    }

    @Test
    @DisplayName("a rider whose coupon is expired still learns what the trip costs, priced without it")
    void anUnusableCouponStillYieldsAPrice() {
        when(pricingService.quote(any(), any(), any(), any(), eq("SAVE50"), any()))
                .thenThrow(new BusinessException(ErrorCode.COUPON_EXPIRED, "Coupon SAVE50 has expired"));
        when(pricingService.quote(any(), any(), any(), any(), isNull(), any())).thenReturn(quoteWithoutCoupon());

        FareEstimateResponse response = fareEstimateService.estimate(request("SAVE50"), USER_ID);

        assertThat(response.totalFare()).isEqualByComparingTo("187.43");
        assertThat(response.discountAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a rejected coupon is reported as data the client can branch on, not as an error")
    void aRejectedCouponIsReportedAsData() {
        when(pricingService.quote(any(), any(), any(), any(), eq("SAVE50"), any()))
                .thenThrow(new BusinessException(ErrorCode.COUPON_EXPIRED, "Coupon SAVE50 has expired"));
        when(pricingService.quote(any(), any(), any(), any(), isNull(), any())).thenReturn(quoteWithoutCoupon());

        FareEstimateResponse response = fareEstimateService.estimate(request("SAVE50"), USER_ID);

        assertThat(response.couponApplicable()).isFalse();
        assertThat(response.couponCode()).isEqualTo("SAVE50");
        assertThat(response.couponReason()).isEqualTo(ErrorCode.COUPON_EXPIRED.name());
        assertThat(response.couponMessage()).isEqualTo("Coupon SAVE50 has expired");
    }

    @Test
    @DisplayName("a rejected coupon is re-quoted exactly once, without the coupon")
    void aRejectedCouponIsRequotedOnceWithoutIt() {
        when(pricingService.quote(any(), any(), any(), any(), eq("SAVE50"), any()))
                .thenThrow(new BusinessException(ErrorCode.COUPON_NOT_FOUND, "No such coupon"));
        when(pricingService.quote(any(), any(), any(), any(), isNull(), any())).thenReturn(quoteWithoutCoupon());

        fareEstimateService.estimate(request("SAVE50"), USER_ID);

        verify(pricingService).quote(any(), any(), any(), any(), eq("SAVE50"), eq(USER_ID));
        verify(pricingService).quote(any(), any(), any(), any(), isNull(), eq(USER_ID));
    }

    @Test
    @DisplayName("a coupon typed in lower case is echoed back in the canonical upper case form")
    void aRejectedCouponIsEchoedInCanonicalForm() {
        when(pricingService.quote(any(), any(), any(), any(), eq("save50"), any()))
                .thenThrow(new BusinessException(ErrorCode.COUPON_EXHAUSTED, "Coupon fully used"));
        when(pricingService.quote(any(), any(), any(), any(), isNull(), any())).thenReturn(quoteWithoutCoupon());

        FareEstimateResponse response = fareEstimateService.estimate(request("save50"), USER_ID);

        assertThat(response.couponCode()).isEqualTo("SAVE50");
        assertThat(response.couponReason()).isEqualTo(ErrorCode.COUPON_EXHAUSTED.name());
    }

    @Test
    @DisplayName("surrounding whitespace in a coupon code is trimmed before the coupon is looked up")
    void aCouponCodeIsTrimmedBeforeUse() {
        when(pricingService.quote(any(), any(), any(), any(), eq("SAVE50"), any()))
                .thenReturn(quote("SAVE50", 7L, "20.00", "167.43"));

        fareEstimateService.estimate(request("  SAVE50  "), USER_ID);

        verify(pricingService).quote(any(), any(), any(), any(), eq("SAVE50"), eq(USER_ID));
    }

    @Test
    @DisplayName("a blank coupon field is treated as no coupon at all, not as an unusable one")
    void aBlankCouponIsTreatedAsNoCoupon() {
        when(pricingService.quote(any(), any(), any(), any(), isNull(), any())).thenReturn(quoteWithoutCoupon());

        FareEstimateResponse response = fareEstimateService.estimate(request("   "), USER_ID);

        verify(pricingService).quote(any(), any(), any(), any(), isNull(), eq(USER_ID));
        assertThat(response.couponCode()).isNull();
        assertThat(response.couponApplicable()).isFalse();
        assertThat(response.couponReason()).isNull();
    }

    @Test
    @DisplayName("a genuine pricing fault is never disguised as a coupon problem")
    void aGenuinePricingFaultReachesTheCaller() {
        when(pricingService.quote(any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.PRICING_RULE_NOT_FOUND, "Pricing rule STANDARD is missing"));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> fareEstimateService.estimate(request("SAVE50"), USER_ID));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.PRICING_RULE_NOT_FOUND);
        verify(pricingService).quote(any(), any(), any(), any(), eq("SAVE50"), eq(USER_ID));
    }

    @Test
    @DisplayName("a coupon rejection on a trip that asked for no coupon is a real failure, not absorbed")
    void aCouponErrorWithoutACouponRequestedIsNotAbsorbed() {
        when(pricingService.quote(any(), any(), any(), any(), isNull(), any()))
                .thenThrow(new BusinessException(ErrorCode.COUPON_NOT_FOUND, "No such coupon"));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> fareEstimateService.estimate(request(null), USER_ID));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.COUPON_NOT_FOUND);
    }

    @Test
    @DisplayName("a trip with an out of range coordinate is refused before anything is priced")
    void outOfRangeCoordinatesAreRefusedBeforePricing() {
        FareEstimateRequest outOfRange = new FareEstimateRequest(PICKUP_LAT, PICKUP_LNG,
                new BigDecimal("91"), DROP_LNG, CarType.SEDAN, null);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> fareEstimateService.estimate(outOfRange, USER_ID));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.INVALID_TRIP);
        verifyNoInteractions(pricingService);
    }

    @Test
    @DisplayName("a trip whose pickup and drop are the same point is refused before anything is priced")
    void aZeroLengthTripIsRefusedBeforePricing() {
        FareEstimateRequest sameSpot = new FareEstimateRequest(PICKUP_LAT, PICKUP_LNG,
                PICKUP_LAT, PICKUP_LNG, CarType.SEDAN, null);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> fareEstimateService.estimate(sameSpot, USER_ID));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.INVALID_TRIP);
        verifyNoInteractions(pricingService);
    }
}
