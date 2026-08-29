package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.common.api.PageResponse;
import com.ridehailing.security.AccessGuard;
import com.ridehailing.security.AuthPrincipal;
import com.ridehailing.security.CurrentUser;
import com.ridehailing.user.dto.CreateUserRequest;
import com.ridehailing.user.dto.UserResponse;
import com.ridehailing.user.entity.UserRole;
import com.ridehailing.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService userService;

    /** Public rider signup. The role is fixed by the server, never by the caller. */
    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.register(request, UserRole.USER);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('USER_READ')")
    public ApiResponse<UserResponse> getById(@PathVariable Long userId) {
        AuthPrincipal principal = CurrentUser.require();
        AccessGuard.requireOwnerOrAdmin(principal, userId, "user");
        return ApiResponse.ok(userService.getById(userId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ') and hasRole('ADMIN')")
    public ApiResponse<PageResponse<UserResponse>> list(@RequestParam(required = false) UserRole role,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(userService.findAll(role,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt"))));
    }
}
