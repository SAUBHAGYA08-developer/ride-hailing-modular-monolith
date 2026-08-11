package com.ridehailing.controller;

import com.ridehailing.notification.channel.SseNotificationChannel;
import com.ridehailing.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live event stream for the signed-in user; the recipient comes from the token, never from a parameter. */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationStreamController {

    /** Long but finite: a bounded stream forces a reconnect instead of letting a dead emitter live forever. */
    private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final SseNotificationChannel sseChannel;

    /**
     * Consume with fetch and a ReadableStream rather than EventSource: EventSource cannot set headers, and a JWT
     * in the query string would leak into every access log.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return sseChannel.subscribe(CurrentUser.require().userId(), STREAM_TIMEOUT_MILLIS);
    }
}
