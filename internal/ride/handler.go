package ride

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strconv"

	"ridehailing/internal/auth"
	"ridehailing/internal/httpx"
)

const maxPageSize = 100
const defaultPageSize = 20

type Handler struct {
	booking   *BookingService
	lifecycle *LifecycleService
	query     *QueryService
}

func NewHandler(booking *BookingService, lifecycle *LifecycleService, query *QueryService) *Handler {
	return &Handler{booking: booking, lifecycle: lifecycle, query: query}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/rides", httpx.RequirePermission("RIDE_CREATE", h.book))
	mux.Handle("GET /api/v1/rides/{rideId}", httpx.RequirePermission("RIDE_READ", h.getByID))
	mux.Handle("POST /api/v1/rides/{rideId}/start", httpx.RequirePermission("RIDE_START", h.start))
	mux.Handle("POST /api/v1/rides/{rideId}/complete", httpx.RequirePermission("RIDE_COMPLETE", h.complete))
	mux.Handle("POST /api/v1/rides/{rideId}/cancel", httpx.RequirePermission("RIDE_CANCEL", h.cancel))
	mux.Handle("GET /api/v1/users/{userId}/rides", httpx.RequirePermission("RIDE_READ", h.ridesOfUser))
	mux.Handle("GET /api/v1/drivers/{driverId}/rides", httpx.RequirePermission("RIDE_READ", h.ridesOfDriver))
}

// The rider is the authenticated principal; the request body carries no userId.
func (h *Handler) book(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	var request CreateRideRequest
	if err := httpx.Decode(r, &request); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	booked, err := h.booking.Book(contextOf(r, principal), principal.UserID, request)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.Created(w, r, booked)
}

func (h *Handler) getByID(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	rideID, err := pathID(r, "rideId")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	found, err := h.query.GetByID(contextOf(r, principal), rideID, principal)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, found)
}

func (h *Handler) start(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	rideID, err := pathID(r, "rideId")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	started, err := h.lifecycle.Start(contextOf(r, principal), rideID, principal)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, started)
}

// The payment method is mandatory, so an absent body is a 400 rather than a guessed cash ledger.
func (h *Handler) complete(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	rideID, err := pathID(r, "rideId")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var request CompleteRideRequest
	if err := httpx.Decode(r, &request); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	method, err := request.Method()
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	completed, err := h.lifecycle.Complete(contextOf(r, principal), rideID, principal, method)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, completed)
}

func (h *Handler) cancel(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	rideID, err := pathID(r, "rideId")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	// The body is optional here, unlike completion: a cancellation needs no reason.
	var request CancelRideRequest
	if err := decodeOptional(r, &request); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := request.Validate(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	cancelled, err := h.lifecycle.Cancel(contextOf(r, principal), rideID, principal, request.Reason)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, cancelled)
}

func (h *Handler) ridesOfUser(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	userID, err := pathID(r, "userId")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	page, size := pageable(r)
	rides, err := h.query.FindByUser(contextOf(r, principal), userID, principal, page, size)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, rides)
}

func (h *Handler) ridesOfDriver(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	driverID, err := pathID(r, "driverId")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	page, size := pageable(r)
	rides, err := h.query.FindByDriver(contextOf(r, principal), driverID, principal, page, size)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, rides)
}

// Carries what Spring read from a thread local, so audit rows and created_by name the real caller.
func contextOf(r *http.Request, principal auth.Principal) context.Context {
	name := principal.Email
	if name == "" {
		name = SystemActor
	}
	return WithActor(r.Context(), Actor{Name: name, RequestID: httpx.RequestIDOf(r), IP: clientIP(r)})
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func pathID(r *http.Request, name string) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	if err != nil || id <= 0 {
		return 0, httpx.ErrValidation(name + " must be a positive number")
	}
	return id, nil
}

// An absent body leaves the zero value, as @RequestBody(required = false) does, but a broken one still fails.
func decodeOptional(r *http.Request, into any) error {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return httpx.ErrMalformed()
	}
	if len(bytes.TrimSpace(body)) == 0 {
		return nil
	}
	if err := json.Unmarshal(body, into); err != nil {
		return httpx.ErrMalformed()
	}
	return nil
}

func pageable(r *http.Request) (int, int) {
	page := queryInt(r, "page", 0)
	size := queryInt(r, "size", defaultPageSize)
	if page < 0 {
		page = 0
	}
	if size < 1 {
		size = 1
	}
	if size > maxPageSize {
		size = maxPageSize
	}
	return page, size
}

func queryInt(r *http.Request, name string, fallback int) int {
	raw := r.URL.Query().Get(name)
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return value
}
