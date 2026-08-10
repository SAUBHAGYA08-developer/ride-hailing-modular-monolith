package com.ridehailing.ride.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.ride.dto.RideResponse;
import com.ridehailing.ride.entity.IdempotencyRecord;
import com.ridehailing.ride.entity.IdempotencyStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Duplicate suppression for ride creation.
 *
 * The guarantee comes from the unique key (user_id, idempotency_key), not from
 * a read-then-check: concurrent retries all attempt the insert and exactly one
 * survives. This class holds no transaction of its own - every database step
 * runs in its own via IdempotencyStore, so a rejected insert is rolled back
 * before the winning row is read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyStore store;
    private final ConfigurationService configurationService;
    private final ObjectMapper objectMapper;

    /** Empty for a fresh request; present when a completed result can be replayed. */
    public Optional<RideResponse> begin(Long userId, String key, String requestHash) {
        Instant now = Instant.now();
        try {
            store.insert(userId, key, requestHash, now, now.plus(ttl()));
            return Optional.empty();
        } catch (DataIntegrityViolationException ex) {
            return Optional.ofNullable(resolveExisting(userId, key, requestHash, now));
        }
    }

    public void complete(Long userId, String key, Long rideId, RideResponse response) {
        store.complete(userId, key, rideId, writeJson(response));
    }

    /** Frees the key after a failed attempt so the client may retry. */
    public void abort(Long userId, String key) {
        try {
            store.delete(userId, key);
        } catch (RuntimeException ex) {
            log.warn("Could not release idempotency key for user {} - it expires by TTL", userId, ex);
        }
    }

    public String hash(Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not fingerprint the request");
        }
    }

    /** Null means the key was expired and has been reclaimed for this request. */
    private RideResponse resolveExisting(Long userId, String key, String requestHash, Instant now) {
        IdempotencyRecord existing = store.find(userId, key).orElseThrow(
                () -> new BusinessException(ErrorCode.REQUEST_ALREADY_IN_PROGRESS,
                        "A request with this Idempotency-Key is already being processed"));

        if (existing.getExpiresAt().isBefore(now)) {
            store.reclaim(existing.getId(), requestHash, now, now.plus(ttl()));
            return null;
        }
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request");
        }
        if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.REQUEST_ALREADY_IN_PROGRESS,
                    "A request with this Idempotency-Key is already being processed");
        }
        return readJson(existing.getResponseBody());
    }

    private Duration ttl() {
        return Duration.ofSeconds(configurationService.getInt(ConfigKeys.IDEMPOTENCY_TTL_SECONDS, 86400));
    }

    private String writeJson(RideResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not store the idempotent response");
        }
    }

    private RideResponse readJson(String body) {
        try {
            return objectMapper.readValue(body, RideResponse.class);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Could not replay the idempotent response");
        }
    }
}
