package com.ridehailing.payment.strategy;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.payment.entity.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Maps a method onto its strategy, following DriverMatchingStrategyResolver but with no fallback: money has no safe default. */
@Slf4j
@Component
public class PaymentStrategyFactory {

    private final Map<PaymentMethod, PaymentStrategy> strategies;

    // Both checks are startup failures on purpose: the enum and the strategies must not drift apart silently.
    public PaymentStrategyFactory(List<PaymentStrategy> strategies) {
        Map<PaymentMethod, PaymentStrategy> byMethod = new EnumMap<>(PaymentMethod.class);
        for (PaymentStrategy strategy : strategies) {
            PaymentStrategy clash = byMethod.put(strategy.method(), strategy);
            if (clash != null) {
                throw new IllegalStateException("Payment method " + strategy.method() + " is claimed by both "
                        + clash.getClass().getSimpleName() + " and " + strategy.getClass().getSimpleName());
            }
        }
        for (PaymentMethod method : PaymentMethod.values()) {
            if (!byMethod.containsKey(method)) {
                throw new IllegalStateException("No PaymentStrategy implements " + method
                        + ". Every PaymentMethod must be collectable before the application may serve traffic.");
            }
        }

        this.strategies = Collections.unmodifiableMap(byMethod);
        log.info("Payment strategies registered: {}", this.strategies.keySet());
    }

    /** The exhaustiveness check above means only a null method can reach the throw. */
    public PaymentStrategy forMethod(PaymentMethod method) {
        PaymentStrategy strategy = method == null ? null : strategies.get(method);
        if (strategy == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PAYMENT_METHOD,
                    "Payment method " + method + " cannot be collected. Supported: " + strategies.keySet());
        }
        return strategy;
    }
}
