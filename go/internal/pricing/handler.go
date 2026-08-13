package pricing

import (
	"net/http"

	"github.com/shopspring/decimal"

	"ridehailing/internal/geo"
	"ridehailing/internal/httpx"
)

// The trip a CreateRideRequest describes, minus everything only a real booking needs.
type FareEstimateRequest struct {
	PickupLatitude  *decimal.Decimal `json:"pickupLatitude"`
	PickupLongitude *decimal.Decimal `json:"pickupLongitude"`
	DropLatitude    *decimal.Decimal `json:"dropLatitude"`
	DropLongitude   *decimal.Decimal `json:"dropLongitude"`
	CarType         string           `json:"carType"`
	CouponCode      string           `json:"couponCode"`
}

// The price of a trip nobody has booked; field order and names are the Java record's.
type FareEstimateResponse struct {
	DistanceKm         Amount  `json:"distanceKm"`
	PricingRuleCode    string  `json:"pricingRuleCode"`
	PricingZoneCode    *string `json:"pricingZoneCode"`
	DistanceFare       Amount  `json:"distanceFare"`
	CarTypeMultiplier  Amount  `json:"carTypeMultiplier"`
	SurgeMultiplier    Amount  `json:"surgeMultiplier"`
	MinimumFare        Amount  `json:"minimumFare"`
	MinimumFareApplied bool    `json:"minimumFareApplied"`
	FareBeforeDiscount Amount  `json:"fareBeforeDiscount"`
	CouponCode         *string `json:"couponCode"`
	CouponApplicable   bool    `json:"couponApplicable"`
	CouponReason       *string `json:"couponReason"`
	CouponMessage      *string `json:"couponMessage"`
	DiscountAmount     Amount  `json:"discountAmount"`
	TotalFare          Amount  `json:"totalFare"`
}

const couponCodeMaxLength = 40

type Handler struct {
	svc *Service
}

func NewHandler(svc *Service) *Handler { return &Handler{svc: svc} }

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/pricing/quote", httpx.RequirePermission("PRICING_READ", h.quote))
}

// Prices a trip without booking it: nothing is reserved, so a client may call it on every map drag.
func (h *Handler) quote(w http.ResponseWriter, r *http.Request) {
	var request FareEstimateRequest
	if err := httpx.Decode(r, &request); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := request.validate(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	// The rider comes from the token, never from the body.
	principal, _ := httpx.PrincipalOf(r)
	response, err := h.svc.Estimate(r.Context(), request, principal.UserID)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, response)
}

// Stands in for the bean validation annotations on the record, which run before the service does.
func (r FareEstimateRequest) validate() error {
	if !geo.IsValidLatitude(r.PickupLatitude) || !geo.IsValidLongitude(r.PickupLongitude) ||
		!geo.IsValidLatitude(r.DropLatitude) || !geo.IsValidLongitude(r.DropLongitude) {
		return httpx.ErrValidation("Request validation failed")
	}
	if r.CarType == "" {
		// An absent car type is @NotNull, so it is a validation failure.
		return httpx.ErrValidation("Request validation failed")
	}
	if !IsCarType(r.CarType) {
		// An unparseable enum is MALFORMED_REQUEST in Java, because Jackson fails before validation runs.
		return httpx.ErrMalformed()
	}
	if len(r.CouponCode) > couponCodeMaxLength {
		return httpx.ErrValidation("Request validation failed")
	}
	return nil
}
