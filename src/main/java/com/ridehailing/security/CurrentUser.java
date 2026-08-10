package com.ridehailing.security;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Access to the authenticated principal. */
public final class CurrentUser {

    public static final String SYSTEM_ACTOR = "SYSTEM";

    private CurrentUser() {
    }

    public static Optional<AuthPrincipal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof AuthPrincipal principal ? Optional.of(principal) : Optional.empty();
    }

    public static AuthPrincipal require() {
        return current().orElseThrow(
                () -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication is required"));
    }

    /** Value written into created_by / updated_by / audit trail. */
    public static String actorName() {
        return current().map(AuthPrincipal::email).orElse(SYSTEM_ACTOR);
    }
}
