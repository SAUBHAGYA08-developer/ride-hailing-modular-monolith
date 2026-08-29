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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Every driver currently reporting a fresh position, as driver ids.
     *
     * This walks the entire GEO set, so it is an operator view and never a
     * booking path: dispatch reads positions through {@link #findNearby}, which
     * is bounded by radius and limit. Booking must not pay for the whole fleet.
     *
     * Freshness is resolved with a single multiGet instead of a hasKey per
     * member, so the whole call is two round trips whether the fleet is 10
     * drivers or 10,000. Stale members are evicted on the way past, exactly as
     * findNearby does, so an operational read also heals the set.
     *
     * If the multiGet comes back null, Redis could not answer at all. "Stale"
     * and "cannot tell" are indistinguishable in that case, so nothing is
     * evicted - deleting live positions on the strength of a failed read would
     * let a Redis hiccup unbook the whole fleet - and the caller sees an empty
     * live set, which reads as a total presence outage rather than as a set that
     * has been quietly pruned on bad information.
     */
    public Set<Long> liveDriverIds() {
        Set<String> members = redis.opsForZSet().range(RedisKeys.DRIVER_GEO_SET, 0, -1);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }

        // Two parallel lists rather than a map: the multiGet result comes back
        // positionally, so the index is what ties a reply back to its driver.
        List<Long> candidates = new ArrayList<>(members.size());
        List<String> freshnessKeys = new ArrayList<>(members.size());
        for (String member : members) {
            Long driverId = parseMember(member);
            if (driverId == null) {
                // Junk regardless of freshness, so this eviction needs no reply.
                evict(member);
                continue;
            }
            candidates.add(driverId);
            freshnessKeys.add(RedisKeys.driverLocationFreshness(driverId));
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }

        List<String> freshness = redis.opsForValue().multiGet(freshnessKeys);
        if (freshness == null || freshness.size() != candidates.size()) {
            log.warn("Freshness lookup for {} members of {} returned nothing usable; "
                            + "reporting an empty live set and evicting nothing",
                    candidates.size(), RedisKeys.DRIVER_GEO_SET);
            return Set.of();
        }

        Set<Long> live = new LinkedHashSet<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Long driverId = candidates.get(index);
            if (freshness.get(index) == null) {
                // The driver stopped reporting: what is left is a position, not
                // a location. Drop it so the next reader is not misled either.
                evict(member(driverId));
                continue;
            }
            live.add(driverId);
        }
        return live;
    }

    /** Live coordinates for the fresh members, for the operator fleet view only. */
    public Map<Long, Point> livePositions() {
        Set<Long> live = liveDriverIds();
        if (live.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(live);
        String[] members = ids.stream().map(this::member).toArray(String[]::new);
        List<Point> points = redis.opsForGeo().position(RedisKeys.DRIVER_GEO_SET, members);
        if (points == null || points.size() != ids.size()) {
            log.warn("GEOPOS for {} live members returned nothing usable", ids.size());
            return Map.of();
        }
        Map<Long, Point> positions = new LinkedHashMap<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            // A null means the member vanished between the two calls, which is not an error.
            if (points.get(index) != null) {
                positions.put(ids.get(index), points.get(index));
            }
        }
        return positions;
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

    /**
     * How long a reported position stays trustworthy. Visible outside the class
     * only so an operator view can publish it: a presence count is impossible to
     * interpret without knowing the window it was measured over.
     */
    public int locationTtlSeconds() {
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
