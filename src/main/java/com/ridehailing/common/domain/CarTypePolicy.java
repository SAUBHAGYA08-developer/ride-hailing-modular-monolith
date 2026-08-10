package com.ridehailing.common.domain;

import java.util.List;
import java.util.Map;

/**
 * The single place that knows which vehicle may serve which request.
 * A HATCHBACK request may be upgraded to a SEDAN at no extra charge; the rider
 * is always billed for the car type they requested.
 */
public final class CarTypePolicy {

    private static final Map<CarType, List<CarType>> ACCEPTABLE = Map.of(
            CarType.HATCHBACK, List.of(CarType.HATCHBACK, CarType.SEDAN),
            CarType.SEDAN, List.of(CarType.SEDAN)
    );

    private CarTypePolicy() {
    }

    /** Car types that may serve the request, most preferred first. */
    public static List<CarType> acceptableFor(CarType requested) {
        return ACCEPTABLE.getOrDefault(requested, List.of(requested));
    }

    public static boolean isUpgrade(CarType requested, CarType assigned) {
        return assigned != null && assigned != requested;
    }
}
