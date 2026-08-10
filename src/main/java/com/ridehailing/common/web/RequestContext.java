package com.ridehailing.common.web;

import org.slf4j.MDC;

/** Per request correlation data, published through MDC so every log line carries it. */
public final class RequestContext {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_CLIENT_IP = "clientIp";

    private RequestContext() {
    }

    public static String requestId() {
        return MDC.get(MDC_REQUEST_ID);
    }

    public static String clientIp() {
        return MDC.get(MDC_CLIENT_IP);
    }

    static void bind(String requestId, String clientIp) {
        MDC.put(MDC_REQUEST_ID, requestId);
        if (clientIp != null) {
            MDC.put(MDC_CLIENT_IP, clientIp);
        }
    }

    static void clear() {
        MDC.remove(MDC_REQUEST_ID);
        MDC.remove(MDC_CLIENT_IP);
    }
}
