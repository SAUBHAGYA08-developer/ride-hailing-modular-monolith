package com.ridehailing.notification.channel;

import com.ridehailing.notification.api.NotificationEvent;

/**
 * Outbound port for delivering an event. Adding Firebase means one new @Component implementing this plus its
 * credentials in the environment; no publisher, orchestrator or controller changes.
 */
public interface NotificationChannel {

    String name();

    /** Read per send, so a channel can be switched off by configuration without a restart. */
    boolean enabled();

    void deliver(NotificationEvent event);
}
