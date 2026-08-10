package com.ridehailing.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Every monetary amount in the platform is rounded here and nowhere else. */
public final class Money {

    public static final int SCALE = 2;

    private Money() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal nonNegative(BigDecimal value) {
        return value.signum() < 0 ? zero() : round(value);
    }
}
