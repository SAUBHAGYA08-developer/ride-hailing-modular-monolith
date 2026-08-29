package com.ridehailing.ride;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.ride.entity.RideStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ride lifecycle, pinned as a full transition matrix.
 *
 * The legal moves are restated here independently of the implementation, and
 * every one of the 25 ordered pairs is then checked against that statement, so
 * a new status or a widened rule cannot slip through unnoticed.
 */
class RideStateMachineTest {

    /** The specification: the only moves a ride is allowed to make. */
    private static final Set<Map.Entry<RideStatus, RideStatus>> LEGAL = Set.of(
            Map.entry(RideStatus.REQUESTED, RideStatus.DRIVER_ASSIGNED),
            Map.entry(RideStatus.REQUESTED, RideStatus.CANCELLED),
            Map.entry(RideStatus.DRIVER_ASSIGNED, RideStatus.STARTED),
            Map.entry(RideStatus.DRIVER_ASSIGNED, RideStatus.CANCELLED),
            Map.entry(RideStatus.STARTED, RideStatus.COMPLETED));

    static Stream<Arguments> everyOrderedPair() {
        return Arrays.stream(RideStatus.values())
                .flatMap(from -> Arrays.stream(RideStatus.values()).map(to -> Arguments.of(from, to)));
    }

    static Stream<Arguments> legalTransitions() {
        return LEGAL.stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    static Stream<Arguments> illegalTransitions() {
        return Arrays.stream(RideStatus.values())
                .flatMap(from -> Arrays.stream(RideStatus.values())
                        .filter(to -> !LEGAL.contains(Map.entry(from, to)))
                        .map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyOrderedPair")
    @DisplayName("a ride may only move along the five transitions the lifecycle defines, and no others")
    void theTransitionMatrixMatchesTheSpecification(RideStatus from, RideStatus to) {
        boolean expectedLegal = LEGAL.contains(Map.entry(from, to));

        assertThat(RideStateMachine.canTransition(from, to))
                .as("%s -> %s", from, to)
                .isEqualTo(expectedLegal);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("legalTransitions")
    @DisplayName("a legal transition is allowed to proceed without raising anything")
    void aLegalTransitionIsAllowed(RideStatus from, RideStatus to) {
        assertThatCode(() -> RideStateMachine.assertCanTransition(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("illegalTransitions")
    @DisplayName("an illegal transition is rejected as a conflict, not swallowed")
    void anIllegalTransitionIsRejectedWithTheStateTransitionErrorCode(RideStatus from, RideStatus to) {
        BusinessException thrown = assertThrows(BusinessException.class,
                () -> RideStateMachine.assertCanTransition(from, to));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.INVALID_RIDE_STATE_TRANSITION);
        assertThat(thrown.getMessage()).contains(from.name(), to.name());
    }

    @ParameterizedTest(name = "COMPLETED -> {0}")
    @EnumSource(RideStatus.class)
    @DisplayName("a completed ride is final: nothing can follow it, not even a cancellation")
    void completedIsTerminal(RideStatus to) {
        assertThat(RideStateMachine.canTransition(RideStatus.COMPLETED, to)).isFalse();
    }

    @ParameterizedTest(name = "CANCELLED -> {0}")
    @EnumSource(RideStatus.class)
    @DisplayName("a cancelled ride is final: it can never be revived or completed")
    void cancelledIsTerminal(RideStatus to) {
        assertThat(RideStateMachine.canTransition(RideStatus.CANCELLED, to)).isFalse();
    }

    @Test
    @DisplayName("a ride cannot be started before a driver has been assigned")
    void aRideCannotStartWithoutADriver() {
        assertThat(RideStateMachine.canTransition(RideStatus.REQUESTED, RideStatus.STARTED)).isFalse();
    }

    @Test
    @DisplayName("a ride already under way can no longer be cancelled")
    void aStartedRideCannotBeCancelled() {
        assertThat(RideStateMachine.canTransition(RideStatus.STARTED, RideStatus.CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("a ride cannot be completed without having been started")
    void aRideCannotCompleteWithoutStarting() {
        assertThat(RideStateMachine.canTransition(RideStatus.DRIVER_ASSIGNED, RideStatus.COMPLETED)).isFalse();
    }

    @ParameterizedTest(name = "status {0}")
    @EnumSource(RideStatus.class)
    @DisplayName("a status can never transition to itself, so a repeated call is not silently accepted")
    void noSelfTransition(RideStatus status) {
        assertThat(RideStateMachine.canTransition(status, status)).isFalse();
    }

    @Test
    @DisplayName("an unknown status on either side of the move is refused rather than assumed safe")
    void unknownStatusesAreRefused() {
        assertThat(RideStateMachine.canTransition(null, RideStatus.STARTED)).isFalse();
        assertThat(RideStateMachine.canTransition(RideStatus.DRIVER_ASSIGNED, null)).isFalse();
        assertThat(RideStateMachine.canTransition(null, null)).isFalse();

        assertThat(assertThrows(BusinessException.class,
                () -> RideStateMachine.assertCanTransition(null, RideStatus.STARTED)).errorCode())
                .isEqualTo(ErrorCode.INVALID_RIDE_STATE_TRANSITION);
    }
}
