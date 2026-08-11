package com.ridehailing.notification.api;

import com.ridehailing.notification.NotificationType;

import java.util.Map;

/** A thing that happened, addressed to a user id; a channel resolves devices, so no publisher knows about tokens. */
public record NotificationEvent(NotificationType type,
                                Long rideId,
                                Long recipientUserId,
                                String title,
                                String body,
                                Map<String, String> data) {

    public static NotificationEvent to(Long userId, NotificationType type, Long rideId, String title, String body) {
        return new NotificationEvent(type, rideId, userId, title, body,
                rideId == null ? Map.of() : Map.of("rideId", String.valueOf(rideId)));
    }
}
