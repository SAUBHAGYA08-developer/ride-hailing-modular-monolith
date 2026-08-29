package com.ridehailing.ride.repository;

import com.ridehailing.ride.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    void deleteByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
