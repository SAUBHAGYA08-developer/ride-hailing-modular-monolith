package com.ridehailing.driver.dto;

import com.ridehailing.driver.entity.DriverStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateDriverStatusRequest(@NotNull DriverStatus status) {
}
