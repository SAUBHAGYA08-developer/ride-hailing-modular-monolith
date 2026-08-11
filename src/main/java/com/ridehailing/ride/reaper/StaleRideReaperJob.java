package com.ridehailing.ride.reaper;

import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.driver.repository.DriverRepository;
import com.ridehailing.driver.service.DriverLocationService;
import com.ridehailing.ride.repository.RideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Finds rides whose driver has silently disappeared and hands each one to
 * {@link StaleRideReaper}.
 *
 * A driver whose app is killed or loses the network never calls the API again,
 * so without this the ride stays DRIVER_ASSIGNED or STARTED forever and the
 * driver stays BUSY. Two independent facts must agree before anything is
 * touched: the ride has been in its current state longer than
 * ride.stale.grace.seconds (MySQL), and the driver is not reporting a fresh
 * position (Redis). Redis absence alone is never enough - a 60 second network
 * blip must not cancel a ride in progress - which is why the grace window is
 * fifteen minutes rather than the location TTL.
 *
 * The sweep opens no transaction of its own: each ride is resolved in one, so a
 * single bad row cannot roll back the batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleRideReaperJob {

    private static final int DEFAULT_GRACE_SECONDS = 900;
    private static final int DEFAULT_BATCH_SIZE = 50;

    private final RideRepository rideRepository;
    private final DriverRepository driverRepository;
    private final DriverLocationService driverLocationService;
    private final ConfigurationService configurationService;
    private final StaleRideReaper staleRideReaper;

    /**
     * fixedDelay rather than fixedRate, so a slow sweep can never overlap itself,
     * and the initial delay keeps the job clear of startup warm-up. Both are
     * properties because they are technical settings; whether the reaper runs at
     * all is a business decision and lives in a configuration row instead.
     */
    @Scheduled(fixedDelayString = "${app.ride.reaper.interval-ms:60000}",
            initialDelayString = "${app.ride.reaper.initial-delay-ms:60000}")
    public void sweep() {
        try {
            runSweep();
        } catch (RuntimeException ex) {
            // Covers a dead Redis, which throws rather than reporting an empty live set; the next run sees the same rides.
            log.error("Stale ride sweep failed and reaped nothing; the next run will see the same rides", ex);
        }
    }

    private void runSweep() {
        if (!configurationService.getBoolean(ConfigKeys.RIDE_REAPER_ENABLED, true)) {
            log.debug("Stale ride reaper is switched off by {}", ConfigKeys.RIDE_REAPER_ENABLED);
            return;
        }
        long graceSeconds = configurationService.getInt(ConfigKeys.RIDE_STALE_GRACE_SECONDS, DEFAULT_GRACE_SECONDS);
        int batchSize = configurationService.getInt(ConfigKeys.RIDE_REAPER_BATCH_SIZE, DEFAULT_BATCH_SIZE);
        if (graceSeconds <= 0 || batchSize <= 0) {
            log.warn("Refusing to sweep with a grace of {}s and a batch size of {}", graceSeconds, batchSize);
            return;
        }

        List<Long> candidates = rideRepository.findStaleCandidateIds(
                Instant.now().minusSeconds(graceSeconds), batchSize);
        if (candidates.isEmpty()) {
            return;
        }

        // Presence is only consulted once MySQL says there is something to look at, so a quiet system never touches Redis.
        Set<Long> live = driverLocationService.liveDriverIds();
        if (live.isEmpty() && !safeToReapWithNobodyLive(candidates.size())) {
            return;
        }

        int locationTtlSeconds = driverLocationService.locationTtlSeconds();
        int cancelled = 0;
        int flagged = 0;
        int skipped = 0;
        for (Long rideId : candidates) {
            switch (staleRideReaper.reap(rideId, live, graceSeconds, locationTtlSeconds)) {
                case CANCELLED -> cancelled++;
                case FLAGGED -> flagged++;
                case SKIPPED -> skipped++;
            }
        }

        log.info("Stale ride sweep: {} candidates past a {}s grace, {} cancelled, {} flagged, {} left alone",
                candidates.size(), graceSeconds, cancelled, flagged, skipped);
        if (candidates.size() == batchSize) {
            log.warn("Stale ride sweep filled its batch of {}; more may be waiting for the next run", batchSize);
        }
    }

    /**
     * The most dangerous failure mode in the whole job: an empty live set looks
     * exactly like every driver having vanished at once.
     *
     * A dead Redis throws instead of answering, so an empty set means Redis
     * replied with nothing - a flushed or failed-over instance included. MySQL is
     * the only thing that can tell that apart from a genuinely idle fleet: if
     * anyone is on duty, presence is broken rather than the fleet gone, and the
     * run is abandoned. The cost of that choice is deliberate and worth stating -
     * a system whose only on-duty drivers are the suspects themselves will never
     * self-heal and needs an operator - because the alternative is cancelling
     * every ride in flight the first time a cache is flushed.
     */
    private boolean safeToReapWithNobodyLive(int candidateCount) {
        long onDuty = driverRepository.countOnDuty();
        if (onDuty > 0) {
            log.error("Skipping the stale ride sweep: no driver is reporting a position while {} are on duty, "
                    + "which reads as a presence outage rather than {} abandoned rides", onDuty, candidateCount);
            return false;
        }
        // Nobody on duty at all, so there is no fleet left to endanger and a stuck ride is stuck for real.
        log.warn("No driver is live and none is on duty; reaping {} ride(s) held by off-duty drivers", candidateCount);
        return true;
    }
}
