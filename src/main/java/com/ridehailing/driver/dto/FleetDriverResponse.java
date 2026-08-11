package com.ridehailing.driver.dto;

import com.ridehailing.common.domain.CarType;
import com.ridehailing.driver.entity.DriverStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One driver as an operator sees them, with both notions of "live" side by side:
 * {@code status} is what MySQL has reserved, {@code reportingLocation} is whether
 * Redis still holds a fresh position for them.
 *
 * Phone, e-mail and licence number are deliberately absent. The question this
 * endpoint answers is how many drivers are live, which needs no personal data,
 * and a fleet-wide listing is the worst possible place to hand it out.
 *
 * {@code lastLocationAt} is the MySQL recovery snapshot, written only when a
 * driver changes status - not the last GPS ping. It explains a ghost row
 * ("last seen this morning"), it does not prove presence; only
 * {@code reportingLocation} does.
 */
public record FleetDriverResponse(
        Long driverId,
        String fullName,
        DriverStatus status,
        List<CarType> activeCarTypes,
        BigDecimal rating,
        int totalRides,
        boolean reportingLocation,
        boolean bookable,
        Instant lastLocationAt) {
}
