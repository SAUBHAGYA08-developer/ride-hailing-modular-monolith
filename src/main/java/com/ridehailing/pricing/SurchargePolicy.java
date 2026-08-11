package com.ridehailing.pricing;

import com.ridehailing.common.domain.CarType;
import com.ridehailing.common.util.Money;
import com.ridehailing.pricing.entity.PricingSurcharge;
import com.ridehailing.pricing.entity.SurchargeType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;

/** Whether a surcharge applies and what it comes to. Pure, so it needs no Spring to be tested or reasoned about. */
public final class SurchargePolicy {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private SurchargePolicy() {
    }

    /** Every condition is a narrowing filter, so a row with no conditions applies to every ride. */
    public static boolean matches(PricingSurcharge surcharge, BigDecimal distanceKm, CarType carType, LocalTime atTime) {
        if (surcharge == null || !surcharge.isActive()) {
            return false;
        }
        if (surcharge.getCarType() != null && surcharge.getCarType() != carType) {
            return false;
        }
        return withinDistance(surcharge, distanceKm) && withinTime(surcharge, atTime);
    }

    /** PERCENT reads against the fare passed in, so the caller decides what the percentage is of. */
    public static BigDecimal amountFor(PricingSurcharge surcharge, BigDecimal base) {
        BigDecimal rate = surcharge == null ? null : surcharge.getAmount();
        if (rate == null || rate.signum() <= 0) {
            return Money.zero();
        }
        if (surcharge.getChargeType() == SurchargeType.PERCENT) {
            BigDecimal on = base == null ? Money.zero() : base;
            return Money.round(on.multiply(rate).divide(HUNDRED, Money.SCALE + 2, RoundingMode.HALF_UP));
        }
        return Money.round(rate);
    }

    /** Lower bound inclusive, upper exclusive, so adjacent bands cannot both claim the same distance. */
    private static boolean withinDistance(PricingSurcharge surcharge, BigDecimal distanceKm) {
        if (surcharge.getMinDistanceKm() == null && surcharge.getMaxDistanceKm() == null) {
            return true;
        }
        if (distanceKm == null) {
            return false;
        }
        if (surcharge.getMinDistanceKm() != null && distanceKm.compareTo(surcharge.getMinDistanceKm()) < 0) {
            return false;
        }
        return surcharge.getMaxDistanceKm() == null || distanceKm.compareTo(surcharge.getMaxDistanceKm()) < 0;
    }

    /** A window that wraps midnight (23:00 to 05:00) is the normal case for a night fee, not an edge case. */
    private static boolean withinTime(PricingSurcharge surcharge, LocalTime atTime) {
        LocalTime from = surcharge.getActiveFromTime();
        LocalTime to = surcharge.getActiveToTime();
        if (from == null || to == null) {
            return true;
        }
        if (atTime == null || from.equals(to)) {
            return false;
        }
        return from.isBefore(to)
                ? !atTime.isBefore(from) && atTime.isBefore(to)
                : !atTime.isBefore(from) || atTime.isBefore(to);
    }
}
