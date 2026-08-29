package com.ridehailing.ride;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.ride.entity.RideStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The only definition of which ride status may follow which. Holding it here
 * instead of scattering it over the lifecycle service makes COMPLETED and
 * CANCELLED terminal by construction rather than by convention.
 */
public final class RideStateMachine {

    private static final Map<RideStatus, Set<RideStatus>> ALLOWED = Map.of(
            RideStatus.REQUESTED, EnumSet.of(RideStatus.DRIVER_ASSIGNED, RideStatus.CANCELLED),
            RideStatus.DRIVER_ASSIGNED, EnumSet.of(RideStatus.STARTED, RideStatus.CANCELLED),
            RideStatus.STARTED, EnumSet.of(RideStatus.COMPLETED),
            RideStatus.COMPLETED, EnumSet.noneOf(RideStatus.class),
            RideStatus.CANCELLED, EnumSet.noneOf(RideStatus.class));

    private RideStateMachine() {
    }

    public static boolean canTransition(RideStatus from, RideStatus to) {
        return from != null && to != null && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertCanTransition(RideStatus from, RideStatus to) {
        if (!canTransition(from, to)) {
            throw new BusinessException(ErrorCode.INVALID_RIDE_STATE_TRANSITION,
                    "A ride cannot move from " + from + " to " + to);
        }
    }
}
