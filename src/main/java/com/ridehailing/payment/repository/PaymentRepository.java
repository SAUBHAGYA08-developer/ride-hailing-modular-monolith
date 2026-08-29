package com.ridehailing.payment.repository;

import com.ridehailing.payment.entity.Payment;
import com.ridehailing.payment.entity.PaymentPurpose;
import com.ridehailing.payment.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** The idempotency probe; at most one row can match because PaymentService never inserts a second SUCCESS. */
    Optional<Payment> findByRideIdAndPurposeAndStatus(Long rideId, PaymentPurpose purpose, PaymentStatus status);

    /** Newest first: a retry only cares about the most recent attempt. */
    List<Payment> findByRideIdAndPurposeOrderByIdDesc(Long rideId, PaymentPurpose purpose);

    List<Payment> findByRideIdOrderByIdAsc(Long rideId);
}
