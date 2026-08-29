package com.ridehailing.payment.strategy;

import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.entity.PaymentPurpose;
import com.ridehailing.payment.entity.PaymentStatus;
import com.ridehailing.payment.partner.MockPaymentPartner;
import com.ridehailing.payment.partner.PartnerCharge;
import com.ridehailing.payment.partner.PaymentPartner;
import com.ridehailing.payment.partner.PaymentSimulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How a partner's answer becomes the platform's decision, shared by every method a partner settles. */
class PartnerBackedPaymentStrategyTest {

    private static final Long RIDE_ID = 42L;
    private static final PaymentRequest REQUEST =
            new PaymentRequest(RIDE_ID, 7L, 9L, new BigDecimal("187.43"), PaymentPurpose.RIDE_FARE);

    private final PaymentPartner partner = mock(PaymentPartner.class);
    private final PaymentSimulation paymentSimulation = mock(PaymentSimulation.class);

    private PartnerBackedPaymentStrategy strategyFor(PaymentMethod method) {
        return switch (method) {
            case UPI -> new UpiPaymentStrategy(partner);
            case CARD -> new CardPaymentStrategy(partner);
            case WALLET -> new WalletPaymentStrategy(partner);
            case NETBANKING -> new NetbankingPaymentStrategy(partner);
            case CASH -> throw new IllegalArgumentException("CASH is not partner backed");
        };
    }

    /** The same strategy wired to the real mock partner, which is the thing that mints a reference. */
    private PartnerBackedPaymentStrategy strategyOverRealPartner(PaymentMethod method) {
        MockPaymentPartner mockPartner = new MockPaymentPartner(paymentSimulation);
        return switch (method) {
            case UPI -> new UpiPaymentStrategy(mockPartner);
            case CARD -> new CardPaymentStrategy(mockPartner);
            case WALLET -> new WalletPaymentStrategy(mockPartner);
            case NETBANKING -> new NetbankingPaymentStrategy(mockPartner);
            case CASH -> throw new IllegalArgumentException("CASH is not partner backed");
        };
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(value = PaymentMethod.class, names = "CASH", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every partner settled method claims exactly the method it is named for")
    void everyPartnerBackedStrategyClaimsItsOwnMethod(PaymentMethod method) {
        assertThat(strategyFor(method).method()).isEqualTo(method);
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(value = PaymentMethod.class, names = "CASH", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("an authorised charge is recorded as a successful payment carrying the partner's reference")
    void anAuthorisedChargeBecomesASuccess(PaymentMethod method) {
        when(partner.charge(any(), any())).thenReturn(PartnerCharge.authorised("PARTNER-REF-1"));

        PaymentResult result = strategyFor(method).collect(REQUEST);

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.reference()).isEqualTo("PARTNER-REF-1");
        assertThat(result.failureReason()).isNull();
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(value = PaymentMethod.class, names = "CASH", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a declined charge is recorded as a failed payment with the partner's reason kept word for word")
    void aDeclinedChargeBecomesAFailureWithTheReasonPreserved(PaymentMethod method) {
        when(partner.charge(any(), any())).thenReturn(PartnerCharge.declined("Insufficient funds at the issuer"));

        PaymentResult result = strategyFor(method).collect(REQUEST);

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo("Insufficient funds at the issuer");
        assertThat(result.reference()).isNull();
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(value = PaymentMethod.class, names = "CASH", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a decline is an answer rather than an exception, so a completed ride is never unwound by it")
    void aDeclineIsAnAnswerRatherThanAnException(PaymentMethod method) {
        when(partner.charge(any(), any())).thenReturn(PartnerCharge.declined("Card blocked"));

        assertThatCode(() -> strategyFor(method).collect(REQUEST)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(value = PaymentMethod.class, names = "CASH", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("the strategy tells the partner which instrument to charge, and passes the ride's request untouched")
    void theStrategyTellsThePartnerWhichInstrumentToCharge(PaymentMethod method) {
        when(partner.charge(any(), any())).thenReturn(PartnerCharge.authorised("PARTNER-REF-1"));

        strategyFor(method).collect(REQUEST);

        verify(partner).charge(eq(method), eq(REQUEST));
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(value = PaymentMethod.class, names = "CASH", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a successful payment always carries a reference of the form METHOD-rideId-timestamp")
    void aSuccessfulPaymentCarriesAMethodStampedReference(PaymentMethod method) {
        when(paymentSimulation.shouldDecline(any())).thenReturn(false);

        PaymentResult result = strategyOverRealPartner(method).collect(REQUEST);

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.reference()).matches(method.name() + "-" + RIDE_ID + "-\\d+");
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(value = PaymentMethod.class, names = "CASH", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a method switched into the simulated failure list fails deterministically, naming the method")
    void aSimulatedFailureMethodFailsDeterministically(PaymentMethod method) {
        when(paymentSimulation.shouldDecline(method)).thenReturn(true);

        PaymentResult result = strategyOverRealPartner(method).collect(REQUEST);

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.failureReason()).contains(method.name());
        assertThat(result.reference()).isNull();
    }

    @Test
    @DisplayName("switching one instrument into the failure list leaves the other instruments collecting")
    void switchingOneInstrumentOffLeavesTheOthersCollecting() {
        when(paymentSimulation.shouldDecline(PaymentMethod.UPI)).thenReturn(true);
        when(paymentSimulation.shouldDecline(PaymentMethod.CARD)).thenReturn(false);

        PaymentResult upi = strategyOverRealPartner(PaymentMethod.UPI).collect(REQUEST);
        PaymentResult card = strategyOverRealPartner(PaymentMethod.CARD).collect(REQUEST);

        assertThat(upi.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(card.status()).isEqualTo(PaymentStatus.SUCCESS);
    }
}
