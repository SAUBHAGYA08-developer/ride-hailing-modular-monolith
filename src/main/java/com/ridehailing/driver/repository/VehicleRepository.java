package com.ridehailing.driver.repository;

import com.ridehailing.driver.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByDriverIdAndActiveTrue(Long driverId);

    List<Vehicle> findByDriverId(Long driverId);

    boolean existsByRegistrationNumber(String registrationNumber);
}
