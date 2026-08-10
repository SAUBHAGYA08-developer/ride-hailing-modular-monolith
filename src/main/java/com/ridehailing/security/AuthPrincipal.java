package com.ridehailing.security;

import java.util.Set;

/** The authenticated identity. Never taken from request parameters. */
public record AuthPrincipal(Long userId, String email, String role, Set<String> permissions) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_DRIVER = "DRIVER";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    public boolean isDriver() {
        return ROLE_DRIVER.equals(role);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
