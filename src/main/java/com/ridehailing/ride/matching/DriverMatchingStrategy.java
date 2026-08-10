package com.ridehailing.ride.matching;

import java.util.List;

/**
 * Orders bookable drivers for one request. Implementations are Spring beans
 * chosen by name from configuration, so changing how the platform matches is a
 * configuration change rather than a deployment.
 */
public interface DriverMatchingStrategy {

    /** Identifier stored in the matching.strategy configuration row. */
    String name();

    /** Returns a new list, best candidate first. The input is never mutated. */
    List<DriverCandidate> rank(List<DriverCandidate> candidates);
}
