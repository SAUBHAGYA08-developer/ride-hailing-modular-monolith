package com.ridehailing.payment.strategy;

import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.entity.PaymentPurpose;
import com.ridehailing.payment.entity.PaymentStatus;
import com.ridehailing.payment.partner.PaymentPartner;
import com.ridehailing.payment.partner.PaymentSimulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cash is money already in the driver's hand, so it is the one method with nothing to authorise. */
class CashPaymentStrategyTest {

    private static final Long RIDE_ID = 42L;
    private static final PaymentRequest REQUEST =
            new PaymentRequest(RIDE_ID, 7L, 9L, new BigDecimal("187.43"), PaymentPurpose.RIDE_FARE);

    private final PaymentSimulation paymentSimulation = mock(PaymentSimulation.class);
    private final CashPaymentStrategy strategy = new CashPaymentStrategy(paymentSimulation);

    @Test
    @DisplayName("cash is collected without a payment partner at all, which is why strategy and partner are separate")
    void cashIsCollectedWithoutAPaymentPartner() {
        when(paymentSimulation.shouldDecline(any())).thenReturn(false);

        PaymentResult result = strategy.collect(REQUEST);

        assertThat(strategy).isNotInstanceOf(PartnerBackedPaymentStrategy.class);
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("the cash strategy cannot even be given a partner, so no no-op partner has to exist for it")
    void theCashStrategyCannotBeGivenAPartner() {
        for (Constructor<?> constructor : CashPaymentStrategy.class.getDeclaredConstructors()) {
            assertThat(constructor.getParameterTypes()).doesNotContain(PaymentPartner.class);
        }
    }

    @Test
    @DisplayName("cash claims the CASH method and nothing else")
    void cashClaimsTheCashMethod() {
        assertThat(strategy.method()).isEqualTo(PaymentMethod.CASH);
    }

    @Test
    @DisplayName("a cash payment records a reference of the form CASH-rideId-timestamp and no failure reason")
    void aCashPaymentRecordsAStampedReference() {
        when(paymentSimulation.shouldDecline(any())).thenReturn(false);

        PaymentResult result = strategy.collect(REQUEST);

        assertThat(result.reference()).matches("CASH-" + RIDE_ID + "-\\d+");
        assertThat(result.failureReason()).isNull();
    }

    @Test
    @DisplayName("cash asks the simulation about CASH specifically, so another method's switch cannot fail it")
    void cashAsksTheSimulationAboutCashOnly() {
        when(paymentSimulation.shouldDecline(any())).thenReturn(false);

        strategy.collect(REQUEST);

        verify(paymentSimulation).shouldDecline(PaymentMethod.CASH);
    }

    @Test
    @DisplayName("a rider who never paid the driver is recorded as a failed cash payment, not as an error")
    void aRiderWhoNeverPaidIsRecordedAsFailed() {
        when(paymentSimulation.shouldDecline(PaymentMethod.CASH)).thenReturn(true);

        PaymentResult result = strategy.collect(REQUEST);

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.failureReason()).contains("CASH");
        assertThat(result.reference()).isNull();
    }
}
