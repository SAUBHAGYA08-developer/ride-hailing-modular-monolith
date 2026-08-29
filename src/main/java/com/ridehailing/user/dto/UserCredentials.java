package com.ridehailing.user.dto;

import com.ridehailing.user.entity.UserRole;
import com.ridehailing.user.entity.UserStatus;

/**
 * Everything authentication needs, and nothing else. Keeps the User entity
 * inside the user module instead of leaking it into the security layer.
 */
public record UserCredentials(Long userId,
                              String email,
                              String passwordHash,
                              UserRole role,
                              UserStatus status) {
}
