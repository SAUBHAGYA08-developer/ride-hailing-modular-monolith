package driver

import (
	"context"
	"net/http"

	"ridehailing/internal/httpx"
)

// The rider-facing shape from VehicleResponse; a driver may hold several, and only an active one is ever matched.
type VehicleResponse struct {
	ID                 int64  `json:"id"`
	DriverID           int64  `json:"driverId"`
	CarType            string `json:"carType"`
	RegistrationNumber string `json:"registrationNumber"`
	Make               string `json:"make"`
	Model              string `json:"model"`
	Color              string `json:"color"`
	Active             bool   `json:"active"`
}

func (s *Service) VehiclesOf(ctx context.Context, driverID int64) ([]VehicleResponse, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, driver_id, car_type, registration_number,
		        COALESCE(make,''), COALESCE(model,''), COALESCE(color,''), active
		 FROM driver_schema.vehicles WHERE driver_id = ? ORDER BY id`, driverID)
	if err != nil {
		return nil, httpx.ErrInternal()
	}
	defer rows.Close()

	out := []VehicleResponse{}
	for rows.Next() {
		var v VehicleResponse
		if err := rows.Scan(&v.ID, &v.DriverID, &v.CarType, &v.RegistrationNumber,
			&v.Make, &v.Model, &v.Color, &v.Active); err != nil {
			return nil, httpx.ErrInternal()
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (h *Handler) vehicles(w http.ResponseWriter, r *http.Request) {
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
	found, err := h.svc.VehiclesOf(r.Context(), driverID)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.OK(w, r, found)
}
