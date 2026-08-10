package com.ridehailing.driver.dto;

import com.ridehailing.common.domain.CarType;

public record VehicleResponse(
        Long id,
        Long driverId,
        CarType carType,
        String registrationNumber,
        String make,
        String model,
        String color,
        boolean active) {
}
