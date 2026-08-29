package com.ridehailing.driver.api;

import java.math.BigDecimal;

/** The slice of a driver profile other modules are allowed to show a rider. */
public record DriverSummary(Long id, String fullName, String phone, BigDecimal rating) {
}
