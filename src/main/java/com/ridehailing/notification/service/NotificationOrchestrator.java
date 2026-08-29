package com.ridehailing.notification.service;

import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.notification.api.NotificationEvent;
import com.ridehailing.notification.channel.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/** Decides who hears about an event; channels decide how it travels. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationOrchestrator {

    private static final String NOTIFICATION_ENABLED = "notification.enabled";

    private final List<NotificationChannel> channels;
    private final ConfigurationService configurationService;

    /**
     * AFTER_COMMIT, so a rolled back ride never announces itself; fallbackExecution keeps events published
     * outside a transaction working. A delivery failure must never fail the caller - the ride happened regardless.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(NotificationEvent event) {
        if (event == null || event.recipientUserId() == null) {
            return;
        }
        if (!configurationService.getBoolean(NOTIFICATION_ENABLED, true)) {
            return;
        }
        for (NotificationChannel channel : channels) {
            if (!channel.enabled()) {
                continue;
            }
            try {
                channel.deliver(event);
            } catch (RuntimeException ex) {
                log.warn("Channel {} could not deliver {} for user {}",
                        channel.name(), event.type(), event.recipientUserId(), ex);
            }
        }
    }
}
