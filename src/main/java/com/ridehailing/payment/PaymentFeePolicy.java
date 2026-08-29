package com.ridehailing.payment;

import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.payment.api.PaymentMethodFee;
import com.ridehailing.payment.entity.PaymentMethod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A flat per-method surcharge, DB driven like CANCELLATION_FEE_AMOUNT: ops tunes it without a redeploy. */
@Component
@RequiredArgsConstructor
public class PaymentFeePolicy {

    private final ConfigurationService configurationService;

    /** Zero for a method with no seeded row, so a new PaymentMethod needs a config row, not a code change. */
    public BigDecimal feeFor(PaymentMethod method) {
        return configurationService.getDecimal(
                ConfigKeys.PAYMENT_FEE_PREFIX + method.name().toLowerCase(Locale.ROOT), BigDecimal.ZERO);
    }

    /** For the upfront preview: every method's current fee in one call. */
    public List<PaymentMethodFee> all() {
        return Arrays.stream(PaymentMethod.values())
                .map(method -> new PaymentMethodFee(method, feeFor(method)))
                .toList();
    }
}
