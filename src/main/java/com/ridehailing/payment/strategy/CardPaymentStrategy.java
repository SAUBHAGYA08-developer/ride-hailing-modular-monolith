package com.ridehailing.payment.strategy;

import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.partner.PaymentPartner;
import org.springframework.stereotype.Component;

/** References look like {@code CARD-<rideId>-<epochMilli>}; no card details are ever held, so this stays outside PCI scope. */
@Component
public class CardPaymentStrategy extends PartnerBackedPaymentStrategy {

    public CardPaymentStrategy(PaymentPartner paymentPartner) {
        super(paymentPartner);
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CARD;
    }
}
