package com.ridehailing.driver.service;

import com.ridehailing.common.domain.CarType;
import com.ridehailing.driver.api.AvailableDriver;
import com.ridehailing.driver.repository.DriverRepository;
import com.ridehailing.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Reservation state transitions, kept deliberately thin.
 *
 * Every method uses the default propagation so it joins the caller's booking
 * transaction: the driver claim and the ride row must commit or roll back
 * together. Nothing here writes an audit record either - the ride module owns
 * that and must only write it once its own transaction has committed,
 * otherwise a rolled back booking would leave a phantom trail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverReservationService {

    private final DriverRepository driverRepository;

    @Transactional(readOnly = true)
    public List<AvailableDriver> findAvailableCandidates(Collection<Long> driverIds, Collection<CarType> carTypes) {
        // An empty collection would render as IN (), which MySQL rejects, and
        // there is nothing to resolve in the first place.
        if (driverIds == null || driverIds.isEmpty() || carTypes == null || carTypes.isEmpty()) {
            return List.of();
        }
        return driverRepository.findAvailableCandidates(driverIds, carTypes);
    }

    /**
     * Claims the driver only if it is still AVAILABLE at exactly the version
     * the caller evaluated. False means another booking won the race, so the
     * caller must move on to its next candidate rather than retry this one.
     */
    @Transactional
    public boolean reserve(Long driverId, long expectedVersion) {
        boolean reserved = driverRepository.reserve(driverId, expectedVersion, CurrentUser.actorName()) == 1;
        if (!reserved) {
            log.debug("Driver {} was taken by a concurrent booking at version {}", driverId, expectedVersion);
        }
        return reserved;
    }

    @Transactional
    public boolean release(Long driverId) {
        return driverRepository.release(driverId, CurrentUser.actorName()) == 1;
    }

    @Transactional
    public boolean completeRide(Long driverId) {
        return driverRepository.completeRide(driverId, CurrentUser.actorName()) == 1;
    }
}
