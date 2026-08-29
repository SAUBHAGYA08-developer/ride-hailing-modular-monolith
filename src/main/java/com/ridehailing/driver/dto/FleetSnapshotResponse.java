package com.ridehailing.driver.dto;

import com.ridehailing.common.api.PageResponse;

/**
 * The fleet endpoint's payload: the totals first, the rows second.
 *
 * Both halves are derived from a single read of the Redis live set, so a driver
 * counted in the summary can never be flagged differently in the listing.
 */
public record FleetSnapshotResponse(FleetSummaryResponse summary, PageResponse<FleetDriverResponse> drivers) {
}
