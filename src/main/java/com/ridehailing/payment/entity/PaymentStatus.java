package com.ridehailing.payment.entity;

/** No PENDING: the partner answers inside the completion transaction, so there is no third state to move along. */
public enum PaymentStatus {
    SUCCESS,
    FAILED
}
