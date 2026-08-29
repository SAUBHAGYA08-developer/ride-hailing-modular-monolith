package com.ridehailing.ride.matching;

import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.ride.PickupEta;
import com.ridehailing.ride.repository.RideOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Score = w1*ETA + w2*Rating + w3*AcceptanceRate, each signal min-max
 * normalised to [0,1] across the candidate pool before weighting - combining
 * the raw values directly would let ETA (minutes, can run into double digits)
 * dominate rating (0-5) and acceptance (0-1) purely on scale, and would rank
 * slower drivers higher besides, since lower ETA is better but higher rating
 * and acceptance are.
 *
 * Registered alongside NearestDriverMatchingStrategy but not made the default;
 * an admin opts in by setting matching.strategy=SCORE.
 */
@Component
@RequiredArgsConstructor
public class ScoredDriverMatchingStrategy implements DriverMatchingStrategy {

    public static final String NAME = "SCORE";

    private static final int DEFAULT_PICKUP_SPEED_KMPH = 20;
    private static final BigDecimal DEFAULT_WEIGHT_ETA = new BigDecimal("0.4");
    private static final BigDecimal DEFAULT_WEIGHT_RATING = new BigDecimal("0.3");
    private static final BigDecimal DEFAULT_WEIGHT_ACCEPTANCE = new BigDecimal("0.3");

    private final RideOfferRepository rideOfferRepository;
    private final ConfigurationService configurationService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<DriverCandidate> rank(List<DriverCandidate> candidates) {
        if (candidates.size() <= 1) {
            // Nothing to discriminate between; also sidesteps a zero-width normalisation range.
            return List.copyOf(candidates);
        }

        int speedKmph = configurationService.getInt(ConfigKeys.PICKUP_AVERAGE_SPEED_KMPH, DEFAULT_PICKUP_SPEED_KMPH);
        if (speedKmph <= 0) {
            speedKmph = DEFAULT_PICKUP_SPEED_KMPH;
        }
        double weightEta = configurationService.getDecimal(
                ConfigKeys.MATCHING_SCORE_WEIGHT_ETA, DEFAULT_WEIGHT_ETA).doubleValue();
        double weightRating = configurationService.getDecimal(
                ConfigKeys.MATCHING_SCORE_WEIGHT_RATING, DEFAULT_WEIGHT_RATING).doubleValue();
        double weightAcceptance = configurationService.getDecimal(
                ConfigKeys.MATCHING_SCORE_WEIGHT_ACCEPTANCE, DEFAULT_WEIGHT_ACCEPTANCE).doubleValue();

        Map<Long, Double> acceptanceByDriver = acceptanceRates(candidates);
        int finalSpeedKmph = speedKmph;
        List<Scored> scored = candidates.stream()
                .map(c -> new Scored(c,
                        PickupEta.minutesFor(BigDecimal.valueOf(c.distanceKm()), finalSpeedKmph),
                        c.rating() == null ? 0.0 : c.rating().doubleValue(),
                        acceptanceByDriver.getOrDefault(c.driverId(), 1.0)))
                .toList();

        DoubleRange etaRange = DoubleRange.of(scored, Scored::eta);
        DoubleRange ratingRange = DoubleRange.of(scored, Scored::rating);
        DoubleRange acceptanceRange = DoubleRange.of(scored, Scored::acceptance);

        return scored.stream()
                .sorted(Comparator.<Scored>comparingDouble(s ->
                        weightEta * (1.0 - etaRange.normalize(s.eta()))
                                + weightRating * ratingRange.normalize(s.rating())
                                + weightAcceptance * acceptanceRange.normalize(s.acceptance()))
                        .reversed())
                .map(Scored::candidate)
                .toList();
    }

    private Map<Long, Double> acceptanceRates(List<DriverCandidate> candidates) {
        List<Long> driverIds = candidates.stream().map(DriverCandidate::driverId).toList();
        return rideOfferRepository.acceptanceStats(driverIds).stream()
                .collect(Collectors.toMap(DriverAcceptanceStats::driverId, DriverAcceptanceStats::acceptanceRate));
    }

    private record Scored(DriverCandidate candidate, double eta, double rating, double acceptance) {
    }

    /** A tied pool (min == max, including a single value) can't discriminate on that signal, so it normalises to 1 for everyone. */
    private record DoubleRange(double min, double max) {
        static <T> DoubleRange of(List<T> items, java.util.function.ToDoubleFunction<T> extractor) {
            double min = items.stream().mapToDouble(extractor).min().orElse(0);
            double max = items.stream().mapToDouble(extractor).max().orElse(0);
            return new DoubleRange(min, max);
        }

        double normalize(double value) {
            return max - min <= 0 ? 1.0 : (value - min) / (max - min);
        }
    }
}
