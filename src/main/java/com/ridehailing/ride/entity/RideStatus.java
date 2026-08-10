package com.ridehailing.ride.entity;

/** Lifecycle of a ride. The legal moves between these values live in RideStateMachine. */
public enum RideStatus {
    REQUESTED,
    DRIVER_ASSIGNED,
    STARTED,
    COMPLETED,
    CANCELLED
}
