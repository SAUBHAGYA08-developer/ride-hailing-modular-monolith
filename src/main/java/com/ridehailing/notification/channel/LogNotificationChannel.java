package com.ridehailing.notification.channel;

import com.ridehailing.notification.api.NotificationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Always on, so every event is observable before any provider is configured. */
@Slf4j
@Component
public class LogNotificationChannel implements NotificationChannel {

    @Override
    public String name() {
        return "LOG";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /** Type and recipient only: a notification body can carry trip detail that has no business in a log file. */
    @Override
    public void deliver(NotificationEvent event) {
        log.info("Notification {} for user {} on ride {}", event.type(), event.recipientUserId(), event.rideId());
    }
}
