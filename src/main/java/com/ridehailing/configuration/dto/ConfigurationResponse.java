package com.ridehailing.configuration.dto;

import com.ridehailing.configuration.entity.ValueType;

import java.time.Instant;

public record ConfigurationResponse(String key,
                                    String value,
                                    ValueType valueType,
                                    String description,
                                    boolean editable,
                                    Instant updatedAt,
                                    String updatedBy) {
}
