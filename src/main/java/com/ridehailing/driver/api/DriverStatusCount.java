package com.ridehailing.driver.api;

import com.ridehailing.driver.entity.DriverStatus;

/**
 * One row of the driver headcount grouped by reservation status.
 *
 * The fleet totals are produced by a GROUP BY rather than counted off the
 * current page, so they stay correct whichever page the caller asked for.
 *
 * {@code count} is the wrapper type because JPQL {@code count(...)} is typed
 * Long: an exact match keeps Hibernate's constructor lookup unambiguous.
 */
public record DriverStatusCount(DriverStatus status, Long count) {
}
