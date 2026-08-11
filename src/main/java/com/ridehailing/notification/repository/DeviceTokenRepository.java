package com.ridehailing.notification.repository;

import com.ridehailing.notification.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    List<DeviceToken> findByUserIdAndActiveTrue(Long userId);

    /** Token is unique, so re-registering a handset updates its row instead of adding another. */
    Optional<DeviceToken> findByToken(String token);
}
