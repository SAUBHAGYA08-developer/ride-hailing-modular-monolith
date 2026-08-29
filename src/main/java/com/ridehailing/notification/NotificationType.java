package com.ridehailing.notification;

/** The domain moments worth telling someone about. */
public enum NotificationType {
    RIDE_ASSIGNED,
    RIDE_STARTED,
    RIDE_COMPLETED,
    RIDE_CANCELLED,
    RIDE_RELEASED_BY_SYSTEM,
    PAYMENT_COLLECTED,
    PAYMENT_FAILED
}
