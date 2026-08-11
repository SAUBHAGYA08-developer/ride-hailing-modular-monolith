package com.ridehailing.driver.api;

import com.ridehailing.common.domain.CarType;

/** What a rider needs to identify the car at the kerb; no audit columns, no ownership detail. */
public record VehicleSummary(Long vehicleId, CarType carType, String registrationNumber,
                             String make, String model, String color) {
}
