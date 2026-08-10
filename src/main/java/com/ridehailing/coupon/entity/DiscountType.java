package com.ridehailing.coupon.entity;

public enum DiscountType {

    /** discount_value is a percentage of the fare, optionally capped. */
    PERCENTAGE,

    /** discount_value is an absolute amount. */
    FLAT
}