package com.ridehailing.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Map<String, String> details) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }
}
