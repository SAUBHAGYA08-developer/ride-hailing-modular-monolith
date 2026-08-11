package com.ridehailing.payment.partner;

import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.entity.PaymentMethod;

/** The outbound port a real PSP will implement: adding one is a single @Component, with no change above this line. */
// One injected bean, no resolver, while only the mock exists; a second implementation makes injection ambiguous and
// forces a payment.partner key plus a resolver like DriverMatchingStrategyResolver, which is when it should be written.
public interface PaymentPartner {

    /** Appears in logs and the audit trail, so an operator can tell which partner answered. */
    String name();

    /** The method is a parameter, not a PaymentRequest field: the strategy knows the instrument, the caller does not. */
    PartnerCharge charge(PaymentMethod method, PaymentRequest request);
}
