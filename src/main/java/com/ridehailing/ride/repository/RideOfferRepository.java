package com.ridehailing.ride.repository;

import com.ridehailing.ride.entity.RideOffer;
import com.ridehailing.ride.entity.RideOfferStatus;
import com.ridehailing.ride.matching.DriverAcceptanceStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RideOfferRepository extends JpaRepository<RideOffer, Long> {

    /** A ride has at most one PENDING offer at a time - this is the accept/decline and reaper lookup. */
    Optional<RideOffer> findByRideIdAndStatus(Long rideId, RideOfferStatus status);

    /** What a driver's app polls to discover an incoming offer. */
    Optional<RideOffer> findByDriverIdAndStatus(Long driverId, RideOfferStatus status);

    /** How many offers a ride has already had, so the next one's sequence number is a plain count + 1. */
    long countByRideId(Long rideId);

    /**
     * Accepted-vs-total counts per driver over every offer that has actually
     * been resolved, for the SCORE matching strategy's acceptance-rate signal.
     * Mirrors DriverRepository#countByStatus's projection style.
     */
    @Query("""
            select new com.ridehailing.ride.matching.DriverAcceptanceStats(o.driverId,
                   sum(case when o.status = com.ridehailing.ride.entity.RideOfferStatus.ACCEPTED then 1L else 0L end),
                   count(o.id))
            from RideOffer o
            where o.driverId in :driverIds
              and o.status <> com.ridehailing.ride.entity.RideOfferStatus.PENDING
            group by o.driverId
            """)
    List<DriverAcceptanceStats> acceptanceStats(@Param("driverIds") Collection<Long> driverIds);

    /** PENDING offers past their deadline, oldest first, for the expiry reaper. */
    @Query(value = "SELECT id FROM ride_schema.ride_offers "
            + "WHERE status = 'PENDING' AND expires_at < :cutoff "
            + "ORDER BY expires_at LIMIT :batchSize",
            nativeQuery = true)
    List<Long> findExpiredCandidateIds(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    /**
     * The reaper's claim, shaped like RideRepository#claimStale: a status and
     * deadline predicate and no read-then-write, so two overlapping sweeps
     * cannot both advance the same ride.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE ride_schema.ride_offers SET status = 'EXPIRED', responded_at = :at, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP(6), updated_by = :actor "
            + "WHERE id = :offerId AND status = 'PENDING' AND expires_at < :cutoff",
            nativeQuery = true)
    int claimExpired(@Param("offerId") Long offerId, @Param("cutoff") Instant cutoff,
                     @Param("at") Instant at, @Param("actor") String actor);
}
