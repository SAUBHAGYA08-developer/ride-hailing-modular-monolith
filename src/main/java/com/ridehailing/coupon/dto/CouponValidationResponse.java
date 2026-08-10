package com.ridehailing.coupon.dto;

import java.math.BigDecimal;

/**
 * An unusable coupon is a normal answer here rather than an error: the caller
 * is probing before booking. reason carries the ErrorCode name so a client can
 * branch on it without parsing the human readable message.
 */
public record CouponValidationResponse(boolean valid,
                                       String code,
                                       BigDecimal discountAmount,
                                       BigDecimal payableAmount,
                                       String reason,
                                       String message) {
}
