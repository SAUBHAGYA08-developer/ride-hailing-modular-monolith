package com.ridehailing.ride.entity;

/**
 * Who cancelled a ride. Always derived from the authenticated principal and
 * never from the request body, so the trail cannot be forged by the caller.
 * SYSTEM is reserved for automated expiry and is never produced by the API.
 */
public enum CancelledBy {
    USER,
    DRIVER,
    ADMIN,
    SYSTEM
}
