package com.ridehailing.ride.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.domain.CarType;
import com.ridehailing.common.domain.CarTypePolicy;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.common.geo.GeoUtils;
import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.driver.api.AvailableDriver;
import com.ridehailing.driver.api.NearbyDriver;
import com.ridehailing.driver.service.DriverLocationService;
import com.ridehailing.driver.service.DriverReservationService;
import com.ridehailing.pricing.api.FareQuote;
import com.ridehailing.pricing.service.PricingService;
import com.ridehailing.redis.DistributedLockService;
import com.ridehailing.redis.RedisKeys;
import com.ridehailing.ride.dto.CreateRideRequest;
import com.ridehailing.ride.dto.RideMapper;
import com.ridehailing.ride.dto.RideResponse;
import com.ridehailing.ride.entity.Ride;
import com.ridehailing.ride.matching.DriverCandidate;
import com.ridehailing.ride.matching.DriverMatchingStrategyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates booking. Deliberately NOT transactional: the Redis search and
 * the pricing quote must not sit inside the reservation transaction, which is
 * kept to the shortest possible span in RideCreationService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RideBookingService {

    private final DriverLocationService driverLocationService;
    private final DriverReservationService driverReservationService;
    private final DriverMatchingStrategyResolver matchingStrategyResolver;
    private final PricingService pricingService;
    private final RideCreationService rideCreationService;
    private final IdempotencyService idempotencyService;
    private final ConfigurationService configurationService;
    private final DistributedLockService distributedLockService;
    private final AuditService auditService;
    private final RideMapper rideMapper;

    @Value("${app.booking.max-candidates:20}")
    private int maxCandidates;

    public RideResponse book(Long userId, CreateRideRequest request, String idempotencyKey) {
        boolean idempotent = StringUtils.hasText(idempotencyKey);
        String requestHash = idempotent ? idempotencyService.hash(request) : null;

        if (idempotent) {
            RideResponse replay = idempotencyService.begin(userId, idempotencyKey, requestHash).orElse(null);
            if (replay != null) {
                return replay;
            }
        }

        // Coordination only. Correctness comes from the conditional UPDATE in
        // MySQL, never from this lock.
        String lockKey = RedisKeys.bookingLock(userId);
        Duration lockTtl = Duration.ofSeconds(configurationService.getInt(ConfigKeys.BOOKING_LOCK_TTL_SECONDS, 10));
        Optional<String> lockToken = distributedLockService.acquire(lockKey, lockTtl);
        if (lockToken.isEmpty()) {
            if (idempotent) {
                idempotencyService.abort(userId, idempotencyKey);
            }
            throw new BusinessException(ErrorCode.REQUEST_ALREADY_IN_PROGRESS,
                    "Another booking for this rider is still being processed");
        }

        try {
            RideResponse response = doBook(userId, request);
            if (idempotent) {
                idempotencyService.complete(userId, idempotencyKey, response.id(), response);
            }
            return response;
        } catch (RuntimeException ex) {
            if (idempotent) {
                idempotencyService.abort(userId, idempotencyKey);
            }
            throw ex;
        } finally {
            distributedLockService.release(lockKey, lockToken.get());
        }
    }

    private RideResponse doBook(Long userId, CreateRideRequest request) {
        BigDecimal distanceKm = validateTrip(request);

        double radiusKm = configurationService
                .getDecimal(ConfigKeys.RIDE_SEARCH_RADIUS_KM, BigDecimal.valueOf(5)).doubleValue();
        List<CarType> acceptable = CarTypePolicy.acceptableFor(request.carType());

        List<NearbyDriver> nearby = driverLocationService.findNearby(
                request.pickupLatitude(), request.pickupLongitude(), radiusKm, maxCandidates);
        if (nearby.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_DRIVER_IN_RADIUS,
                    "No available driver found within " + radiusKm + " km");
        }

        Map<Long, Double> distanceByDriver = new java.util.LinkedHashMap<>();
        nearby.forEach(hit -> distanceByDriver.putIfAbsent(hit.driverId(), hit.distanceKm()));

        // Redis only proposed candidates; MySQL decides who is actually bookable.
        List<AvailableDriver> available =
                driverReservationService.findAvailableCandidates(distanceByDriver.keySet(), acceptable);
        if (available.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_DRIVER_IN_RADIUS,
                    "No available driver found within " + radiusKm + " km");
        }

        List<DriverCandidate> candidates = new ArrayList<>();
        for (AvailableDriver driver : available) {
            Double distance = distanceByDriver.get(driver.driverId());
            if (distance != null) {
                candidates.add(new DriverCandidate(driver.driverId(), driver.vehicleId(), driver.carType(),
                        driver.rating(), distance, driver.version()));
            }
        }

        List<DriverCandidate> ranked = rankWithCarTypePreference(candidates, acceptable);

        // Priced before the transaction opens: it reads several tables and must
        // not lengthen the driver reservation.
        FareQuote quote = pricingService.quote(distanceKm, request.carType(), request.couponCode(), userId);

        Ride ride = rideCreationService.reserveAndCreate(userId, request, distanceKm, quote, ranked);

        auditService.record(AuditEntities.RIDE, ride.getId(), AuditActions.RIDE_CREATED, null,
                Map.of("status", ride.getStatus().name(), "driverId", ride.getDriverId(),
                        "requestedCarType", ride.getRequestedCarType().name(),
                        "assignedCarType", ride.getAssignedCarType().name(),
                        "totalFare", ride.getTotalFare()));
        auditService.record(AuditEntities.DRIVER, ride.getDriverId(), AuditActions.DRIVER_STATUS_CHANGED,
                Map.of("status", "AVAILABLE"), Map.of("status", "BUSY", "rideId", ride.getId()));

        return rideMapper.toResponse(ride);
    }

    /**
     * The requested car type always outranks an upgrade, whatever the strategy
     * says: a hatchback rider gets a sedan only when no hatchback is bookable.
     */
    private List<DriverCandidate> rankWithCarTypePreference(List<DriverCandidate> candidates,
                                                            List<CarType> preferenceOrder) {
        List<DriverCandidate> ranked = matchingStrategyResolver.resolve().rank(candidates);
        List<DriverCandidate> sorted = new ArrayList<>(ranked);
        sorted.sort(Comparator.comparingInt(candidate -> {
            int index = preferenceOrder.indexOf(candidate.carType());
            return index < 0 ? Integer.MAX_VALUE : index;
        }));
        return sorted;
    }

    private BigDecimal validateTrip(CreateRideRequest request) {
        if (!GeoUtils.isValidLatitude(request.pickupLatitude()) || !GeoUtils.isValidLongitude(request.pickupLongitude())
                || !GeoUtils.isValidLatitude(request.dropLatitude())
                || !GeoUtils.isValidLongitude(request.dropLongitude())) {
            throw new BusinessException(ErrorCode.INVALID_TRIP, "Pickup or drop coordinates are out of range");
        }
        BigDecimal distanceKm = GeoUtils.distanceKm(request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());
        if (distanceKm.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_TRIP, "Pickup and drop locations must be different");
        }
        return distanceKm;
    }
}
