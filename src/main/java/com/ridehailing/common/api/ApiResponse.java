package com.ridehailing.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ridehailing.common.web.RequestContext;

import java.time.Instant;

/** Uniform envelope for every response the API produces. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ApiError error, String requestId, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, RequestContext.requestId(), Instant.now());
    }

    public static <T> ApiResponse<T> failure(ApiError error) {
        return new ApiResponse<>(false, null, error, RequestContext.requestId(), Instant.now());
    }
}
