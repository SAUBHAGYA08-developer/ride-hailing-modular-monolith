package com.ridehailing.security.dto;

import java.util.Set;

public record LoginResponse(String accessToken,
                            String tokenType,
                            long expiresInSeconds,
                            Long userId,
                            String email,
                            String role,
                            Set<String> permissions) {
}
