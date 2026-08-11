package com.ridehailing.payment.partner;

import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.payment.entity.PaymentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** The one place a simulated decline is decided, and configuration rather than random so it is reproducible. */
// The only class here a real integration deletes.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSimulation {

    /** Not an empty string: ConfigurationService rejects a blank STRING, so a cleared row could never be restored. */
    private static final String NONE = "NONE";

    private final ConfigurationService configurationService;

    /** An unknown name is warned and ignored, never thrown: a typo must not take collection down. */
    public boolean shouldDecline(PaymentMethod method) {
        String configured = configurationService.getString(ConfigKeys.PAYMENT_SIMULATED_FAILURE_METHODS, NONE);
        if (method == null || configured == null || configured.isBlank()) {
            return false;
        }

        for (String token : configured.split(",")) {
            String candidate = token.trim().toUpperCase(Locale.ROOT);
            if (candidate.isEmpty() || candidate.equals(NONE)) {
                continue;
            }
            if (candidate.equals(method.name())) {
                return true;
            }
            if (!isKnownMethod(candidate)) {
                log.warn("Ignoring unknown payment method '{}' in {}", candidate,
                        ConfigKeys.PAYMENT_SIMULATED_FAILURE_METHODS);
            }
        }
        return false;
    }

    private static boolean isKnownMethod(String candidate) {
        for (PaymentMethod method : PaymentMethod.values()) {
            if (method.name().equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
