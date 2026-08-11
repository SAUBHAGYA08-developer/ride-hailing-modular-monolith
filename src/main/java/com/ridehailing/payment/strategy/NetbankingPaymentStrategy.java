package com.ridehailing.payment.strategy;

import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.partner.PaymentPartner;
import org.springframework.stereotype.Component;

/** The longest method name at ten characters, which is why the method column stays VARCHAR(20) like its neighbours. */
@Component
public class NetbankingPaymentStrategy extends PartnerBackedPaymentStrategy {

    public NetbankingPaymentStrategy(PaymentPartner paymentPartner) {
        super(paymentPartner);
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.NETBANKING;
    }
}
