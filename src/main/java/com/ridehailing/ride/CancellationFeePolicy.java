package com.ridehailing.ride;

import com.ridehailing.common.util.Money;
import com.ridehailing.ride.entity.CancelledBy;
import com.ridehailing.ride.entity.RideStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * The single place that decides whether a cancellation costs the rider anything.
 *
 * It is a pure function of the cancellation facts and the configured policy, so
 * the rule can be read and tested without a database, a clock or a Spring
 * context - the same reason {@link com.ridehailing.common.domain.CarTypePolicy}
 * is a plain class rather than a service.
 *
 * Three deliberate decisions, none of them stated by the requirements:
 *
 * 1. Only the rider is ever charged. A driver or admin cancellation is free,
 *    because a rider must not pay for someone else changing their mind.
 * 2. Nothing is charged unless a driver was actually assigned. Before that
 *    nobody has been dispatched, so there is no cost to recover.
 * 3. A grace window measured from the assignment makes an immediate change of
 *    mind free; the fee only bites once a driver has been held for a while.
 */
public final class CancellationFeePolicy {

    private CancellationFeePolicy() {
    }

    /**
     * @param statusBeforeCancel the ride's status at the moment cancel was called
     * @param assignedAt         when the driver was claimed, null if never assigned
     * @param configuredFee      ride.cancellation.fee.amount
     * @param graceSeconds       ride.cancellation.fee.grace.seconds
     * @return the fee to charge, never null and never negative
     */
    public static BigDecimal feeFor(CancelledBy cancelledBy,
                                    RideStatus statusBeforeCancel,
                                    Instant assignedAt,
                                    Instant cancelledAt,
                                    BigDecimal configuredFee,
                                    long graceSeconds) {

        if (cancelledBy != CancelledBy.USER) {
            return Money.zero();
        }
        if (statusBeforeCancel != RideStatus.DRIVER_ASSIGNED || assignedAt == null || cancelledAt == null) {
            return Money.zero();
        }
        if (configuredFee == null || configuredFee.signum() <= 0) {
            return Money.zero();
        }
        // A negative duration can only come from clock skew; treating it as
        // inside the grace window fails in the rider's favour.
        long heldSeconds = Duration.between(assignedAt, cancelledAt).getSeconds();
        if (heldSeconds < graceSeconds) {
            return Money.zero();
        }
        return Money.round(configuredFee);
    }
}
