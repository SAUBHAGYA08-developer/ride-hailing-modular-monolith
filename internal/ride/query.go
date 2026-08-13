package ride

import (
	"context"
	"database/sql"

	"ridehailing/internal/auth"
	"ridehailing/internal/httpx"
)

// Read side with ownership enforcement: RIDE_READ says a caller may read rides, this decides which are theirs.
type QueryService struct {
	*core
}

func NewQueryService(db *sql.DB, cfg ConfigReader, payments Payments) *QueryService {
	return &QueryService{core: newCore(db, cfg, payments, nil)}
}

func (s *QueryService) GetByID(ctx context.Context, rideID int64, principal auth.Principal) (RideResponse, error) {
	found, err := s.readable(ctx, rideID, principal)
	if err != nil {
		return RideResponse{}, err
	}
	return s.mapper.toResponse(ctx, found)
}

// The ownership probe the payment module reuses: reading the ride is what proves the caller may see its money.
func (s *QueryService) EnsureReadable(ctx context.Context, rideID int64, principal auth.Principal) error {
	_, err := s.readable(ctx, rideID, principal)
	return err
}

func (s *QueryService) readable(ctx context.Context, rideID int64, principal auth.Principal) (*Ride, error) {
	found, err := s.repo.findByID(ctx, s.db, rideID)
	if err != nil {
		return nil, err
	}
	if principal.IsAdmin() {
		return found, nil
	}

	isRider := principal.UserID == found.UserID
	isAssignedDriver := false
	if principal.IsDriver() && found.DriverID != nil {
		driverID, err := s.repo.driverIDByUserID(ctx, principal.UserID)
		if err != nil {
			return nil, err
		}
		isAssignedDriver = driverID != nil && *driverID == *found.DriverID
	}
	if !isRider && !isAssignedDriver {
		return nil, httpx.Err("ACCESS_DENIED", 403, "You are not allowed to read this ride")
	}
	return found, nil
}

func (s *QueryService) FindByUser(ctx context.Context, userID int64, principal auth.Principal,
	page, size int) (PageResponse, error) {

	if !principal.IsAdmin() && principal.UserID != userID {
		return PageResponse{}, httpx.Err("ACCESS_DENIED", 403, "You are not allowed to read these rides")
	}
	rides, total, err := s.repo.listByUser(ctx, userID, page, size)
	if err != nil {
		return PageResponse{}, err
	}
	return s.page(ctx, rides, total, page, size)
}

func (s *QueryService) FindByDriver(ctx context.Context, driverID int64, principal auth.Principal,
	page, size int) (PageResponse, error) {

	if err := s.requireDriverOwnership(ctx, principal, driverID); err != nil {
		return PageResponse{}, err
	}
	rides, total, err := s.repo.listByDriver(ctx, driverID, page, size)
	if err != nil {
		return PageResponse{}, err
	}
	return s.page(ctx, rides, total, page, size)
}

// Admins pass; every other caller must be the driver themselves.
func (s *QueryService) requireDriverOwnership(ctx context.Context, principal auth.Principal, driverID int64) error {
	if principal.IsAdmin() {
		return nil
	}
	ownerUserID, err := s.repo.driverOwnerUserID(ctx, driverID)
	if err != nil {
		return err
	}
	if ownerUserID != principal.UserID {
		return httpx.Err("ACCESS_DENIED", 403, "You are not allowed to access this driver")
	}
	return nil
}

func (s *QueryService) page(ctx context.Context, rides []Ride, total int64, page, size int) (PageResponse, error) {
	items := make([]RideResponse, 0, len(rides))
	for index := range rides {
		item, err := s.mapper.toListResponse(ctx, &rides[index])
		if err != nil {
			return PageResponse{}, err
		}
		items = append(items, item)
	}
	totalPages := 0
	if size > 0 {
		totalPages = int((total + int64(size) - 1) / int64(size))
	}
	return PageResponse{Items: items, Page: page, Size: size, TotalElements: total, TotalPages: totalPages}, nil
}
