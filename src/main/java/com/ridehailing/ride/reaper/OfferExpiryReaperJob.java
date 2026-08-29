package com.ridehailing.ride.reaper;

import com.ridehailing.configuration.ConfigKeys;
import com.ridehailing.configuration.service.ConfigurationService;
import com.ridehailing.ride.repository.RideOfferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Finds offers a driver never answered and hands each one to
 * {@link OfferExpiryReaper}.
 *
 * Without this, a driver who never opens the app - or whose app is killed -
 * would leave a ride stuck REQUESTED forever with no PENDING offer ever
 * advancing it. Mirrors StaleRideReaperJob: fixedDelay so a slow sweep can
 * never overlap itself, and the sweep itself opens no transaction of its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfferExpiryReaperJob {

    private static final int DEFAULT_BATCH_SIZE = 50;

    private final RideOfferRepository rideOfferRepository;
    private final ConfigurationService configurationService;
    private final OfferExpiryReaper offerExpiryReaper;

    @Scheduled(fixedDelayString = "${app.ride.offer-reaper.interval-ms:5000}",
            initialDelayString = "${app.ride.offer-reaper.initial-delay-ms:5000}")
    public void sweep() {
        try {
            runSweep();
        } catch (RuntimeException ex) {
            log.error("Offer expiry sweep failed and advanced nothing; the next run will see the same offers", ex);
        }
    }

    private void runSweep() {
        if (!configurationService.getBoolean(ConfigKeys.RIDE_OFFER_REAPER_ENABLED, true)) {
            log.debug("Offer expiry reaper is switched off by {}", ConfigKeys.RIDE_OFFER_REAPER_ENABLED);
            return;
        }
        int batchSize = configurationService.getInt(ConfigKeys.RIDE_OFFER_REAPER_BATCH_SIZE, DEFAULT_BATCH_SIZE);
        if (batchSize <= 0) {
            log.warn("Refusing to sweep offers with a batch size of {}", batchSize);
            return;
        }

        Instant now = Instant.now();
        List<Long> candidates = rideOfferRepository.findExpiredCandidateIds(now, batchSize);
        if (candidates.isEmpty()) {
            return;
        }

        int expired = 0;
        int skipped = 0;
        for (Long offerId : candidates) {
            switch (offerExpiryReaper.reap(offerId, now)) {
                case EXPIRED -> expired++;
                case SKIPPED -> skipped++;
            }
        }

        log.info("Offer expiry sweep: {} candidates, {} expired and advanced, {} left alone",
                candidates.size(), expired, skipped);
        if (candidates.size() == batchSize) {
            log.warn("Offer expiry sweep filled its batch of {}; more may be waiting for the next run", batchSize);
        }
    }
}
