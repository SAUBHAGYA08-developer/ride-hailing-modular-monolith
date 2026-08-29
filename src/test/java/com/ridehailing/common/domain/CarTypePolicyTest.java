package com.ridehailing.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which vehicle may serve which request.
 *
 * The order of the returned list is the business rule, not a detail: matching
 * walks it front to back, so {@code containsExactly} (order sensitive) is used
 * throughout rather than {@code containsExactlyInAnyOrder}.
 */
class CarTypePolicyTest {

    @Test
    @DisplayName("a hatchback request prefers a hatchback but accepts a free upgrade to a sedan")
    void hatchbackPrefersHatchbackThenAcceptsSedan() {
        assertThat(CarTypePolicy.acceptableFor(CarType.HATCHBACK))
                .containsExactly(CarType.HATCHBACK, CarType.SEDAN);
    }

    @Test
    @DisplayName("a sedan request is only ever served by a sedan, never downgraded to a hatchback")
    void sedanIsNeverDowngraded() {
        assertThat(CarTypePolicy.acceptableFor(CarType.SEDAN))
                .containsExactly(CarType.SEDAN);
        assertThat(CarTypePolicy.acceptableFor(CarType.SEDAN))
                .doesNotContain(CarType.HATCHBACK);
    }

    @ParameterizedTest(name = "requested {0}")
    @EnumSource(CarType.class)
    @DisplayName("every car type is at least served by itself, and is always its own first preference")
    void everyCarTypeIsItsOwnFirstPreference(CarType requested) {
        List<CarType> acceptable = CarTypePolicy.acceptableFor(requested);

        assertThat(acceptable).isNotEmpty();
        assertThat(acceptable.get(0)).isEqualTo(requested);
        assertThat(acceptable).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the acceptable list cannot be mutated by a caller, so the policy is safe to share")
    void theAcceptableListIsImmutable() {
        List<CarType> acceptable = CarTypePolicy.acceptableFor(CarType.HATCHBACK);

        assertThatThrownBy(() -> acceptable.add(CarType.SEDAN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a hatchback rider served by a sedan has been upgraded, and the ride says so")
    void servingAHatchbackWithASedanIsAnUpgrade() {
        assertThat(CarTypePolicy.isUpgrade(CarType.HATCHBACK, CarType.SEDAN)).isTrue();
    }

    @ParameterizedTest(name = "requested and assigned {0}")
    @EnumSource(CarType.class)
    @DisplayName("getting exactly the car type that was requested is not an upgrade")
    void gettingWhatWasRequestedIsNotAnUpgrade(CarType carType) {
        assertThat(CarTypePolicy.isUpgrade(carType, carType)).isFalse();
    }

    @ParameterizedTest(name = "requested {0}")
    @EnumSource(CarType.class)
    @DisplayName("a ride with no car assigned yet is not reported as an upgrade")
    void anUnassignedRideIsNotAnUpgrade(CarType requested) {
        assertThat(CarTypePolicy.isUpgrade(requested, null)).isFalse();
    }

    @ParameterizedTest(name = "requested {0} may be served by {1}: {2}")
    @CsvSource({
            "HATCHBACK, HATCHBACK, true",
            "HATCHBACK, SEDAN,     true",
            "SEDAN,     SEDAN,     true",
            "SEDAN,     HATCHBACK, false"
    })
    @DisplayName("the substitution matrix is one directional: hatchback to sedan only")
    void theSubstitutionMatrixIsOneDirectional(CarType requested, CarType assigned, boolean acceptable) {
        assertThat(CarTypePolicy.acceptableFor(requested).contains(assigned)).isEqualTo(acceptable);
    }

    @ParameterizedTest(name = "requested {0}")
    @CsvSource({"AUTO", "BIKE"})
    @DisplayName("an auto or bike request is served only by its own category, never by a car")
    void autoAndBikeAreAFamilyOfOne(CarType requested) {
        assertThat(CarTypePolicy.acceptableFor(requested)).containsExactly(requested);
        assertThat(CarTypePolicy.acceptableFor(requested))
                .doesNotContain(CarType.SEDAN, CarType.HATCHBACK);
    }

    @ParameterizedTest(name = "requested {0} is never served by {1}")
    @CsvSource({
            "BIKE,      AUTO",
            "BIKE,      SEDAN",
            "AUTO,      BIKE",
            "AUTO,      HATCHBACK",
            "HATCHBACK, AUTO",
            "SEDAN,     BIKE"
    })
    @DisplayName("substitution never crosses a category family, however near the other vehicle is")
    void substitutionNeverCrossesACategoryFamily(CarType requested, CarType assigned) {
        assertThat(CarTypePolicy.acceptableFor(requested)).doesNotContain(assigned);
    }

    @ParameterizedTest(name = "requested {0}, assigned {1} -> upgrade {2}")
    @CsvSource({
            "HATCHBACK, SEDAN,     true",
            "BIKE,      AUTO,      true",
            "AUTO,      SEDAN,     true",
            "SEDAN,     HATCHBACK, false",
            "SEDAN,     BIKE,      false",
            "AUTO,      BIKE,      false"
    })
    @DisplayName("only a higher ranked category counts as an upgrade, so a downgrade is never mislabelled")
    void onlyAHigherRankedCategoryIsAnUpgrade(CarType requested, CarType assigned, boolean upgrade) {
        assertThat(CarTypePolicy.isUpgrade(requested, assigned)).isEqualTo(upgrade);
    }
}
