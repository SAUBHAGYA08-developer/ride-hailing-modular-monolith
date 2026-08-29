package com.ridehailing.payment.strategy;

import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.partner.PartnerCharge;
import com.ridehailing.payment.partner.PaymentPartner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Shared body for every method a partner settles, so adding an instrument is a subclass rather than a fourth copy. */
// A method that later needs something the others do not simply overrides collect. CASH does not extend this at all.
@Slf4j
@RequiredArgsConstructor
public abstract class PartnerBackedPaymentStrategy implements PaymentStrategy {

    protected final PaymentPartner paymentPartner;

    @Override
    public PaymentResult collect(PaymentRequest request) {
        PartnerCharge charge = paymentPartner.charge(method(), request);
        if (!charge.authorised()) {
            log.info("Partner {} declined {} of {} for ride {}: {}", paymentPartner.name(), method(),
                    request.amount(), request.rideId(), charge.declineReason());
            return PaymentResult.failed(charge.declineReason());
        }
        return PaymentResult.success(charge.reference());
    }
}
