package com.ridehailing.configuration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateConfigurationRequest(@NotBlank @Size(max = 500) String value) {
}
