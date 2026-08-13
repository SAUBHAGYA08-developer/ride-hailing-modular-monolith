package ride

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-sql-driver/mysql"

	"ridehailing/internal/httpx"
)

const (
	ConfigIdempotencyTTLSeconds = "idempotency.ttl.seconds"
	defaultIdempotencyTTL       = 86400

	statusInProgress = "IN_PROGRESS"
	statusCompleted  = "COMPLETED"

	mysqlDuplicateEntry = 1062
)

// Duplicate suppression for booking. The guarantee comes from the unique key on
// (user_id, idempotency_key), never from a read-then-check: concurrent retries all
// attempt the insert and exactly one survives.
type Idempotency struct {
	db  *sql.DB
	cfg ConfigReader
}

func NewIdempotency(db *sql.DB, cfg ConfigReader) *Idempotency { return &Idempotency{db: db, cfg: cfg} }

// A fingerprint of the body, so the same key with a different request is refused rather than replayed.
func (i *Idempotency) Hash(payload any) string {
	raw, err := json.Marshal(payload)
	if err != nil {
		return ""
	}
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

// Nil response means the caller should proceed; a non-nil one is a completed result to replay.
func (i *Idempotency) Begin(ctx context.Context, userID int64, key, requestHash string) (*RideResponse, error) {
	now := time.Now().UTC()
	_, err := i.db.ExecContext(ctx,
		`INSERT INTO ride_schema.idempotency_keys
		   (user_id, idempotency_key, request_hash, status, created_at, expires_at)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		userID, key, requestHash, statusInProgress, now, now.Add(i.ttl(ctx)))
	if err == nil {
		return nil, nil
	}
	var my *mysql.MySQLError
	if !errors.As(err, &my) || my.Number != mysqlDuplicateEntry {
		return nil, httpx.ErrInternal()
	}
	return i.resolveExisting(ctx, userID, key, requestHash, now)
}

func (i *Idempotency) Complete(ctx context.Context, userID int64, key string, rideID int64, response RideResponse) {
	body, err := json.Marshal(response)
	if err != nil {
		slog.Warn("could not store idempotent response", "userId", userID, "err", err)
		return
	}
	if _, err := i.db.ExecContext(ctx,
		`UPDATE ride_schema.idempotency_keys SET status = ?, ride_id = ?, response_body = ?
		 WHERE user_id = ? AND idempotency_key = ?`,
		statusCompleted, rideID, string(body), userID, key); err != nil {
		slog.Warn("could not complete idempotency record", "userId", userID, "err", err)
	}
}

// Frees the key after a failed attempt so the client may retry; a failure here is survivable, the row expires by TTL.
func (i *Idempotency) Abort(ctx context.Context, userID int64, key string) {
	if _, err := i.db.ExecContext(ctx,
		`DELETE FROM ride_schema.idempotency_keys
		 WHERE user_id = ? AND idempotency_key = ? AND status = ?`,
		userID, key, statusInProgress); err != nil {
		slog.Warn("could not release idempotency key", "userId", userID, "err", err)
	}
}

// Nil, nil means the row had expired and has been reclaimed for this request.
func (i *Idempotency) resolveExisting(ctx context.Context, userID int64, key, requestHash string,
	now time.Time) (*RideResponse, error) {

	var (
		id        int64
		hash      string
		status    string
		body      sql.NullString
		expiresAt time.Time
	)
	err := i.db.QueryRowContext(ctx,
		`SELECT id, request_hash, status, response_body, expires_at
		 FROM ride_schema.idempotency_keys WHERE user_id = ? AND idempotency_key = ?`,
		userID, key).Scan(&id, &hash, &status, &body, &expiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		// The winner rolled back between the rejected insert and this read.
		return nil, httpx.Err("REQUEST_ALREADY_IN_PROGRESS", http.StatusConflict,
			"A request with this Idempotency-Key is already being processed")
	}
	if err != nil {
		return nil, httpx.ErrInternal()
	}

	if expiresAt.Before(now) {
		if _, err := i.db.ExecContext(ctx,
			`UPDATE ride_schema.idempotency_keys
			 SET request_hash = ?, status = ?, ride_id = NULL, response_body = NULL, created_at = ?, expires_at = ?
			 WHERE id = ?`,
			requestHash, statusInProgress, now, now.Add(i.ttl(ctx)), id); err != nil {
			return nil, httpx.ErrInternal()
		}
		return nil, nil
	}
	if hash != requestHash {
		return nil, httpx.Err("IDEMPOTENCY_KEY_REUSED", http.StatusConflict,
			"This Idempotency-Key was already used for a different request")
	}
	if status == statusInProgress {
		return nil, httpx.Err("REQUEST_ALREADY_IN_PROGRESS", http.StatusConflict,
			"A request with this Idempotency-Key is already being processed")
	}

	var replay RideResponse
	if !body.Valid || json.Unmarshal([]byte(body.String), &replay) != nil {
		return nil, httpx.ErrInternal()
	}
	return &replay, nil
}

func (i *Idempotency) ttl(ctx context.Context) time.Duration {
	return time.Duration(i.cfg.Int(ctx, ConfigIdempotencyTTLSeconds, defaultIdempotencyTTL)) * time.Second
}
