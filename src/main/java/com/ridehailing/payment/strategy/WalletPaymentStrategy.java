package com.ridehailing.payment.strategy;

import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.partner.PaymentPartner;
import org.springframework.stereotype.Component;

/** A partner-held wallet, not a platform wallet: a platform balance would be a money store, not a payment method. */
@Component
public class WalletPaymentStrategy extends PartnerBackedPaymentStrategy {

    public WalletPaymentStrategy(PaymentPartner paymentPartner) {
        super(paymentPartner);
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.WALLET;
    }
}
