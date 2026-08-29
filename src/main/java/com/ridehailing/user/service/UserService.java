package com.ridehailing.user.service;

import com.ridehailing.common.api.PageResponse;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.user.dto.CreateUserRequest;
import com.ridehailing.user.dto.UserCredentials;
import com.ridehailing.user.dto.UserResponse;
import com.ridehailing.user.entity.User;
import com.ridehailing.user.entity.UserRole;
import com.ridehailing.user.entity.UserStatus;
import com.ridehailing.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Owns user_schema. The only module allowed to read or write users. */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates an account with the given role. Called by public signup for
     * riders and by driver onboarding for drivers.
     */
    @Transactional
    public UserResponse register(CreateUserRequest request, UserRole role) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED, "Email is already registered");
        }
        if (userRepository.existsByPhone(request.phone())) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED, "Phone number is already registered");
        }

        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPhone(request.phone());
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        return toResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long userId) {
        return toResponse(requireUser(userId));
    }

    @Transactional(readOnly = true)
    public Optional<UserCredentials> findCredentialsByEmail(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase())
                .map(user -> new UserCredentials(user.getId(), user.getEmail(), user.getPasswordHash(),
                        user.getRole(), user.getStatus()));
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> findAll(UserRole role, Pageable pageable) {
        return role == null
                ? PageResponse.from(userRepository.findAll(pageable), this::toResponse)
                : PageResponse.from(userRepository.findByRole(role, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public void requireExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "User " + userId + " does not exist");
        }
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User " + userId + " does not exist"));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.getStatus(), user.getCreatedAt());
    }
}
