package com.ridehailing.ride;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one formula for turning a pickup distance into a minutes estimate,
 * shared by RideMapper (what a rider/driver is shown) and the SCORE matching
 * strategy (what ranks candidates) so the two can never drift apart.
 */
public final class PickupEta {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private PickupEta() {
    }

    /** Rounded up, since an ETA that rounds down promises a driver who cannot arrive that soon. Caller resolves speedKmph &gt; 0. */
    public static int minutesFor(BigDecimal distanceKm, int speedKmph) {
        return distanceKm.multiply(MINUTES_PER_HOUR)
                .divide(BigDecimal.valueOf(speedKmph), 0, RoundingMode.CEILING)
                .intValue();
    }
}
