package com.ridehailing.payment.strategy;

import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.partner.PaymentPartner;
import org.springframework.stereotype.Component;

/** UPI is settled by the partner; references look like {@code UPI-<rideId>-<epochMilli>}. */
@Component
public class UpiPaymentStrategy extends PartnerBackedPaymentStrategy {

    public UpiPaymentStrategy(PaymentPartner paymentPartner) {
        super(paymentPartner);
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.UPI;
    }
}
