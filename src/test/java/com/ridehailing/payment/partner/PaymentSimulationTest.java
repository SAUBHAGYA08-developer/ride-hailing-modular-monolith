package com.ridehailing.payment.partner;

import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.payment.entity.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The demo switch that makes chosen methods decline, driven by configuration rather than randomness so a demo repeats. */
class PaymentSimulationTest {

    private final ConfigurationService configurationService = mock(ConfigurationService.class);
    private final PaymentSimulation paymentSimulation = new PaymentSimulation(configurationService);

    private void configured(String csv) {
        when(configurationService.getString(eq(ConfigKeys.PAYMENT_SIMULATED_FAILURE_METHODS), anyString()))
                .thenReturn(csv);
    }

    static Stream<Arguments> failureLists() {
        return Stream.of(
                // the plain case: the method is named exactly as the enum spells it
                Arguments.of("UPI", PaymentMethod.UPI, true),
                Arguments.of("UPI", PaymentMethod.CARD, false),
                // case tolerant, because an operator types a config row by hand
                Arguments.of("upi", PaymentMethod.UPI, true),
                Arguments.of("Upi", PaymentMethod.UPI, true),
                // whitespace tolerant, around the whole value and around each entry
                Arguments.of("   upi   ", PaymentMethod.UPI, true),
                Arguments.of("upi , card", PaymentMethod.CARD, true),
                Arguments.of(" UPI , CARD , WALLET ", PaymentMethod.WALLET, true),
                // an empty entry from a trailing or doubled comma is skipped, not treated as a method
                Arguments.of("UPI,,CARD", PaymentMethod.CARD, true),
                Arguments.of("UPI,", PaymentMethod.UPI, true),
                // NONE is the off switch and never matches a method, in either case
                Arguments.of("NONE", PaymentMethod.UPI, false),
                Arguments.of("none", PaymentMethod.CASH, false),
                // cash is switchable too: it is the one method with no partner to decline for it
                Arguments.of("CASH", PaymentMethod.CASH, true),
                Arguments.of("cash , netbanking", PaymentMethod.NETBANKING, true),
                // a method absent from a populated list is collected normally
                Arguments.of("CASH,UPI", PaymentMethod.CARD, false)
        );
    }

    @ParameterizedTest(name = "configured [{0}] declines {1}: {2}")
    @MethodSource("failureLists")
    @DisplayName("the simulated failure list is matched case and whitespace insensitively, entry by entry")
    void theFailureListIsMatchedCaseAndWhitespaceInsensitively(String csv, PaymentMethod method, boolean declined) {
        configured(csv);

        assertThat(paymentSimulation.shouldDecline(method)).isEqualTo(declined);
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(PaymentMethod.class)
    @DisplayName("a typo in the configuration row is ignored rather than thrown, so it cannot take payment collection down")
    void anUnknownMethodNameIsIgnoredRatherThanThrown(PaymentMethod method) {
        configured("CRYPTO");

        assertThatCode(() -> paymentSimulation.shouldDecline(method)).doesNotThrowAnyException();
        assertThat(paymentSimulation.shouldDecline(method)).isFalse();
    }

    @Test
    @DisplayName("a typo sitting beside a real method still lets the real method decline")
    void aTypoBesideARealMethodDoesNotDisarmIt() {
        configured("CRYPTO,UPI");

        assertThat(paymentSimulation.shouldDecline(PaymentMethod.UPI)).isTrue();
        assertThat(paymentSimulation.shouldDecline(PaymentMethod.CARD)).isFalse();
    }

    @Test
    @DisplayName("a list of nothing but typos declines nothing at all")
    void aListOfOnlyTyposDeclinesNothing() {
        configured("CRYPTO, BARTER , GOLD");

        for (PaymentMethod method : PaymentMethod.values()) {
            assertThat(paymentSimulation.shouldDecline(method)).isFalse();
        }
    }

    @Test
    @DisplayName("an unset configuration row falls back to declining nothing rather than failing the collection")
    void anUnsetConfigurationDeclinesNothing() {
        when(configurationService.getString(eq(ConfigKeys.PAYMENT_SIMULATED_FAILURE_METHODS), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        for (PaymentMethod method : PaymentMethod.values()) {
            assertThat(paymentSimulation.shouldDecline(method)).isFalse();
        }
    }

    @Test
    @DisplayName("a configuration value that is missing entirely declines nothing")
    void aNullConfigurationValueDeclinesNothing() {
        configured(null);

        assertThat(paymentSimulation.shouldDecline(PaymentMethod.UPI)).isFalse();
    }

    static Stream<Arguments> blankValues() {
        return Stream.of(Arguments.of(""), Arguments.of("   "), Arguments.of(","), Arguments.of(" , "));
    }

    @ParameterizedTest(name = "value [{0}]")
    @MethodSource("blankValues")
    @DisplayName("a blank or comma only configuration value declines nothing")
    void aBlankConfigurationValueDeclinesNothing(String csv) {
        configured(csv);

        assertThat(paymentSimulation.shouldDecline(PaymentMethod.UPI)).isFalse();
    }

    @Test
    @DisplayName("a payment with no method at all is never declined by the simulation")
    void aNullMethodIsNeverDeclined() {
        configured("UPI,CARD");

        assertThat(paymentSimulation.shouldDecline(null)).isFalse();
    }

    @Test
    @DisplayName("the decision is read from the documented configuration key, so an operator can find the switch")
    void theDecisionIsReadFromTheDocumentedKey() {
        configured("UPI");

        paymentSimulation.shouldDecline(PaymentMethod.UPI);

        verify(configurationService).getString(eq(ConfigKeys.PAYMENT_SIMULATED_FAILURE_METHODS), anyString());
        assertThat(ConfigKeys.PAYMENT_SIMULATED_FAILURE_METHODS).isEqualTo("payment.simulated.failure.methods");
    }
}
