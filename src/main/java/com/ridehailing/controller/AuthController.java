package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.ratelimit.RateLimitPolicy;
import com.ridehailing.ratelimit.RateLimited;
import com.ridehailing.security.AuthService;
import com.ridehailing.security.dto.LoginRequest;
import com.ridehailing.security.dto.LoginResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @RateLimited(RateLimitPolicy.LOGIN)
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }
}
