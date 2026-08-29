package com.ridehailing.ride.service;

import com.ridehailing.ride.entity.IdempotencyRecord;
import com.ridehailing.ride.entity.IdempotencyStatus;
import com.ridehailing.ride.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for idempotency markers, one transaction per operation.
 *
 * The split matters: a duplicate key rejection poisons the persistence context,
 * so the losing insert must be rolled back and closed before anything reads the
 * winning row. Doing both in one transaction makes Hibernate fail the next
 * auto-flush instead of surfacing the duplicate cleanly.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;

    /** Throws DataIntegrityViolationException when the key is already taken. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(Long userId, String key, String requestHash, Instant now, Instant expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setUserId(userId);
        record.setIdempotencyKey(key);
        record.setRequestHash(requestHash);
        record.setStatus(IdempotencyStatus.IN_PROGRESS);
        record.setCreatedAt(now);
        record.setExpiresAt(expiresAt);
        repository.saveAndFlush(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(Long userId, String key) {
        return repository.findByUserIdAndIdempotencyKey(userId, key);
    }

    /** Reclaims an expired marker: the key is reused for a genuinely new request. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reclaim(Long id, String requestHash, Instant now, Instant expiresAt) {
        repository.findById(id).ifPresent(record -> {
            record.setRequestHash(requestHash);
            record.setStatus(IdempotencyStatus.IN_PROGRESS);
            record.setRideId(null);
            record.setResponseBody(null);
            record.setCreatedAt(now);
            record.setExpiresAt(expiresAt);
            repository.saveAndFlush(record);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long userId, String key, Long rideId, String responseBody) {
        repository.findByUserIdAndIdempotencyKey(userId, key).ifPresent(record -> {
            record.setStatus(IdempotencyStatus.COMPLETED);
            record.setRideId(rideId);
            record.setResponseBody(responseBody);
            repository.save(record);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(Long userId, String key) {
        repository.deleteByUserIdAndIdempotencyKey(userId, key);
    }
}
