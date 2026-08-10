package com.ridehailing.ride.dto;

import jakarta.validation.constraints.Size;

public record CancelRideRequest(@Size(max = 255) String reason) {
}
