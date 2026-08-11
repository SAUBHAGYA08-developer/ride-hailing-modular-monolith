package com.ridehailing.driver.repository;

import com.ridehailing.driver.api.DriverCarType;
import com.ridehailing.driver.api.VehicleSummary;
import com.ridehailing.driver.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByDriverIdAndActiveTrue(Long driverId);

    List<Vehicle> findByDriverId(Long driverId);

    boolean existsByRegistrationNumber(String registrationNumber);

    /** Rider-facing projection: the columns needed to spot the car, nothing else. */
    @Query("""
            select new com.ridehailing.driver.api.VehicleSummary(
                v.id, v.carType, v.registrationNumber, v.make, v.model, v.color)
            from Vehicle v where v.id = :vehicleId
            """)
    Optional<VehicleSummary> findSummaryById(@Param("vehicleId") Long vehicleId);

    /**
     * Active car types for a batch of drivers, one query for a whole page rather
     * than one per row. Only the two columns the caller needs are selected: the
     * fleet view never renders a registration number.
     *
     * Callers must skip this for an empty collection rather than send an empty
     * IN list.
     */
    @Query("""
            select new com.ridehailing.driver.api.DriverCarType(v.driverId, v.carType)
            from Vehicle v
            where v.driverId in :driverIds
              and v.active = true
            """)
    List<DriverCarType> findActiveCarTypes(@Param("driverIds") Collection<Long> driverIds);
}
