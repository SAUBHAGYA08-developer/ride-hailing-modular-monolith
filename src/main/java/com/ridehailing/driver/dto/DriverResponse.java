package com.ridehailing.driver.dto;

import com.ridehailing.driver.entity.DriverStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record DriverResponse(
        Long id,
        Long userId,
        String fullName,
        String phone,
        String licenseNumber,
        DriverStatus status,
        BigDecimal rating,
        int totalRides,
        Instant createdAt) {
}
