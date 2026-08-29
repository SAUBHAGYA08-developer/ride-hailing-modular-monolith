package com.ridehailing.payment.entity;

/** Chosen when the driver completes the ride, not at booking: that is when it is a fact. */
public enum PaymentMethod {
    /** The only method collected in person, so the only one that never reaches a partner. */
    CASH,
    UPI,
    CARD,
    WALLET,
    NETBANKING
}
