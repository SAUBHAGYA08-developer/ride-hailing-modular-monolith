package com.ridehailing.payment.partner;

import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.entity.PaymentPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The stand-in partner: authorises everything the simulated failure list does not name, with no network and no waiting. */
class MockPaymentPartnerTest {

    private static final Long RIDE_ID = 42L;
    private static final PaymentRequest REQUEST =
            new PaymentRequest(RIDE_ID, 7L, 9L, new BigDecimal("187.43"), PaymentPurpose.RIDE_FARE);

    private final PaymentSimulation paymentSimulation = mock(PaymentSimulation.class);
    private final MockPaymentPartner partner = new MockPaymentPartner(paymentSimulation);

    @Test
    @DisplayName("the partner names itself in every answer, so an operator can tell who authorised a charge")
    void thePartnerNamesItself() {
        assertThat(partner.name()).isEqualTo("MOCK");
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(PaymentMethod.class)
    @DisplayName("a method the simulation leaves alone is authorised with a reference and no decline reason")
    void aMethodOutsideTheFailureListIsAuthorised(PaymentMethod method) {
        when(paymentSimulation.shouldDecline(any())).thenReturn(false);

        PartnerCharge charge = partner.charge(method, REQUEST);

        assertThat(charge.authorised()).isTrue();
        assertThat(charge.reference()).isNotBlank();
        assertThat(charge.declineReason()).isNull();
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(PaymentMethod.class)
    @DisplayName("a reference carries the method and the ride it settled, so a charge is traceable from the row alone")
    void aReferenceCarriesTheMethodAndTheRide(PaymentMethod method) {
        when(paymentSimulation.shouldDecline(any())).thenReturn(false);

        PartnerCharge charge = partner.charge(method, REQUEST);

        assertThat(charge.reference()).startsWith(method.name() + "-" + RIDE_ID + "-");
        assertThat(charge.reference()).matches(method.name() + "-" + RIDE_ID + "-\\d+");
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(PaymentMethod.class)
    @DisplayName("a method named in the simulated failure list is declined with a reason naming that method")
    void aMethodInTheFailureListIsDeclined(PaymentMethod method) {
        when(paymentSimulation.shouldDecline(method)).thenReturn(true);

        PartnerCharge charge = partner.charge(method, REQUEST);

        assertThat(charge.authorised()).isFalse();
        assertThat(charge.reference()).isNull();
        assertThat(charge.declineReason()).contains(method.name());
    }

    @Test
    @DisplayName("the decline decision is taken per method, so switching one method off leaves the others collecting")
    void theDeclineDecisionIsTakenPerMethod() {
        when(paymentSimulation.shouldDecline(PaymentMethod.UPI)).thenReturn(true);
        when(paymentSimulation.shouldDecline(PaymentMethod.CARD)).thenReturn(false);

        assertThat(partner.charge(PaymentMethod.UPI, REQUEST).authorised()).isFalse();
        assertThat(partner.charge(PaymentMethod.CARD, REQUEST).authorised()).isTrue();
    }

    @Test
    @DisplayName("two charges on the same ride never reuse a reference, which the unique key on the column relies on")
    void twoChargesNeverReuseAReference() {
        when(paymentSimulation.shouldDecline(any())).thenReturn(false);

        PartnerCharge first = partner.charge(PaymentMethod.UPI, REQUEST);
        PartnerCharge second = partner.charge(PaymentMethod.CARD, REQUEST);

        assertThat(first.reference()).isNotEqualTo(second.reference());
    }
}
