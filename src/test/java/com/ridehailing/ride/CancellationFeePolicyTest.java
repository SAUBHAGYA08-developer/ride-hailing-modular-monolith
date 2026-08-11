package com.ridehailing.ride;

import com.ridehailing.ride.entity.CancelledBy;
import com.ridehailing.ride.entity.RideStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cancellation fee rule, exercised as a pure function.
 *
 * Every case passes explicit instants rather than reading the clock, so the
 * grace window boundary is asserted exactly and nothing here can go flaky.
 * Amounts are compared with {@code isEqualByComparingTo} because a fee of
 * {@code 30.00} and a fee of {@code 30} are the same money.
 */
class CancellationFeePolicyTest {

    private static final BigDecimal FEE = new BigDecimal("30.00");
    private static final long GRACE_SECONDS = 120;
    private static final Instant ASSIGNED_AT = Instant.parse("2026-08-11T10:00:00Z");

    private static BigDecimal feeAfterHolding(long heldSeconds) {
        return CancellationFeePolicy.feeFor(CancelledBy.USER, RideStatus.DRIVER_ASSIGNED,
                ASSIGNED_AT, ASSIGNED_AT.plusSeconds(heldSeconds), FEE, GRACE_SECONDS);
    }

    @Test
    @DisplayName("a rider who cancels inside the grace window is not charged")
    void noFeeInsideGraceWindow() {
        assertThat(feeAfterHolding(0)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(feeAfterHolding(60)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a rider who cancels after holding a driver past the grace window pays the configured fee")
    void feeChargedOutsideGraceWindow() {
        assertThat(feeAfterHolding(600)).isEqualByComparingTo(FEE);
    }

    @ParameterizedTest(name = "held {0}s of a {1}s grace window -> fee {2}")
    @CsvSource({
            // one second inside the window is still free
            "119, 120, 0.00",
            // the boundary itself is chargeable: the window is "less than grace"
            "120, 120, 30.00",
            // one second outside is chargeable
            "121, 120, 30.00"
    })
    @DisplayName("the grace window boundary is exact: at the grace second itself the rider already pays")
    void graceWindowBoundaryIsExact(long heldSeconds, long graceSeconds, BigDecimal expectedFee) {
        BigDecimal fee = CancellationFeePolicy.feeFor(CancelledBy.USER, RideStatus.DRIVER_ASSIGNED,
                ASSIGNED_AT, ASSIGNED_AT.plusSeconds(heldSeconds), FEE, graceSeconds);

        assertThat(fee).isEqualByComparingTo(expectedFee);
    }

    @Test
    @DisplayName("a zero grace window charges a rider who cancels the very instant a driver is assigned")
    void zeroGraceWindowChargesImmediately() {
        BigDecimal fee = CancellationFeePolicy.feeFor(CancelledBy.USER, RideStatus.DRIVER_ASSIGNED,
                ASSIGNED_AT, ASSIGNED_AT, FEE, 0);

        assertThat(fee).isEqualByComparingTo(FEE);
    }

    @ParameterizedTest(name = "cancelled by {0}")
    @EnumSource(value = CancelledBy.class, names = {"DRIVER", "ADMIN", "SYSTEM"})
    @DisplayName("a rider never pays for someone else's cancellation, however long the driver was held")
    void onlyTheRiderIsEverCharged(CancelledBy cancelledBy) {
        BigDecimal fee = CancellationFeePolicy.feeFor(cancelledBy, RideStatus.DRIVER_ASSIGNED,
                ASSIGNED_AT, ASSIGNED_AT.plusSeconds(3600), FEE, GRACE_SECONDS);

        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "status before cancel was {0}")
    @EnumSource(value = RideStatus.class, names = "DRIVER_ASSIGNED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("nothing is charged unless a driver had actually been dispatched")
    void noFeeWhenNoDriverWasDispatched(RideStatus statusBeforeCancel) {
        BigDecimal fee = CancellationFeePolicy.feeFor(CancelledBy.USER, statusBeforeCancel,
                ASSIGNED_AT, ASSIGNED_AT.plusSeconds(3600), FEE, GRACE_SECONDS);

        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a ride that never recorded an assignment timestamp cannot be charged")
    void noFeeWhenAssignedAtIsUnknown() {
        BigDecimal fee = CancellationFeePolicy.feeFor(CancelledBy.USER, RideStatus.DRIVER_ASSIGNED,
                null, ASSIGNED_AT.plusSeconds(3600), FEE, GRACE_SECONDS);

        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a missing cancellation timestamp is not guessed at, so nothing is charged")
    void noFeeWhenCancelledAtIsUnknown() {
        BigDecimal fee = CancellationFeePolicy.feeFor(CancelledBy.USER, RideStatus.DRIVER_ASSIGNED,
                ASSIGNED_AT, null, FEE, GRACE_SECONDS);

        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "configured fee {0}")
    @ValueSource(strings = {"0", "0.00", "-1.00"})
    @DisplayName("switching the fee off by configuring it to zero or below charges nobody")
    void noFeeWhenTheFeeIsSwitchedOff(String configuredFee) {
        BigDecimal fee = CancellationFeePolicy.feeFor(CancelledBy.USER, RideStatus.DRIVER_ASSIGNED,
                ASSIGNED_AT, ASSIGNED_AT.plusSeconds(3600), new BigDecimal(configuredFee), GRACE_SECONDS);

        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("an unconfigured fee charges nobody instead of failing the cancellation")
    void noFeeWhenTheFeeIsNotConfigured() {
        BigDecimal fee = CancellationFeePolicy.feeFor(CancelledBy.USER, RideStatus.DRIVER_ASSIGNED,
                ASSIGNED_AT, ASSIGNED_AT.plusSeconds(3600), null, GRACE_SECONDS);

        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("clock skew that puts the cancellation before the assignment fails in the rider's favour")
    void clockSkewFailsInTheRidersFavour() {
        BigDecimal fee = CancellationFeePolicy.feeFor(CancelledBy.USER, RideStatus.DRIVER_ASSIGNED,
                ASSIGNED_AT, ASSIGNED_AT.minusSeconds(5), FEE, GRACE_SECONDS);

        assertThat(fee).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a charged fee is rounded to the two decimal places money is stored in")
    void chargedFeeIsRoundedToMoneyScale() {
        BigDecimal fee = CancellationFeePolicy.feeFor(CancelledBy.USER, RideStatus.DRIVER_ASSIGNED,
                ASSIGNED_AT, ASSIGNED_AT.plusSeconds(3600), new BigDecimal("30.005"), GRACE_SECONDS);

        assertThat(fee.scale()).isEqualTo(2);
        assertThat(fee).isEqualByComparingTo("30.01");
    }

    @Test
    @DisplayName("a free cancellation still returns a real zero amount, never null")
    void freeCancellationReturnsAZeroAmount() {
        BigDecimal fee = feeAfterHolding(0);

        assertThat(fee).isNotNull();
        assertThat(fee.scale()).isEqualTo(2);
        assertThat(fee.signum()).isZero();
    }
}
