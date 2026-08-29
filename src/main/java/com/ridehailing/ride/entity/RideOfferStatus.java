package com.ridehailing.ride.entity;

/** Lifecycle of one candidate's offer. A ride has at most one PENDING offer at a time. */
public enum RideOfferStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED
}
