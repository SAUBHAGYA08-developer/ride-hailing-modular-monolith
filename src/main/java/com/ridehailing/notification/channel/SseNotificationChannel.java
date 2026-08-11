package com.ridehailing.notification.channel;

import com.ridehailing.notification.api.NotificationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes the same event straight to an open browser, which is what lets the pages stop polling.
 * In-memory by design: emitters belong to one JVM, so scaling out needs a Redis fan-out behind this class.
 */
@Slf4j
@Component
public class SseNotificationChannel implements NotificationChannel {

    /** A user can watch from several tabs, so a subscription is a list, not one emitter. */
    private final Map<Long, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "SSE";
    }

    @Override
    public boolean enabled() {
        return !subscribers.isEmpty();
    }

    public SseEmitter subscribe(Long userId, long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        subscribers.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        // Every terminal path removes the emitter, otherwise a closed tab leaks one per reconnect.
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(error -> remove(userId, emitter));

        try {
            // An immediate event makes the browser's open callback meaningful rather than a guess.
            emitter.send(SseEmitter.event().name("connected").data(Map.of("userId", userId)));
        } catch (IOException ex) {
            remove(userId, emitter);
        }
        return emitter;
    }

    @Override
    public void deliver(NotificationEvent event) {
        List<SseEmitter> targets = subscribers.get(event.recipientUserId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name(event.type().name()).data(event));
            } catch (IOException | IllegalStateException ex) {
                // A dead tab is normal, not an incident: drop it and keep the others.
                remove(event.recipientUserId(), emitter);
            }
        }
    }

    public int openConnections() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    private void remove(Long userId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(userId);
        if (list == null) {
            return;
        }
        list.remove(emitter);
        if (list.isEmpty()) {
            subscribers.remove(userId, list);
        }
    }
}
