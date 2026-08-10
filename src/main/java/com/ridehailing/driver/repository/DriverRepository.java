package com.ridehailing.driver.repository;

import com.ridehailing.common.domain.CarType;
import com.ridehailing.driver.api.AvailableDriver;
import com.ridehailing.driver.entity.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    boolean existsByLicenseNumber(String licenseNumber);

    boolean existsByPhone(String phone);

    @Query("select d.id from Driver d where d.userId = :userId")
    Optional<Long> findIdByUserId(@Param("userId") Long userId);

    /** Ownership checks only need the owner, never the whole profile. */
    @Query("select d.userId from Driver d where d.id = :driverId")
    Optional<Long> findUserIdById(@Param("driverId") Long driverId);

    /** Source rows for the Redis GEO warm-up after a cache restart. */
    List<Driver> findByLastKnownLatitudeIsNotNullAndLastKnownLongitudeIsNotNull();

    /**
     * Resolves Redis proximity hits against the authoritative availability
     * state, keeping the driver and the vehicle that would serve the ride in a
     * single round trip. The version travels with the candidate so the caller
     * can reserve exactly the row it evaluated.
     */
    @Query("""
            select new com.ridehailing.driver.api.AvailableDriver(d.id, v.id, v.carType, d.rating, d.version)
            from Driver d
            join Vehicle v on v.driverId = d.id
            where d.id in :driverIds
              and d.status = com.ridehailing.driver.entity.DriverStatus.AVAILABLE
              and v.active = true
              and v.carType in :carTypes
            """)
    List<AvailableDriver> findAvailableCandidates(@Param("driverIds") Collection<Long> driverIds,
                                                  @Param("carTypes") Collection<CarType> carTypes);

    /**
     * Claims a driver. The status and version predicates make this a single
     * atomic compare-and-set in InnoDB: exactly one of N concurrent bookings
     * gets 1 back, everyone else gets 0 and must fall through to the next
     * candidate. Assignment must never be a read-then-write instead.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE driver_schema.drivers SET status='BUSY', version = version + 1, "
            + "updated_at = CURRENT_TIMESTAMP(6), updated_by = :actor "
            + "WHERE id = :driverId AND status = 'AVAILABLE' AND version = :expectedVersion",
            nativeQuery = true)
    int reserve(@Param("driverId") Long driverId,
                @Param("expectedVersion") long expectedVersion,
                @Param("actor") String actor);

    /**
     * Hands a driver back after a cancelled or failed assignment. No version
     * predicate: a release is only ever driven by the ride that already holds
     * the driver, and the BUSY predicate alone keeps it idempotent.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE driver_schema.drivers SET status='AVAILABLE', version = version + 1, "
            + "updated_at = CURRENT_TIMESTAMP(6), updated_by = :actor "
            + "WHERE id = :driverId AND status = 'BUSY'",
            nativeQuery = true)
    int release(@Param("driverId") Long driverId, @Param("actor") String actor);

    /** Release plus the ride counter, so a replayed completion cannot double count. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE driver_schema.drivers SET status='AVAILABLE', total_rides = total_rides + 1, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP(6), updated_by = :actor "
            + "WHERE id = :driverId AND status = 'BUSY'",
            nativeQuery = true)
    int completeRide(@Param("driverId") Long driverId, @Param("actor") String actor);

    /**
     * Persists the recovery snapshot without bumping {@code version}: a
     * position write must never invalidate a candidate the booking flow is
     * about to reserve. Called on status changes only, never on a GPS ping.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE driver_schema.drivers SET last_known_latitude = :latitude, "
            + "last_known_longitude = :longitude, last_location_at = :at, "
            + "updated_at = CURRENT_TIMESTAMP(6), updated_by = :actor "
            + "WHERE id = :driverId",
            nativeQuery = true)
    int updateLocationSnapshot(@Param("driverId") Long driverId,
                               @Param("latitude") BigDecimal latitude,
                               @Param("longitude") BigDecimal longitude,
                               @Param("at") Instant at,
                               @Param("actor") String actor);
}
