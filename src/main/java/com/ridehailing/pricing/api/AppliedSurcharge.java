package com.ridehailing.pricing.api;

import java.math.BigDecimal;

/** One surcharge line that actually applied, carried into the breakdown so a rider can be told why. */
public record AppliedSurcharge(String code, String name, String chargeType, BigDecimal rate, BigDecimal amount) {
}
