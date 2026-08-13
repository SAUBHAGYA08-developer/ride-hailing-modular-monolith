package payment

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"ridehailing/internal/httpx"
)

// Both *sql.DB and *sql.Tx satisfy this, so a collection joins the caller's transaction rather than opening its own.
type Execer interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// Written by the ride module against audit_schema, and always outside the caller's transaction.
type AuditRecorder interface {
	Record(ctx context.Context, entityType string, entityID int64, action string, oldValue, newValue map[string]any)
}

const selectColumns = `id, ride_id, purpose, method, amount, status, reference, failure_reason, collected_at`

// Owns payment_schema. The only code allowed to read or write payments.
type Service struct {
	db      *sql.DB
	factory *StrategyFactory
	audit   AuditRecorder
}

func NewService(db *sql.DB, factory *StrategyFactory, audit AuditRecorder) *Service {
	return &Service{db: db, factory: factory, audit: audit}
}

// Idempotent by design: an existing SUCCESS row is returned unchanged and no strategy is asked twice.
func (s *Service) Collect(ctx context.Context, ex Execer, method Method, request Request, actor string) (Summary, error) {
	if err := validate(request); err != nil {
		return Summary{}, err
	}
	if ex == nil {
		ex = s.db
	}

	settled, err := s.findSettled(ctx, ex, request.RideID, request.Purpose)
	if err != nil {
		return Summary{}, err
	}
	if settled != nil {
		slog.Info("payment was already collected, returning the original row",
			"rideId", request.RideID, "purpose", request.Purpose)
		return *settled, nil
	}

	strategy, err := s.factory.ForMethod(method)
	if err != nil {
		return Summary{}, err
	}
	result := strategy.Collect(ctx, request)

	amount := Round(request.Amount)
	collectedAt := time.Now().UTC()
	res, err := ex.ExecContext(ctx, `INSERT INTO payment_schema.payments
        (ride_id, user_id, driver_id, purpose, method, amount, status, reference, failure_reason,
         collected_at, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		request.RideID, request.UserID, request.DriverID, request.Purpose, method, amount.String(),
		result.Status, nullable(result.Reference), nullable(result.FailureReason), collectedAt, actor, actor)
	if err != nil {
		return Summary{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Summary{}, err
	}

	summary := Summary{ID: id, RideID: request.RideID, Purpose: request.Purpose, Method: method,
		Status: result.Status, Amount: Num(amount), Reference: nullable(result.Reference),
		FailureReason: nullable(result.FailureReason), CollectedAt: collectedAt}
	s.recordAudit(ctx, summary)
	return summary, nil
}

// The latest attempt, because a rider needs the decline still outstanding, not the first of three.
func (s *Service) FindLatest(ctx context.Context, rideID int64, purpose Purpose) (*Summary, error) {
	row := s.db.QueryRowContext(ctx, `SELECT `+selectColumns+` FROM payment_schema.payments
        WHERE ride_id = ? AND purpose = ? ORDER BY id DESC LIMIT 1`, rideID, purpose)
	summary, err := scanRow(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return summary, err
}

func (s *Service) FindByRide(ctx context.Context, rideID int64) ([]Summary, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+selectColumns+` FROM payment_schema.payments
        WHERE ride_id = ? ORDER BY id ASC`, rideID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	summaries := make([]Summary, 0)
	for rows.Next() {
		summary, err := scanRow(rows)
		if err != nil {
			return nil, err
		}
		summaries = append(summaries, *summary)
	}
	return summaries, rows.Err()
}

// At most one row can match, because a second SUCCESS is never inserted.
func (s *Service) findSettled(ctx context.Context, ex Execer, rideID int64, purpose Purpose) (*Summary, error) {
	row := ex.QueryRowContext(ctx, `SELECT `+selectColumns+` FROM payment_schema.payments
        WHERE ride_id = ? AND purpose = ? AND status = ? LIMIT 1`, rideID, purpose, StatusSuccess)
	summary, err := scanRow(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return summary, err
}

// Runs before any strategy, so a partner is never handed a null or negative charge to interpret.
func validate(request Request) error {
	if request.RideID == 0 || request.UserID == 0 || request.Purpose == "" {
		return httpx.ErrValidation("A payment needs a ride, a payer and a purpose")
	}
	if request.Amount.IsNegative() {
		return httpx.ErrValidation("A payment amount must be present and non negative")
	}
	return nil
}

// Filed under the Ride entity so the money appears on the same timeline as the status changes that caused it.
func (s *Service) recordAudit(ctx context.Context, summary Summary) {
	if s.audit == nil {
		return
	}
	details := map[string]any{
		"purpose": string(summary.Purpose),
		"method":  string(summary.Method),
		"amount":  summary.Amount.Decimal().String(),
		"status":  string(summary.Status),
	}
	if summary.Reference != nil {
		details["reference"] = *summary.Reference
	}
	if summary.FailureReason != nil {
		details["failureReason"] = *summary.FailureReason
	}
	action := "PAYMENT_FAILED"
	if summary.Status == StatusSuccess {
		action = "PAYMENT_COLLECTED"
	}
	s.audit.Record(ctx, "Ride", summary.RideID, action, nil, details)
}

type scanner interface {
	Scan(dest ...any) error
}

func scanRow(row scanner) (*Summary, error) {
	var (
		summary                      Summary
		amount, reference, failedFor sql.NullString
	)
	err := row.Scan(&summary.ID, &summary.RideID, &summary.Purpose, &summary.Method, &amount,
		&summary.Status, &reference, &failedFor, &summary.CollectedAt)
	if err != nil {
		return nil, err
	}
	summary.Amount = parseNum(amount)
	if reference.Valid {
		summary.Reference = &reference.String
	}
	if failedFor.Valid {
		summary.FailureReason = &failedFor.String
	}
	return &summary, nil
}

func nullable(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}

// A payment that cannot be addressed by ride is unanswerable in support, so the id must parse.
func ParseRideID(raw string) (int64, error) {
	id, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || id <= 0 {
		return 0, httpx.Err("VALIDATION_ERROR", http.StatusBadRequest, "Ride id must be a positive number")
	}
	return id, nil
}
