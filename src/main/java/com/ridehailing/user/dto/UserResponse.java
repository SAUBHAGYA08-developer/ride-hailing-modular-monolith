package com.ridehailing.user.dto;

import com.ridehailing.user.entity.UserRole;
import com.ridehailing.user.entity.UserStatus;

import java.time.Instant;

public record UserResponse(Long id,
                           String fullName,
                           String email,
                           String phone,
                           UserRole role,
                           UserStatus status,
                           Instant createdAt) {
}
