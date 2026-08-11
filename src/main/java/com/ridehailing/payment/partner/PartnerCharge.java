package com.ridehailing.payment.partner;

/** The partner's answer, kept separate from PaymentResult, which is the platform's decision about it. */
public record PartnerCharge(boolean authorised, String reference, String declineReason) {

    public static PartnerCharge authorised(String reference) {
        return new PartnerCharge(true, reference, null);
    }

    public static PartnerCharge declined(String declineReason) {
        return new PartnerCharge(false, null, declineReason);
    }
}
