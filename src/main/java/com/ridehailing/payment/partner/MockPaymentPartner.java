package com.ridehailing.payment.partner;

import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.entity.PaymentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** The stand-in partner: authorises everything except the methods named in payment.simulated.failure.methods. */
// No network, no sleep, no randomness, so a charge costs nothing inside the completion transaction.
// Deliberately not @Primary - see PaymentPartner on why a real partner should break startup instead.
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPaymentPartner implements PaymentPartner {

    private static final String NAME = "MOCK";

    private final PaymentSimulation paymentSimulation;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PartnerCharge charge(PaymentMethod method, PaymentRequest request) {
        if (paymentSimulation.shouldDecline(method)) {
            log.info("Mock partner declined {} for ride {}: the method is in the simulated failure list",
                    method, request.rideId());
            return PartnerCharge.declined("Simulated " + method + " failure at the payment partner");
        }

        // Unique in practice, and payments.reference carries a unique key to prove it.
        String reference = method.name() + "-" + request.rideId() + "-" + Instant.now().toEpochMilli();
        return PartnerCharge.authorised(reference);
    }
}
