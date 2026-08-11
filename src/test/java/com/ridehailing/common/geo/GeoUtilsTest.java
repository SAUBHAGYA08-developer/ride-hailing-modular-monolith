package com.ridehailing.common.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Coordinate validation and great circle distance.
 *
 * Distances are asserted against independently computed reference values with a
 * tolerance rather than against exact decimals: the point is that the platform
 * measures real kilometres, not that it reproduces one particular floating point
 * expansion. Coordinates are public Bengaluru landmarks, no user data.
 */
class GeoUtilsTest {

    /** Kilometres of slack allowed against the reference figures. */
    private static final double TOLERANCE_KM = 0.1;

    @ParameterizedTest(name = "{4}: ({0},{1}) -> ({2},{3}) is about {5} km")
    @CsvSource({
            // Majestic -> Kempegowda International Airport
            "12.9784, 77.5726, 13.1986, 77.7066, 'city centre to airport',      28.463",
            // MG Road -> Electronic City
            "12.9757, 77.6069, 12.8452, 77.6602, 'MG Road to Electronic City',  15.619",
            // Koramangala -> Indiranagar
            "12.9352, 77.6245, 12.9784, 77.6408, 'Koramangala to Indiranagar',   5.118"
    })
    @DisplayName("the distance between two real Bengaluru points matches the great circle distance in kilometres")
    void distanceMatchesKnownBengaluruLegs(double fromLat, double fromLng, double toLat, double toLng,
                                           String leg, double expectedKm) {
        BigDecimal distance = GeoUtils.distanceKm(fromLat, fromLng, toLat, toLng);

        assertThat(distance.doubleValue())
                .as("%s", leg)
                .isCloseTo(expectedKm, within(TOLERANCE_KM));
    }

    @Test
    @DisplayName("the result is expressed in kilometres, so one degree of latitude is about 111 km")
    void resultIsInKilometres() {
        BigDecimal oneDegreeOfLatitude = GeoUtils.distanceKm(12.0, 77.0, 13.0, 77.0);

        assertThat(oneDegreeOfLatitude.doubleValue()).isCloseTo(111.195, within(0.5));
    }

    @Test
    @DisplayName("a trip that starts and ends at the same point covers no distance at all")
    void samePointIsZeroDistance() {
        BigDecimal distance = GeoUtils.distanceKm(new BigDecimal("12.9757"), new BigDecimal("77.6069"),
                new BigDecimal("12.9757"), new BigDecimal("77.6069"));

        assertThat(distance).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(distance.signum()).isZero();
    }

    @Test
    @DisplayName("distance does not depend on the direction of travel")
    void distanceIsSymmetric() {
        BigDecimal there = GeoUtils.distanceKm(12.9784, 77.5726, 13.1986, 77.7066);
        BigDecimal back = GeoUtils.distanceKm(13.1986, 77.7066, 12.9784, 77.5726);

        assertThat(there).isEqualByComparingTo(back);
    }

    @Test
    @DisplayName("the decimal and primitive overloads measure the same trip identically")
    void bothOverloadsAgree() {
        BigDecimal fromDecimals = GeoUtils.distanceKm(new BigDecimal("12.9757"), new BigDecimal("77.6069"),
                new BigDecimal("12.8452"), new BigDecimal("77.6602"));
        BigDecimal fromDoubles = GeoUtils.distanceKm(12.9757, 77.6069, 12.8452, 77.6602);

        assertThat(fromDecimals).isEqualByComparingTo(fromDoubles);
    }

    @Test
    @DisplayName("every distance is reported to metre precision, so a stored fare distance is stable")
    void distanceIsAlwaysReportedToMetrePrecision() {
        assertThat(GeoUtils.distanceKm(12.9757, 77.6069, 12.8452, 77.6602).scale())
                .isEqualTo(GeoUtils.DISTANCE_SCALE);
        assertThat(GeoUtils.distanceKm(12.9757, 77.6069, 12.9757, 77.6069).scale())
                .isEqualTo(GeoUtils.DISTANCE_SCALE);
        assertThat(GeoUtils.DISTANCE_SCALE).isEqualTo(3);
    }

    @ParameterizedTest(name = "latitude {0} is accepted")
    @ValueSource(strings = {"-90", "-90.000000", "0", "12.9757", "90", "89.999999"})
    @DisplayName("a latitude anywhere on the globe, poles included, is accepted")
    void validLatitudesAreAccepted(BigDecimal latitude) {
        assertThat(GeoUtils.isValidLatitude(latitude)).isTrue();
    }

    @ParameterizedTest(name = "latitude {0} is rejected")
    @ValueSource(strings = {"-90.000001", "90.000001", "-91", "91", "180", "-1000"})
    @DisplayName("a latitude past either pole is rejected rather than silently wrapped")
    void outOfRangeLatitudesAreRejected(BigDecimal latitude) {
        assertThat(GeoUtils.isValidLatitude(latitude)).isFalse();
    }

    @ParameterizedTest(name = "longitude {0} is accepted")
    @ValueSource(strings = {"-180", "-180.000000", "0", "77.6069", "180", "179.999999"})
    @DisplayName("a longitude anywhere on the globe, both edges of the antimeridian included, is accepted")
    void validLongitudesAreAccepted(BigDecimal longitude) {
        assertThat(GeoUtils.isValidLongitude(longitude)).isTrue();
    }

    @ParameterizedTest(name = "longitude {0} is rejected")
    @ValueSource(strings = {"-180.000001", "180.000001", "-181", "181", "1000"})
    @DisplayName("a longitude past the antimeridian is rejected rather than silently wrapped")
    void outOfRangeLongitudesAreRejected(BigDecimal longitude) {
        assertThat(GeoUtils.isValidLongitude(longitude)).isFalse();
    }

    @Test
    @DisplayName("a missing coordinate is never treated as a valid location")
    void missingCoordinatesAreInvalid() {
        assertThat(GeoUtils.isValidLatitude(null)).isFalse();
        assertThat(GeoUtils.isValidLongitude(null)).isFalse();
    }
}
