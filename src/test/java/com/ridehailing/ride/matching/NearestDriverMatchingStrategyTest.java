package com.ridehailing.ride.matching;

import com.ridehailing.common.domain.CarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the platform picks who gets the ride.
 *
 * Every case builds synthetic candidates and asserts the resulting order by
 * driver id, so the assertions describe the ranking rule rather than the
 * comparator's implementation.
 */
class NearestDriverMatchingStrategyTest {

    private final NearestDriverMatchingStrategy strategy = new NearestDriverMatchingStrategy();

    private static DriverCandidate candidate(long driverId, double distanceKm, String rating) {
        return new DriverCandidate(driverId, driverId * 10, CarType.SEDAN,
                rating == null ? null : new BigDecimal(rating), distanceKm, 1L);
    }

    /** The ranking, expressed as the order drivers would actually be offered the ride in. */
    private static List<Long> offerOrder(List<DriverCandidate> ranked) {
        return ranked.stream().map(DriverCandidate::driverId).toList();
    }

    @Test
    @DisplayName("the strategy is registered under the name configuration selects it by")
    void theStrategyIsNamedNearest() {
        assertThat(strategy.name()).isEqualTo("NEAREST");
        assertThat(strategy.name()).isEqualTo(NearestDriverMatchingStrategy.NAME);
    }

    @Test
    @DisplayName("the driver with the shortest pickup leg is offered the ride first")
    void nearestDriverWinsFirst() {
        List<DriverCandidate> ranked = strategy.rank(List.of(
                candidate(1, 9.0, "5.0"),
                candidate(2, 0.5, "4.1"),
                candidate(3, 3.2, "4.9")));

        assertThat(offerOrder(ranked)).containsExactly(2L, 3L, 1L);
    }

    @Test
    @DisplayName("a nearer driver beats a better rated one: distance decides before rating does")
    void distanceOutweighsRating() {
        List<DriverCandidate> ranked = strategy.rank(List.of(
                candidate(1, 4.0, "5.0"),
                candidate(2, 1.0, "3.0")));

        assertThat(offerOrder(ranked)).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("two drivers the same distance away are separated by the better rating")
    void equalDistanceIsSettledByRating() {
        List<DriverCandidate> ranked = strategy.rank(List.of(
                candidate(1, 2.5, "4.2"),
                candidate(2, 2.5, "4.9"),
                candidate(3, 2.5, "4.5")));

        assertThat(offerOrder(ranked)).containsExactly(2L, 3L, 1L);
    }

    @Test
    @DisplayName("an unrated driver is ranked behind rated drivers at the same distance, not ahead of them")
    void unratedDriversRankLastAtEqualDistance() {
        List<DriverCandidate> ranked = strategy.rank(List.of(
                candidate(1, 2.5, null),
                candidate(2, 2.5, "4.0"),
                candidate(3, 2.5, null),
                candidate(4, 2.5, "4.8")));

        assertThat(offerOrder(ranked)).containsExactly(4L, 2L, 1L, 3L);
    }

    @Test
    @DisplayName("a brand new driver with no rating yet still wins if they are the closest")
    void anUnratedButNearerDriverStillWins() {
        List<DriverCandidate> ranked = strategy.rank(List.of(
                candidate(1, 6.0, "5.0"),
                candidate(2, 0.8, null)));

        assertThat(offerOrder(ranked)).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("candidates that cannot be separated by distance or rating keep the order they arrived in")
    void rankingIsStableForIndistinguishableCandidates() {
        List<DriverCandidate> ranked = strategy.rank(List.of(
                candidate(1, 2.5, "4.50"),
                candidate(2, 2.5, "4.5"),
                candidate(3, 2.5, "4.500")));

        assertThat(offerOrder(ranked)).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("two unrated drivers at the same distance keep the order they arrived in")
    void rankingIsStableForUnratedCandidates() {
        List<DriverCandidate> ranked = strategy.rank(List.of(
                candidate(7, 1.0, null),
                candidate(8, 1.0, null)));

        assertThat(offerOrder(ranked)).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("ranking no candidates yields no candidates rather than failing")
    void emptyCandidateListRanksToEmpty() {
        assertThat(strategy.rank(List.of())).isEmpty();
    }

    @Test
    @DisplayName("a single candidate is returned unchanged")
    void singleCandidateIsReturnedAsIs() {
        DriverCandidate only = candidate(1, 3.3, "4.4");

        assertThat(strategy.rank(List.of(only))).containsExactly(only);
    }

    @Test
    @DisplayName("ranking never drops or invents a candidate")
    void rankingPreservesTheCandidateSet() {
        List<DriverCandidate> candidates = List.of(
                candidate(1, 9.0, "5.0"),
                candidate(2, 0.5, null),
                candidate(3, 3.2, "4.9"));

        List<DriverCandidate> ranked = strategy.rank(candidates);

        assertThat(ranked).hasSameSizeAs(candidates).containsExactlyInAnyOrderElementsOf(candidates);
    }

    @Test
    @DisplayName("ranking returns a new list and leaves the caller's list untouched")
    void theInputListIsNeverMutated() {
        List<DriverCandidate> candidates = new ArrayList<>(List.of(
                candidate(1, 9.0, "5.0"),
                candidate(2, 0.5, "4.1")));

        strategy.rank(candidates);

        assertThat(offerOrder(candidates)).containsExactly(1L, 2L);
    }
}
