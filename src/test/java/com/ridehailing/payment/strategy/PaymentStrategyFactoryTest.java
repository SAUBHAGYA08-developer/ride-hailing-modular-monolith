package com.ridehailing.payment.strategy;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.payment.api.PaymentRequest;
import com.ridehailing.payment.entity.PaymentMethod;
import com.ridehailing.payment.partner.PaymentPartner;
import com.ridehailing.payment.partner.PaymentSimulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** The guard that keeps PaymentMethod and the strategies from drifting apart, checked at construction rather than at the first payment. */
class PaymentStrategyFactoryTest {

    private static final PaymentPartner PARTNER = mock(PaymentPartner.class);

    private final PaymentStrategyFactory factory = new PaymentStrategyFactory(allStrategies());

    /** Exactly the beans Spring collects in production, built by hand so no context is needed. */
    private static List<PaymentStrategy> allStrategies() {
        return List.of(new CashPaymentStrategy(mock(PaymentSimulation.class)),
                new UpiPaymentStrategy(PARTNER),
                new CardPaymentStrategy(PARTNER),
                new WalletPaymentStrategy(PARTNER),
                new NetbankingPaymentStrategy(PARTNER));
    }

    private static List<PaymentStrategy> allExcept(PaymentMethod omitted) {
        List<PaymentStrategy> remaining = new ArrayList<>();
        for (PaymentStrategy strategy : allStrategies()) {
            if (strategy.method() != omitted) {
                remaining.add(strategy);
            }
        }
        return remaining;
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(PaymentMethod.class)
    @DisplayName("every payment method the platform offers is collectable by a strategy that claims that method")
    void everyMethodResolvesToAStrategyThatClaimsIt(PaymentMethod method) {
        PaymentStrategy strategy = factory.forMethod(method);

        assertThat(strategy).isNotNull();
        assertThat(strategy.method()).isEqualTo(method);
    }

    @Test
    @DisplayName("each method is served by its own strategy, so no two methods share a collection path")
    void eachMethodHasItsOwnStrategy() {
        Set<PaymentStrategy> resolved = new HashSet<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            resolved.add(factory.forMethod(method));
        }

        assertThat(resolved).hasSize(PaymentMethod.values().length);
    }

    @ParameterizedTest(name = "method {0}")
    @EnumSource(PaymentMethod.class)
    @DisplayName("resolving the same method twice returns the same strategy, so a strategy holds no per payment state")
    void resolvingTheSameMethodTwiceReturnsTheSameStrategy(PaymentMethod method) {
        assertThat(factory.forMethod(method)).isSameAs(factory.forMethod(method));
    }

    @ParameterizedTest(name = "missing strategy for {0}")
    @EnumSource(PaymentMethod.class)
    @DisplayName("a payment method nobody implements breaks startup, naming the method, rather than failing a live payment")
    void aMethodWithNoStrategyBreaksStartup(PaymentMethod omitted) {
        List<PaymentStrategy> incomplete = allExcept(omitted);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new PaymentStrategyFactory(incomplete));

        assertThat(thrown).hasMessageContaining(omitted.name());
    }

    @Test
    @DisplayName("a deployment with no payment strategies at all refuses to start")
    void noStrategiesAtAllRefusesToStart() {
        assertThrows(IllegalStateException.class, () -> new PaymentStrategyFactory(List.of()));
    }

    @Test
    @DisplayName("two strategies claiming one method break startup, naming both classes so the duplicate is findable")
    void twoStrategiesClaimingOneMethodBreakStartup() {
        List<PaymentStrategy> clashing = new ArrayList<>(allStrategies());
        clashing.add(new StubStrategy(PaymentMethod.UPI));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new PaymentStrategyFactory(clashing));

        assertThat(thrown).hasMessageContaining(PaymentMethod.UPI.name())
                .hasMessageContaining(UpiPaymentStrategy.class.getSimpleName())
                .hasMessageContaining(StubStrategy.class.getSimpleName());
    }

    @Test
    @DisplayName("the clash is reported even when the duplicate arrives before the real strategy")
    void theClashIsReportedWhicheverOrderTheStrategiesArrive() {
        List<PaymentStrategy> clashing = new ArrayList<>();
        clashing.add(new StubStrategy(PaymentMethod.CARD));
        clashing.addAll(allStrategies());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new PaymentStrategyFactory(clashing));

        assertThat(thrown).hasMessageContaining(PaymentMethod.CARD.name());
    }

    @Test
    @DisplayName("a payment with no method asked for is refused as a bad request, not resolved to a default strategy")
    void aPaymentWithNoMethodIsRefused() {
        BusinessException thrown = assertThrows(BusinessException.class, () -> factory.forMethod(null));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_PAYMENT_METHOD);
        assertThat(thrown.errorCode().httpStatus().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("the refusal lists what can be collected, so a caller learns the supported methods from the error")
    void theRefusalListsTheSupportedMethods() {
        BusinessException thrown = assertThrows(BusinessException.class, () -> factory.forMethod(null));

        for (PaymentMethod method : PaymentMethod.values()) {
            assertThat(thrown.getMessage()).contains(method.name());
        }
    }

    @Test
    @DisplayName("cash is resolved to a strategy that needs no partner, while every other method is partner backed")
    void cashIsTheOnlyMethodThatNeedsNoPartner() {
        assertThat(factory.forMethod(PaymentMethod.CASH)).isNotInstanceOf(PartnerBackedPaymentStrategy.class);

        for (PaymentMethod method : PaymentMethod.values()) {
            if (method != PaymentMethod.CASH) {
                assertThat(factory.forMethod(method)).isInstanceOf(PartnerBackedPaymentStrategy.class);
            }
        }
    }

    /** A second claimant for a method, which is the only way to reach the duplicate guard. */
    private static final class StubStrategy implements PaymentStrategy {

        private final PaymentMethod method;

        private StubStrategy(PaymentMethod method) {
            this.method = method;
        }

        @Override
        public PaymentMethod method() {
            return method;
        }

        @Override
        public PaymentResult collect(PaymentRequest request) {
            return PaymentResult.success("STUB-" + request.rideId());
        }
    }
}
