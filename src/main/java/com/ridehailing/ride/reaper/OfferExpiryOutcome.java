package com.ridehailing.ride.reaper;

/** What one candidate offer-expiry claim resolved to. */
public enum OfferExpiryOutcome {
    EXPIRED,
    /** Already resolved (accepted/declined) or claimed by another sweep before this one got to it. */
    SKIPPED
}
