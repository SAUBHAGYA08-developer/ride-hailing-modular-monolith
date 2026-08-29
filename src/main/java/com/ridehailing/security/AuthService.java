package com.ridehailing.security;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.rbac.service.RolePermissionService;
import com.ridehailing.security.dto.LoginRequest;
import com.ridehailing.security.dto.LoginResponse;
import com.ridehailing.user.dto.UserCredentials;
import com.ridehailing.user.entity.UserStatus;
import com.ridehailing.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserService userService;
    private final RolePermissionService rolePermissionService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginResponse login(LoginRequest request) {
        UserCredentials credentials = userService.findCredentialsByEmail(request.email())
                .orElse(null);

        // Same error and roughly the same work for unknown email and wrong
        // password, so the endpoint does not enumerate accounts.
        if (credentials == null || !passwordEncoder.matches(request.password(), credentials.passwordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        if (credentials.status() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED, "This account is suspended");
        }

        Set<String> permissions = rolePermissionService.permissionsOf(credentials.role().name());
        AuthPrincipal principal = new AuthPrincipal(credentials.userId(), credentials.email(),
                credentials.role().name(), permissions);

        return new LoginResponse(jwtService.issue(principal), "Bearer", jwtService.validity().toSeconds(),
                principal.userId(), principal.email(), principal.role(), permissions);
    }
}
