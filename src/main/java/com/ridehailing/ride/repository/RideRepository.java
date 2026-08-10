package com.ridehailing.ride.repository;

import com.ridehailing.ride.entity.Ride;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRepository extends JpaRepository<Ride, Long> {

    /** Ordering mirrors idx_rides_user so the page is served straight from the index. */
    Page<Ride> findByUserIdOrderByRequestedAtDesc(Long userId, Pageable pageable);

    /** Ordering mirrors idx_rides_driver. */
    Page<Ride> findByDriverIdOrderByRequestedAtDesc(Long driverId, Pageable pageable);
}
