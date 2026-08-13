package payment

import (
	"context"
	"net/http"

	"ridehailing/internal/auth"
	"ridehailing/internal/httpx"
)

// Satisfied by the ride module's query service: reading the ride first is the ownership check.
type RideGuard interface {
	EnsureReadable(ctx context.Context, rideID int64, principal auth.Principal) error
}

// Addressed under the ride, the only handle a client has, with the ownership rule delegated to the ride module.
type Handler struct {
	service *Service
	rides   RideGuard
}

func NewHandler(service *Service, rides RideGuard) *Handler {
	return &Handler{service: service, rides: rides}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/rides/{rideId}/payment",
		httpx.RequirePermission("PAYMENT_READ", h.ofRide))
}

// Rider, assigned driver or ADMIN, exactly as GET /rides/{rideId} decides it.
func (h *Handler) ofRide(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	rideID, err := ParseRideID(r.PathValue("rideId"))
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := h.rides.EnsureReadable(r.Context(), rideID, principal); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	payments, err := h.service.FindByRide(r.Context(), rideID)
	if err != nil {
		httpx.Fail(w, r, httpx.ErrInternal())
		return
	}
	httpx.OK(w, r, payments)
}
