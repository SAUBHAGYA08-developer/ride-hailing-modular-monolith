package com.ridehailing.coupon.repository;

import com.ridehailing.coupon.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Guarded atomic consume. The limit is re-checked inside the UPDATE
     * predicate, so two concurrent redemptions of the last remaining use cannot
     * both succeed: the row write is serialised and the loser matches zero
     * rows. A read-modify-write on used_count would let the coupon go over its
     * limit. The status predicate makes a deactivation mid-flight win as well.
     *
     * version is bumped by hand because a native UPDATE bypasses the increment
     * Hibernate applies for the mapped @Version.
     *
     * @return 1 when a use was consumed, 0 when the coupon is exhausted or no
     * longer ACTIVE.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE coupon_schema.coupons SET used_count = used_count + 1, version = version + 1, "
            + "updated_at = CURRENT_TIMESTAMP(6), updated_by = :actor "
            + "WHERE id = :couponId AND status = 'ACTIVE' "
            + "AND (usage_limit IS NULL OR used_count < usage_limit)", nativeQuery = true)
    int consumeOne(@Param("couponId") Long couponId, @Param("actor") String actor);

    /**
     * Mirror of consumeOne, used when a ride that carried a coupon is
     * cancelled. Deliberately does not check status: a use taken while the
     * coupon was ACTIVE must be returned even if it was deactivated since.
     * used_count > 0 keeps the ck_coupons_used constraint satisfied.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE coupon_schema.coupons SET used_count = used_count - 1, version = version + 1, "
            + "updated_at = CURRENT_TIMESTAMP(6), updated_by = :actor "
            + "WHERE id = :couponId AND used_count > 0", nativeQuery = true)
    int releaseOne(@Param("couponId") Long couponId, @Param("actor") String actor);
}
