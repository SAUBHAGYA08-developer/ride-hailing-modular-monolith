package com.ridehailing.coupon.repository;

import com.ridehailing.coupon.entity.CouponRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * The per-user count below is an application level check and therefore
 * advisory. The real guarantee that a ride can never consume two coupons is
 * the uk_coupon_redemption_ride unique key on ride_id: a duplicate insert from
 * a retried or concurrent ride creation fails at the database rather than
 * silently discounting the same ride twice.
 */
public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

    Optional<CouponRedemption> findByRideId(Long rideId);

    long countByCouponIdAndUserId(Long couponId, Long userId);

    void deleteByRideId(Long rideId);
}
