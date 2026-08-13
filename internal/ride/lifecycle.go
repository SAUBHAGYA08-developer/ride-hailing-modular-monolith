package ride

import (
	"context"
	"database/sql"
	"log/slog"
	"time"

	"github.com/shopspring/decimal"
	"ridehailing/internal/auth"
	"ridehailing/internal/httpx"
	"ridehailing/internal/payment"
)

// Used only if the configuration row cannot be read at all.
var defaultCancellationFee = decimal.RequireFromString("30.00")

// A cancellation fee cannot be cash: rider and driver never met.
const defaultCancellationFeeMethod = payment.MethodUPI

const defaultCancellationGraceSeconds = 120

// State transitions after assignment. The version column supplies optimistic
// locking, so a concurrent edit surfaces as CONCURRENT_MODIFICATION and the caller retries.
type LifecycleService struct {
	*core
	reserver DriverReserver
	payments Payments
}

func NewLifecycleService(db *sql.DB, reserver DriverReserver, cfg ConfigReader,
	payments Payments, audit *Audit) *LifecycleService {
	return &LifecycleService{core: newCore(db, cfg, payments, audit), reserver: reserver, payments: payments}
}

func (s *LifecycleService) Start(ctx context.Context, rideID int64, principal auth.Principal) (RideResponse, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return RideResponse{}, err
	}
	defer tx.Rollback()

	found, err := s.repo.findByID(ctx, tx, rideID)
	if err != nil {
		return RideResponse{}, err
	}
	if err := s.requireAssignedDriverOrAdmin(ctx, found, principal); err != nil {
		return RideResponse{}, err
	}
	err = s.transition(ctx, tx, found, StatusStarted, func(now time.Time) { found.StartedAt = &now })
	if err != nil {
		return RideResponse{}, err
	}
	if err := tx.Commit(); err != nil {
		return RideResponse{}, err
	}
	return s.mapper.toResponse(ctx, found)
}

// A declined payment never undoes the completion: the ride stands and the row is written FAILED.
func (s *LifecycleService) Complete(ctx context.Context, rideID int64, principal auth.Principal,
	method payment.Method) (RideResponse, error) {

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return RideResponse{}, err
	}
	defer tx.Rollback()

	found, err := s.repo.findByID(ctx, tx, rideID)
	if err != nil {
		return RideResponse{}, err
	}
	if err := s.requireAssignedDriverOrAdmin(ctx, found, principal); err != nil {
		return RideResponse{}, err
	}
	err = s.transition(ctx, tx, found, StatusCompleted, func(now time.Time) { found.CompletedAt = &now })
	if err != nil {
		return RideResponse{}, err
	}

	if found.DriverID != nil {
		freed, err := s.reserver.CompleteRide(ctx, tx, *found.DriverID, actorName(ctx))
		if err != nil {
			return RideResponse{}, err
		}
		if !freed {
			slog.Warn("driver was not BUSY when the ride completed", "driverId", *found.DriverID, "rideId", rideID)
		}
		s.audit.Record(ctx, "Driver", *found.DriverID, "DRIVER_STATUS_CHANGED",
			map[string]any{"status": "BUSY"}, map[string]any{"status": "AVAILABLE", "rideId": rideID})
	}

	// After the release, which must not be delayed by anything; replay is guarded inside the payment module.
	collected, err := s.payments.Collect(ctx, tx, method, paymentRequest(found, payment.PurposeRideFare),
		actorName(ctx))
	if err != nil {
		return RideResponse{}, err
	}
	slog.Info("ride completed and the fare was collected", "rideId", rideID,
		"amount", collected.Amount.Decimal().String(), "method", collected.Method, "status", collected.Status)

	if err := tx.Commit(); err != nil {
		return RideResponse{}, err
	}
	// Mapped only after the commit, so the response carries the payment that was just written.
	return s.mapper.toResponse(ctx, found)
}

func (s *LifecycleService) Cancel(ctx context.Context, rideID int64, principal auth.Principal,
	reason *string) (RideResponse, error) {

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return RideResponse{}, err
	}
	defer tx.Rollback()

	found, err := s.repo.findByID(ctx, tx, rideID)
	if err != nil {
		return RideResponse{}, err
	}
	cancelledBy, err := s.resolveCanceller(ctx, found, principal)
	if err != nil {
		return RideResponse{}, err
	}

	driverID := found.DriverID
	// Read before the transition overwrites it: the fee depends on whether a driver had been dispatched.
	statusBeforeCancel := found.Status
	configuredFee := s.cfg.Decimal(ctx, ConfigCancellationFeeAmount, defaultCancellationFee)
	graceSeconds := int64(s.cfg.Int(ctx, ConfigCancellationGraceSeconds, defaultCancellationGraceSeconds))

	err = s.transition(ctx, tx, found, StatusCancelled, func(now time.Time) {
		found.CancelledAt = &now
		found.CancelledBy = &cancelledBy
		found.CancellationReason = reason
		fee := CancellationFeeFor(cancelledBy, statusBeforeCancel, found.AssignedAt, now,
			configuredFee, graceSeconds)
		found.CancellationFee = &fee
	})
	if err != nil {
		return RideResponse{}, err
	}

	if driverID != nil {
		released, err := s.reserver.Release(ctx, tx, *driverID, actorName(ctx))
		if err != nil {
			return RideResponse{}, err
		}
		if !released {
			slog.Warn("driver was not BUSY when the ride was cancelled", "driverId", *driverID, "rideId", rideID)
		}
		s.audit.Record(ctx, "Driver", *driverID, "DRIVER_STATUS_CHANGED",
			map[string]any{"status": "BUSY"}, map[string]any{"status": "AVAILABLE", "rideId": rideID})
	}

	// Only a non-zero fee is a financial event, so a free cancellation leaves neither audit row nor payment row.
	if found.CancellationFee != nil && found.CancellationFee.Sign() > 0 {
		s.audit.Record(ctx, "Ride", rideID, "RIDE_STATUS_CHANGED", nil, map[string]any{
			"cancellationFee": found.CancellationFee.String(),
			"cancelledBy":     string(cancelledBy),
			"graceSeconds":    graceSeconds})
		// Same module as the fare, distinguished only by purpose: a second collection path would be a second ledger.
		_, err := s.payments.Collect(ctx, tx, s.cancellationFeeMethod(ctx),
			paymentRequest(found, payment.PurposeCancellationFee), actorName(ctx))
		if err != nil {
			return RideResponse{}, err
		}
	}

	if err := tx.Commit(); err != nil {
		return RideResponse{}, err
	}
	return s.mapper.toResponse(ctx, found)
}

// Returns nothing but the mutation, so callers can finish their side effects before anything is mapped.
func (s *LifecycleService) transition(ctx context.Context, tx *sql.Tx, r *Ride, target Status,
	mutate func(now time.Time)) error {

	current := r.Status
	if err := assertCanTransition(current, target); err != nil {
		return err
	}
	mutate(time.Now().UTC())
	r.Status = target
	if err := s.repo.update(ctx, tx, r, actorName(ctx)); err != nil {
		return err
	}
	s.audit.Record(ctx, "Ride", r.ID, "RIDE_STATUS_CHANGED",
		map[string]any{"status": string(current)}, map[string]any{"status": string(target)})
	return nil
}

// Every figure comes off the ride row, so no request body can redirect a charge.
func paymentRequest(r *Ride, purpose payment.Purpose) payment.Request {
	amount := r.TotalFare
	if purpose == payment.PurposeCancellationFee && r.CancellationFee != nil {
		amount = *r.CancellationFee
	}
	return payment.Request{RideID: r.ID, UserID: r.UserID, DriverID: r.DriverID, Amount: amount, Purpose: purpose}
}

// A typo degrades to UPI with a warning: the rider has already cancelled.
func (s *LifecycleService) cancellationFeeMethod(ctx context.Context) payment.Method {
	configured := s.cfg.String(ctx, ConfigCancellationFeeMethod, string(defaultCancellationFeeMethod))
	method, known := payment.ParseMethod(configured)
	if !known {
		slog.Warn("unknown cancellation fee payment method, falling back",
			"configured", configured, "fallback", defaultCancellationFeeMethod)
		return defaultCancellationFeeMethod
	}
	return method
}

// The assigned driver is resolved from the token, never from the request.
func (s *LifecycleService) requireAssignedDriverOrAdmin(ctx context.Context, r *Ride,
	principal auth.Principal) error {

	if principal.IsAdmin() {
		return nil
	}
	var driverID *int64
	if principal.IsDriver() {
		found, err := s.repo.driverIDByUserID(ctx, principal.UserID)
		if err != nil {
			return err
		}
		driverID = found
	}
	if driverID == nil || r.DriverID == nil || *driverID != *r.DriverID {
		return httpx.Err("ACCESS_DENIED", 403, "Only the assigned driver may change this ride")
	}
	return nil
}

func (s *LifecycleService) resolveCanceller(ctx context.Context, r *Ride,
	principal auth.Principal) (CancelledBy, error) {

	if principal.IsAdmin() {
		return CancelledByAdmin, nil
	}
	if principal.IsDriver() {
		driverID, err := s.repo.driverIDByUserID(ctx, principal.UserID)
		if err != nil {
			return "", err
		}
		if driverID != nil && r.DriverID != nil && *driverID == *r.DriverID {
			return CancelledByDriver, nil
		}
	} else if principal.UserID == r.UserID {
		return CancelledByUser, nil
	}
	return "", httpx.Err("ACCESS_DENIED", 403, "You are not allowed to cancel this ride")
}
