package driver

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"strconv"
	"time"

	"ridehailing/internal/auth"
	"ridehailing/internal/httpx"
)

const systemActor = "SYSTEM"

const selectDriver = `SELECT id, user_id, full_name, phone, license_number, status, rating, total_rides, created_at
	FROM driver_schema.drivers WHERE id = ?`

func (s *Service) GetByID(ctx context.Context, driverID int64) (DriverResponse, error) {
	return s.requireDriver(ctx, driverID)
}

// Admins pass; every other caller must be the driver themselves. The id is never taken from the body.
func (s *Service) RequireOwnership(ctx context.Context, principal auth.Principal, driverID int64) error {
	if principal.IsAdmin() {
		return nil
	}
	var ownerUserID int64
	err := s.db.QueryRowContext(ctx,
		"SELECT user_id FROM driver_schema.drivers WHERE id = ?", driverID).Scan(&ownerUserID)
	if errors.Is(err, sql.ErrNoRows) {
		return errDriverNotFound(driverID)
	}
	if err != nil {
		return err
	}
	if ownerUserID != principal.UserID {
		return httpx.Err("ACCESS_DENIED", 403, "You are not allowed to access this driver")
	}
	return nil
}

// Only the driver's own online/offline switch: BUSY belongs to ride reservation and is never set here.
func (s *Service) UpdateStatus(ctx context.Context, driverID int64, status, actor string) (DriverResponse, error) {
	current, err := s.requireDriver(ctx, driverID)
	if err != nil {
		return DriverResponse{}, err
	}

	if status == StatusBusy || current.Status == StatusBusy {
		return DriverResponse{}, httpx.Err("DRIVER_HAS_ACTIVE_RIDE", 409,
			"BUSY is managed by ride assignment and cannot be set directly")
	}
	if current.Status == status {
		return current, nil
	}

	// The old status is a predicate rather than a read-then-write, so a concurrent change loses instead of silently winning.
	result, err := s.db.ExecContext(ctx,
		`UPDATE driver_schema.drivers SET status = ?, version = version + 1,
			updated_at = CURRENT_TIMESTAMP(6), updated_by = ? WHERE id = ? AND status = ?`,
		status, actor, driverID, current.Status)
	if err != nil {
		return DriverResponse{}, err
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return DriverResponse{}, err
	}
	if changed == 0 {
		return DriverResponse{}, httpx.ErrConcurrent()
	}

	if status == StatusOffline {
		// Going offline must take the driver out of dispatch's view immediately.
		if err := s.RemoveLocation(ctx, driverID); err != nil {
			slog.Warn("could not remove the location of an offline driver", "driverId", driverID, "err", err)
		}
	} else {
		s.captureLocationSnapshot(ctx, driverID, actor)
	}

	current.Status = status
	return current, nil
}

// Copies the live Redis position into the MySQL recovery snapshot; never called on a GPS ping.
func (s *Service) captureLocationSnapshot(ctx context.Context, driverID int64, actor string) {
	position, found, err := s.CurrentPosition(ctx, driverID)
	if err != nil || !found {
		if err != nil {
			slog.Warn("could not capture the location snapshot", "driverId", driverID, "err", err)
		}
		return
	}
	// No version bump: a position write must never invalidate a candidate booking is about to reserve.
	_, err = s.db.ExecContext(ctx,
		`UPDATE driver_schema.drivers SET last_known_latitude = ?, last_known_longitude = ?,
			last_location_at = ?, updated_at = CURRENT_TIMESTAMP(6), updated_by = ? WHERE id = ?`,
		position.Latitude, position.Longitude, time.Now().UTC(), actor, driverID)
	if err != nil {
		slog.Warn("could not capture the location snapshot", "driverId", driverID, "err", err)
	}
}

func (s *Service) requireDriver(ctx context.Context, driverID int64) (DriverResponse, error) {
	var row DriverResponse
	var rating string
	err := s.db.QueryRowContext(ctx, selectDriver, driverID).Scan(&row.ID, &row.UserID, &row.FullName,
		&row.Phone, &row.LicenseNumber, &row.Status, &rating, &row.TotalRides, &row.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return DriverResponse{}, errDriverNotFound(driverID)
	}
	if err != nil {
		return DriverResponse{}, err
	}
	row.Rating = Decimal(rating)
	return row, nil
}

// The actor written into updated_by, matching CurrentUser.actorName.
func actorName(principal auth.Principal, authenticated bool) string {
	if authenticated && principal.Email != "" {
		return principal.Email
	}
	return systemActor
}

func errDriverNotFound(driverID int64) error {
	return httpx.ErrDriverNotFound("Driver " + strconv.FormatInt(driverID, 10) + " does not exist")
}
