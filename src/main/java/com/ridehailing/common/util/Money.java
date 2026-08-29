package com.ridehailing.common.util;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Every monetary amount in the platform is rounded here and nowhere else. */
public final class Money {

    public static final int SCALE = 2;

    private Money() {
    }

    /** Absent in, absent out: an amount nobody set is unknown, and rounding must not invent one. */
    public static BigDecimal round(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Same rule as round - an absent amount is never invented - but stated as a refusal, because every caller charges or stores this result. */
    // Returning null instead would only move the NPE one frame out, and returning zero would silently apply a discount nobody granted.
    public static BigDecimal nonNegative(BigDecimal value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A monetary amount is required");
        }
        return value.signum() < 0 ? zero() : round(value);
    }
}
