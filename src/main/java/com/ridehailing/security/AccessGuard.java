package com.ridehailing.security;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;

/**
 * Ownership enforcement. A permission says what an actor may do; this says
 * which rows they may do it to. Both checks are always required.
 */
public final class AccessGuard {

    private AccessGuard() {
    }

    /** Admins pass; everyone else must own the resource. */
    public static void requireOwnerOrAdmin(AuthPrincipal principal, Long ownerUserId, String resource) {
        if (principal.isAdmin()) {
            return;
        }
        if (ownerUserId == null || !ownerUserId.equals(principal.userId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You are not allowed to access this " + resource);
        }
    }

    public static void require(boolean allowed, String resource) {
        if (!allowed) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You are not allowed to access this " + resource);
        }
    }
}
