package com.ridehailing.ride.matching;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Shortest pickup leg wins; an equal distance is settled by the better rated driver. */
@Component
public class NearestDriverMatchingStrategy implements DriverMatchingStrategy {

    public static final String NAME = "NEAREST";

    private static final Comparator<DriverCandidate> ORDER =
            Comparator.comparingDouble(DriverCandidate::distanceKm)
                    .thenComparing(DriverCandidate::rating, Comparator.nullsLast(Comparator.reverseOrder()));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<DriverCandidate> rank(List<DriverCandidate> candidates) {
        return candidates.stream().sorted(ORDER).toList();
    }
}
