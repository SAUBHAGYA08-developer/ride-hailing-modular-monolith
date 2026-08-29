package com.ridehailing.driver.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Coordinate ranges are checked in the service rather than with
 * \@DecimalMin/\@DecimalMax so that every producer of a position - the HTTP
 * ping and the warm-up alike - passes through the same gate.
 */
public record UpdateLocationRequest(@NotNull BigDecimal latitude, @NotNull BigDecimal longitude) {
}
