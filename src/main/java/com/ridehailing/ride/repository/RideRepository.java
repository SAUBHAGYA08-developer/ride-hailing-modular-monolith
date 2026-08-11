package com.ridehailing.ride.repository;

import com.ridehailing.ride.entity.Ride;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RideRepository extends JpaRepository<Ride, Long> {

    /** Ordering mirrors idx_rides_user so the page is served straight from the index. */
    Page<Ride> findByUserIdOrderByRequestedAtDesc(Long userId, Pageable pageable);

    /** Ordering mirrors idx_rides_driver. */
    Page<Ride> findByDriverIdOrderByRequestedAtDesc(Long driverId, Pageable pageable);

    /**
     * Rides that have sat in an assigned, non-terminal state longer than the
     * grace window, oldest first, for the stale ride reaper.
     *
     * Native because stale_flagged_at is deliberately not mapped on the Ride
     * entity: it is reaper bookkeeping rather than part of the ride a rider is
     * shown, and leaving it unmapped also means an ordinary saveAndFlush of the
     * entity cannot accidentally clear the claim.
     *
     * COALESCE(started_at, assigned_at) is the moment the ride entered its
     * current state - assigned_at while DRIVER_ASSIGNED, started_at once it moved
     * on - so what is measured is the age of the state, not of the ride.
     *
     * Absence from Redis is deliberately not a predicate: presence lives in the
     * other store and only the caller holds both, exactly as
     * DriverRepository#findAvailableDriverIdsWithActiveVehicle leaves it out.
     * Ids only, and LIMIT is the batch cap, so one sweep cannot walk the table.
     */
    @Query(value = "SELECT id FROM ride_schema.rides "
            + "WHERE status IN ('DRIVER_ASSIGNED', 'STARTED') "
            + "AND driver_id IS NOT NULL "
            + "AND stale_flagged_at IS NULL "
            + "AND COALESCE(started_at, assigned_at) < :cutoff "
            + "ORDER BY COALESCE(started_at, assigned_at) LIMIT :batchSize",
            nativeQuery = true)
    List<Long> findStaleCandidateIds(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * The reaper's claim on one ride, in the shape of DriverRepository#release: a
     * status predicate and no read-then-write. Exactly one of N concurrent sweeps
     * gets 1 back and goes on to resolve the ride; the rest get 0 and skip it.
     *
     * The IS NULL predicate makes it idempotent across runs as well as across
     * instances, which is what lets a STARTED ride be flagged once and then left
     * alone instead of re-audited every minute for the rest of its life.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE ride_schema.rides SET stale_flagged_at = :at, version = version + 1, "
            + "updated_at = CURRENT_TIMESTAMP(6), updated_by = :actor "
            + "WHERE id = :rideId AND status = :status AND stale_flagged_at IS NULL",
            nativeQuery = true)
    int claimStale(@Param("rideId") Long rideId,
                   @Param("status") String status,
                   @Param("at") Instant at,
                   @Param("actor") String actor);
}
