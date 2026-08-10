package com.ridehailing.driver.service;

import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.common.geo.GeoUtils;
import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.driver.api.NearbyDriver;
import com.ridehailing.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The only component in the application permitted to touch the Redis GEO set.
 *
 * Positions live exclusively in Redis. A GPS ping is a per-driver, multi-times
 * -a-minute event, so it must never reach MySQL: the drivers row is also the
 * reservation row, and writing it on every ping would create a write hot spot
 * and churn the version that booking compares against.
 *
 * Redis has no per-member TTL, so a GEO entry cannot expire on its own. Each
 * position is therefore paired with a short lived freshness key, and any hit
 * whose freshness key has expired is treated as stale and evicted on read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverLocationService {

    private static final int DEFAULT_LOCATION_TTL_SECONDS = 60;

    private final StringRedisTemplate redis;
    private final ConfigurationService configurationService;

    public void updateLocation(Long driverId, BigDecimal latitude, BigDecimal longitude) {
        requireValidCoordinates(latitude, longitude);

        // Redis orders a Point as (x = longitude, y = latitude), the reverse of
        // the order coordinates are quoted in everywhere else.
        redis.opsForGeo().add(RedisKeys.DRIVER_GEO_SET,
                new Point(longitude.doubleValue(), latitude.doubleValue()), member(driverId));
        redis.opsForValue().set(RedisKeys.driverLocationFreshness(driverId),
                String.valueOf(Instant.now().toEpochMilli()),
                Duration.ofSeconds(locationTtlSeconds()));
    }

    /** Proximity candidates, nearest first. Availability is not decided here. */
    public List<NearbyDriver> findNearby(BigDecimal latitude, BigDecimal longitude, double radiusKm, int limit) {
        requireValidCoordinates(latitude, longitude);
        if (radiusKm <= 0 || limit <= 0) {
            return List.of();
        }

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                RedisKeys.DRIVER_GEO_SET,
                GeoReference.fromCoordinate(longitude.doubleValue(), latitude.doubleValue()),
                new Distance(radiusKm, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(limit));
        if (results == null) {
            return List.of();
        }

        List<NearbyDriver> nearby = new ArrayList<>(results.getContent().size());
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
            String member = result.getContent().getName();
            Long driverId = parseMember(member);
            if (driverId == null) {
                evict(member);
                continue;
            }
            if (!Boolean.TRUE.equals(redis.hasKey(RedisKeys.driverLocationFreshness(driverId)))) {
                // The driver stopped reporting, so the position is no longer
                // trustworthy: drop it rather than dispatch against it.
                evict(member);
                continue;
            }
            nearby.add(new NearbyDriver(driverId, result.getDistance().getValue()));
        }
        return nearby;
    }

    public void removeLocation(Long driverId) {
        evict(member(driverId));
        redis.delete(RedisKeys.driverLocationFreshness(driverId));
    }

    /**
     * The live position, used only to take a recovery snapshot when a driver
     * goes offline. Everything else reads positions through {@link #findNearby}.
     */
    public Optional<Point> currentPosition(Long driverId) {
        List<Point> positions = redis.opsForGeo().position(RedisKeys.DRIVER_GEO_SET, member(driverId));
        if (positions == null || positions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(positions.get(0));
    }

    private void requireValidCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if (!GeoUtils.isValidLatitude(latitude) || !GeoUtils.isValidLongitude(longitude)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Latitude must be within [-90, 90] and longitude within [-180, 180]");
        }
    }

    private void evict(String member) {
        redis.opsForZSet().remove(RedisKeys.DRIVER_GEO_SET, member);
    }

    private int locationTtlSeconds() {
        return configurationService.getInt(ConfigKeys.DRIVER_LOCATION_TTL_SECONDS, DEFAULT_LOCATION_TTL_SECONDS);
    }

    private String member(Long driverId) {
        return String.valueOf(driverId);
    }

    private Long parseMember(String member) {
        try {
            return Long.valueOf(member);
        } catch (NumberFormatException ex) {
            log.warn("Discarding unrecognised member '{}' from {}", member, RedisKeys.DRIVER_GEO_SET);
            return null;
        }
    }
}
