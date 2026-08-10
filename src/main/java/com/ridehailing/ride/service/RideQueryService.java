package com.ridehailing.ride.service;

import com.ridehailing.common.api.PageResponse;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.driver.service.DriverService;
import com.ridehailing.ride.dto.RideMapper;
import com.ridehailing.ride.dto.RideResponse;
import com.ridehailing.ride.entity.Ride;
import com.ridehailing.ride.repository.RideRepository;
import com.ridehailing.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side with ownership enforcement: RIDE_READ says a caller may read rides,
 * this decides which rides are theirs.
 */
@Service
@RequiredArgsConstructor
public class RideQueryService {

    private final RideRepository rideRepository;
    private final DriverService driverService;
    private final RideMapper rideMapper;

    @Transactional(readOnly = true)
    public RideResponse getById(Long rideId, AuthPrincipal principal) {
        Ride ride = rideRepository.findById(rideId).orElseThrow(
                () -> new BusinessException(ErrorCode.RIDE_NOT_FOUND, "Ride " + rideId + " does not exist"));

        if (!principal.isAdmin()) {
            boolean isRider = principal.userId().equals(ride.getUserId());
            boolean isAssignedDriver = principal.isDriver() && ride.getDriverId() != null
                    && driverService.findDriverIdByUserId(principal.userId())
                    .map(driverId -> driverId.equals(ride.getDriverId())).orElse(false);
            if (!isRider && !isAssignedDriver) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED, "You are not allowed to read this ride");
            }
        }
        return rideMapper.toResponse(ride);
    }

    @Transactional(readOnly = true)
    public PageResponse<RideResponse> findByUser(Long userId, AuthPrincipal principal, Pageable pageable) {
        if (!principal.isAdmin() && !principal.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "You are not allowed to read these rides");
        }
        return PageResponse.from(rideRepository.findByUserIdOrderByRequestedAtDesc(userId, pageable),
                rideMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PageResponse<RideResponse> findByDriver(Long driverId, AuthPrincipal principal, Pageable pageable) {
        driverService.requireOwnership(principal, driverId);
        return PageResponse.from(rideRepository.findByDriverIdOrderByRequestedAtDesc(driverId, pageable),
                rideMapper::toResponse);
    }
}
