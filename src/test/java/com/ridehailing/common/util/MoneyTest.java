package com.ridehailing.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rounding, which every fare, discount and cancellation fee passes through.
 *
 * The scale is asserted alongside the value: two amounts that compare equal but
 * carry different scales are the same money, yet only one of them matches the
 * DECIMAL(x,2) columns the platform stores.
 */
class MoneyTest {

    @ParameterizedTest(name = "{0} is stored as {1}")
    @CsvSource({
            "10,      10.00",
            "10.1,    10.10",
            "10.004,  10.00",
            // half up, so the half penny goes to the platform rather than vanishing
            "10.005,  10.01",
            "10.006,  10.01",
            "0.001,   0.00",
            "-10.005, -10.01"
    })
    @DisplayName("an amount is rounded half up to the two decimal places money is stored in")
    void amountsAreRoundedHalfUpToTwoPlaces(BigDecimal value, BigDecimal expected) {
        BigDecimal rounded = Money.round(value);

        assertThat(rounded).isEqualByComparingTo(expected);
        assertThat(rounded.scale()).isEqualTo(Money.SCALE);
    }

    @Test
    @DisplayName("money is always held to two decimal places")
    void moneyScaleIsTwo() {
        assertThat(Money.SCALE).isEqualTo(2);
    }

    @Test
    @DisplayName("an absent amount stays absent instead of silently becoming zero")
    void anAbsentAmountIsNotTurnedIntoZero() {
        assertThat(Money.round(null)).isNull();
    }

    @Test
    @DisplayName("a zero amount is a real, correctly scaled zero")
    void zeroIsScaledLikeEveryOtherAmount() {
        BigDecimal zero = Money.zero();

        assertThat(zero).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.scale()).isEqualTo(Money.SCALE);
        assertThat(zero.signum()).isZero();
    }

    @ParameterizedTest(name = "{0} is clamped to {1}")
    @CsvSource({
            "-0.01,   0.00",
            "-1,      0.00",
            "-999.99, 0.00",
            "0,       0.00",
            "5.005,   5.01",
            "5.004,   5.00"
    })
    @DisplayName("no amount charged to or paid by anybody is ever negative")
    void negativeAmountsAreClampedToZero(BigDecimal value, BigDecimal expected) {
        BigDecimal amount = Money.nonNegative(value);

        assertThat(amount).isEqualByComparingTo(expected);
        assertThat(amount.signum()).isGreaterThanOrEqualTo(0);
        assertThat(amount.scale()).isEqualTo(Money.SCALE);
    }
}
