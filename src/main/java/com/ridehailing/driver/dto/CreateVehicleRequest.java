package com.ridehailing.driver.dto;

import com.ridehailing.common.domain.CarType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateVehicleRequest(
        @NotNull CarType carType,
        @NotBlank @Size(max = 20) String registrationNumber,
        @Size(max = 50) String make,
        @Size(max = 50) String model,
        @Size(max = 30) String color) {
}
