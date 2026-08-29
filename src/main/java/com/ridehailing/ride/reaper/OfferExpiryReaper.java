package com.ridehailing.ride.reaper;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.ride.entity.RideOffer;
import com.ridehailing.ride.repository.RideOfferRepository;
import com.ridehailing.ride.service.RideOfferService;
import com.ridehailing.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Resolves one offer whose deadline has passed and hands the ride to the next
 * candidate. Separate from {@link OfferExpiryReaperJob} so each offer gets its
 * own transaction, exactly like StaleRideReaper/StaleRideReaperJob: one
 * poisoned row must not roll back a whole sweep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferExpiryReaper {

    private final RideOfferRepository rideOfferRepository;
    private final RideOfferService rideOfferService;
    private final AuditService auditService;

    @Transactional
    public OfferExpiryOutcome reap(Long offerId, Instant now) {
        // The claim, shaped like RideOfferRepository#claimExpired: a status and
        // deadline predicate and no read-then-write, so two overlapping sweeps
        // cannot both advance the same ride.
        if (rideOfferRepository.claimExpired(offerId, now, now, CurrentUser.actorName()) != 1) {
            return OfferExpiryOutcome.SKIPPED;
        }

        // Re-read because the claim cleared the persistence context, as StaleRideReaper does for the same reason.
        RideOffer offer = rideOfferRepository.findById(offerId).orElse(null);
        if (offer == null) {
            log.error("Offer {} disappeared between the expiry claim and the audit record", offerId);
            return OfferExpiryOutcome.SKIPPED;
        }

        auditService.record(AuditEntities.RIDE, offer.getRideId(), AuditActions.RIDE_OFFER_EXPIRED, null,
                Map.of("offerId", offerId, "driverId", offer.getDriverId()));
        rideOfferService.dispatchNext(offer.getRideId());
        return OfferExpiryOutcome.EXPIRED;
    }
}
