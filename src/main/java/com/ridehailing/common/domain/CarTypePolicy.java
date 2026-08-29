package com.ridehailing.common.domain;

import java.util.List;
import java.util.Map;

/** Which vehicle may serve which request; substitution never crosses a category family. */
public final class CarTypePolicy {

    private static final Map<CarType, List<CarType>> ACCEPTABLE = Map.of(
            CarType.HATCHBACK, List.of(CarType.HATCHBACK, CarType.SEDAN),
            CarType.SEDAN, List.of(CarType.SEDAN),
            CarType.AUTO, List.of(CarType.AUTO),
            CarType.BIKE, List.of(CarType.BIKE)
    );

    /** Ranks upgrades against downgrades only; the fare comes from the pricing rule, not from here. */
    private static final Map<CarType, Integer> RANK = Map.of(
            CarType.BIKE, 0,
            CarType.AUTO, 1,
            CarType.HATCHBACK, 2,
            CarType.SEDAN, 3
    );

    private CarTypePolicy() {
    }

    /** Most preferred first; an unmapped category safely serves only itself. */
    public static List<CarType> acceptableFor(CarType requested) {
        return ACCEPTABLE.getOrDefault(requested, List.of(requested));
    }

    /** Rank based, so a downgrade is never reported as an upgrade on the ride response. */
    public static boolean isUpgrade(CarType requested, CarType assigned) {
        if (requested == null || assigned == null || assigned == requested) {
            return false;
        }
        return RANK.getOrDefault(assigned, Integer.MIN_VALUE) > RANK.getOrDefault(requested, Integer.MIN_VALUE);
    }
}
