package com.ridehailing.driver.dto;

import com.ridehailing.driver.entity.DriverStatus;

import java.util.Map;

/**
 * Fleet-wide counts. Every figure covers the whole fleet, never the current
 * page, so paging through the listing never changes the numbers.
 *
 * The system holds two independent notions of a live driver and this summary
 * exists to show whether they agree:
 * <ul>
 *   <li>{@code byStatus} - the authoritative reservation state in MySQL, with
 *       every {@link DriverStatus} present even at zero so a dashboard does not
 *       have to guess whether a missing key means none or means broken.</li>
 *   <li>{@code reportingLocation} - drivers with a fresh position in the Redis
 *       GEO set, which is the only thing dispatch can actually match against.</li>
 *   <li>{@code onDutyWithoutLiveLocation} - the gap: on duty in MySQL but
 *       invisible to dispatch. These are ghosts (app killed, phone asleep, no
 *       ping inside the TTL) and they will never be offered a ride, which is
 *       exactly the failure an operator otherwise hears about from complaints
 *       instead of from a number.</li>
 *   <li>{@code bookable} - AVAILABLE, reporting a live position, and holding at
 *       least one active vehicle. This is the honest answer to "how many drivers
 *       can take a ride right now".</li>
 * </ul>
 *
 * {@code locationTtlSeconds} travels with the counts because without it the
 * reader cannot tell how stale a position had to get before it was dropped.
 */
public record FleetSummaryResponse(
        long totalDrivers,
        Map<DriverStatus, Long> byStatus,
        long reportingLocation,
        long onDutyWithoutLiveLocation,
        long bookable,
        int locationTtlSeconds) {
}
