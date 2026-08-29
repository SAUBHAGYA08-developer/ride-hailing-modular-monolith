package com.ridehailing.ride.reaper;

/** What one reap attempt did, so a sweep can report a tally rather than a pile of log lines. */
public enum StaleRideOutcome {

    /** Nobody had been picked up yet, so the ride was cancelled and the driver released. */
    CANCELLED,

    /** The trip was already under way, so it was surfaced for an operator and left running. */
    FLAGGED,

    /** The driver is back, another instance claimed the ride, or it moved on by itself. */
    SKIPPED
}
