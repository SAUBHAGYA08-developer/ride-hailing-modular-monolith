package com.ridehailing.driver.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.driver.api.DriverSummary;
import com.ridehailing.driver.dto.CreateDriverRequest;
import com.ridehailing.driver.dto.DriverResponse;
import com.ridehailing.driver.entity.Driver;
import com.ridehailing.driver.entity.DriverStatus;
import com.ridehailing.driver.repository.DriverRepository;
import com.ridehailing.security.AuthPrincipal;
import com.ridehailing.security.CurrentUser;
import com.ridehailing.user.dto.CreateUserRequest;
import com.ridehailing.user.dto.UserResponse;
import com.ridehailing.user.entity.UserRole;
import com.ridehailing.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Owns driver_schema.drivers. Availability transitions driven by rides live in DriverReservationService. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverService {

    private final DriverRepository driverRepository;
    private final UserService userService;
    private final DriverLocationService driverLocationService;
    private final AuditService auditService;

    /**
     * The login account and the driver profile are created together: a profile
     * without an account could never authenticate, so a partial result is not a
     * valid state to leave behind.
     */
    @Transactional
    public DriverResponse register(CreateDriverRequest request) {
        if (driverRepository.existsByLicenseNumber(request.licenseNumber())) {
            throw new BusinessException(ErrorCode.LICENSE_ALREADY_REGISTERED, "Licence number is already registered");
        }
        if (driverRepository.existsByPhone(request.phone())) {
            throw new BusinessException(ErrorCode.DRIVER_PROFILE_ALREADY_EXISTS, "Phone number is already registered");
        }

        UserResponse account = userService.register(new CreateUserRequest(
                request.fullName(), request.email(), request.password(), request.phone()), UserRole.DRIVER);

        if (driverRepository.existsByUserId(account.id())) {
            throw new BusinessException(ErrorCode.DRIVER_PROFILE_ALREADY_EXISTS,
                    "A driver profile already exists for this account");
        }

        Driver driver = new Driver();
        driver.setUserId(account.id());
        driver.setFullName(request.fullName().trim());
        driver.setPhone(request.phone());
        driver.setLicenseNumber(request.licenseNumber().trim().toUpperCase());
        driver.setStatus(DriverStatus.OFFLINE);

        Driver saved = driverRepository.save(driver);
        auditService.record(AuditEntities.DRIVER, saved.getId(), AuditActions.DRIVER_REGISTERED, null,
                Map.of("userId", saved.getUserId(), "licenseNumber", saved.getLicenseNumber()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public DriverResponse getById(Long driverId) {
        return toResponse(requireDriver(driverId));
    }

    @Transactional(readOnly = true)
    public DriverSummary getSummary(Long driverId) {
        Driver driver = requireDriver(driverId);
        return new DriverSummary(driver.getId(), driver.getFullName(), driver.getPhone(), driver.getRating());
    }

    @Transactional(readOnly = true)
    public Optional<Long> findDriverIdByUserId(Long userId) {
        return driverRepository.findIdByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Long requireDriverIdForUser(Long userId) {
        return findDriverIdByUserId(userId).orElseThrow(
                () -> new BusinessException(ErrorCode.DRIVER_NOT_FOUND, "No driver profile for this account"));
    }

    /** Admins pass; every other caller must be the driver themselves. */
    @Transactional(readOnly = true)
    public void requireOwnership(AuthPrincipal principal, Long driverId) {
        if (principal.isAdmin()) {
            return;
        }
        Long ownerUserId = driverRepository.findUserIdById(driverId).orElseThrow(
                () -> new BusinessException(ErrorCode.DRIVER_NOT_FOUND, "Driver " + driverId + " does not exist"));
        if (!ownerUserId.equals(principal.userId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "You are not allowed to access this driver");
        }
    }

    /**
     * Only the driver's own online/offline switch. BUSY belongs to ride
     * reservation and is never set or cleared through this path.
     */
    @Transactional
    public DriverResponse updateStatus(Long driverId, DriverStatus status) {
        Driver driver = requireDriver(driverId);
        DriverStatus current = driver.getStatus();

        if (status == DriverStatus.BUSY || current == DriverStatus.BUSY) {
            throw new BusinessException(ErrorCode.DRIVER_HAS_ACTIVE_RIDE,
                    "BUSY is managed by ride assignment and cannot be set directly");
        }
        if (current == status) {
            return toResponse(driver);
        }

        driver.setStatus(status);
        Driver saved = driverRepository.saveAndFlush(driver);

        if (status == DriverStatus.OFFLINE) {
            driverLocationService.removeLocation(driverId);
        } else {
            // Keep the recovery snapshot current while the driver is bookable,
            // so a Redis restart can repopulate the GEO set.
            driverLocationService.currentPosition(driverId).ifPresent(point ->
                    driverRepository.updateLocationSnapshot(driverId,
                            java.math.BigDecimal.valueOf(point.getY()),
                            java.math.BigDecimal.valueOf(point.getX()),
                            Instant.now(), CurrentUser.actorName()));
        }

        auditService.record(AuditEntities.DRIVER, driverId, AuditActions.DRIVER_STATUS_CHANGED,
                Map.of("status", current.name()), Map.of("status", status.name()));
        return toResponse(saved);
    }

    private Driver requireDriver(Long driverId) {
        return driverRepository.findById(driverId).orElseThrow(
                () -> new BusinessException(ErrorCode.DRIVER_NOT_FOUND, "Driver " + driverId + " does not exist"));
    }

    private DriverResponse toResponse(Driver driver) {
        return new DriverResponse(driver.getId(), driver.getUserId(), driver.getFullName(), driver.getPhone(),
                driver.getLicenseNumber(), driver.getStatus(), driver.getRating(), driver.getTotalRides(),
                driver.getCreatedAt());
    }
}
