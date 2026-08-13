package driver

import (
	"net/http"
	"strconv"

	"ridehailing/internal/httpx"
)

const maxFleetPageSize = 100

type Handler struct {
	svc *Service
}

func NewHandler(svc *Service) *Handler {
	return &Handler{svc: svc}
}

// Same paths, verbs and permissions as DriverController; /fleet is a literal segment so it never parses as an id.
func (h *Handler) Routes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/drivers/fleet", httpx.RequirePermission("DRIVER_FLEET_READ", h.fleet))
	mux.HandleFunc("GET /api/v1/drivers/{driverId}", httpx.RequirePermission("DRIVER_READ", h.getByID))
	mux.HandleFunc("PUT /api/v1/drivers/{driverId}/location", httpx.RequirePermission("DRIVER_LOCATION_UPDATE", h.updateLocation))
	mux.HandleFunc("GET /api/v1/drivers/{driverId}/vehicles", httpx.RequirePermission("VEHICLE_READ", h.vehicles))
	mux.HandleFunc("PUT /api/v1/drivers/{driverId}/status", httpx.RequirePermission("DRIVER_STATUS_UPDATE", h.updateStatus))
}

// Guarded by DRIVER_FLEET_READ, not DRIVER_READ: every driver holds the latter for their own profile.
func (h *Handler) fleet(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	if status != "" && !isKnownStatus(status) {
		httpx.Fail(w, r, httpx.ErrMalformed())
		return
	}
	page, err := queryInt(r, "page", 0)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	size, err := queryInt(r, "size", 20)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}

	// Size is clamped rather than trusted: this endpoint already scans the whole GEO set.
	if page < 0 {
		page = 0
	}
	if size < 1 {
		size = 1
	}
	if size > maxFleetPageSize {
		size = maxFleetPageSize
	}

	snapshot, err := h.svc.FleetSnapshot(r.Context(), status, page, size)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, snapshot)
}

func (h *Handler) getByID(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	driverID, err := pathDriverID(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := h.svc.RequireOwnership(r.Context(), principal, driverID); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	response, err := h.svc.GetByID(r.Context(), driverID)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, response)
}

// Position updates go to Redis only, and the path id is verified against the authenticated identity.
func (h *Handler) updateLocation(w http.ResponseWriter, r *http.Request) {
	principal, _ := httpx.PrincipalOf(r)
	driverID, err := pathDriverID(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var request UpdateLocationRequest
	if err := httpx.Decode(r, &request); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if request.Latitude == nil || request.Longitude == nil {
		httpx.Fail(w, r, httpx.ErrValidation("Request validation failed"))
		return
	}
	if err := h.svc.RequireOwnership(r.Context(), principal, driverID); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := h.svc.UpdateLocation(r.Context(), driverID, *request.Latitude, *request.Longitude); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.NoContent(w)
}

func (h *Handler) updateStatus(w http.ResponseWriter, r *http.Request) {
	principal, authenticated := httpx.PrincipalOf(r)
	driverID, err := pathDriverID(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var request UpdateStatusRequest
	if err := httpx.Decode(r, &request); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if request.Status == nil {
		httpx.Fail(w, r, httpx.ErrValidation("Request validation failed"))
		return
	}
	// An unrecognised status is an unreadable body, exactly as a failed enum bind is in Spring.
	if !isKnownStatus(*request.Status) {
		httpx.Fail(w, r, httpx.ErrMalformed())
		return
	}
	if err := h.svc.RequireOwnership(r.Context(), principal, driverID); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	response, err := h.svc.UpdateStatus(r.Context(), driverID, *request.Status,
		actorName(principal, authenticated))
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, response)
}

func pathDriverID(r *http.Request) (int64, error) {
	driverID, err := strconv.ParseInt(r.PathValue("driverId"), 10, 64)
	if err != nil {
		return 0, httpx.ErrMalformed()
	}
	return driverID, nil
}

func queryInt(r *http.Request, name string, fallback int) (int, error) {
	raw := r.URL.Query().Get(name)
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return 0, httpx.ErrMalformed()
	}
	return value, nil
}
