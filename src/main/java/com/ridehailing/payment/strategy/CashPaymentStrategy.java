package com.ridehailing.payment.strategy;

import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.partner.PaymentSimulation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Takes no PaymentPartner: cash is already in the driver's hand, so there is nothing to authorise. */
// That asymmetry is why strategy and partner are two abstractions - collapsing them would need a no-op partner here.
@Slf4j
@Component
@RequiredArgsConstructor
public class CashPaymentStrategy implements PaymentStrategy {

    private final PaymentSimulation paymentSimulation;

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CASH;
    }

    /** Records a fact rather than requesting one, so it only fails through the demo switch. */
    @Override
    public PaymentResult collect(PaymentRequest request) {
        if (paymentSimulation.shouldDecline(method())) {
            log.info("Cash collection marked failed for ride {} by the simulated failure list", request.rideId());
            return PaymentResult.failed("Simulated CASH failure: the rider did not pay the driver");
        }
        return PaymentResult.success("CASH-" + request.rideId() + "-" + Instant.now().toEpochMilli());
    }
}
