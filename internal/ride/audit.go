package ride

import (
	"context"
	"database/sql"
	"encoding/json"
	"log/slog"
	"time"
)

// Writes the business audit trail on its own connection, never the caller's transaction.
//
// Spring used REQUIRES_NEW for the same reason: an audit problem must not roll
// back or fail a business operation that already succeeded.
type Audit struct {
	db *sql.DB
}

func NewAudit(db *sql.DB) *Audit { return &Audit{db: db} }

func (a *Audit) Record(ctx context.Context, entityType string, entityID int64, action string,
	oldValue, newValue map[string]any) {

	if a == nil || a.db == nil {
		return
	}
	actor := ActorOf(ctx)
	_, err := a.db.ExecContext(ctx, `INSERT INTO audit_schema.audit_logs
        (entity_type, entity_id, action, old_value, new_value, changed_by, changed_at, request_id, ip_address)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		entityType, itoa(entityID), action, serialise(oldValue), serialise(newValue),
		actor.Name, time.Now().UTC(), nullIfEmpty(actor.RequestID), nullIfEmpty(actor.IP))
	if err != nil {
		slog.Error("failed to write audit record", "entityType", entityType, "entityId", entityID,
			"action", action, "err", err)
	}
}

func serialise(value map[string]any) any {
	if value == nil {
		return nil
	}
	encoded, err := json.Marshal(value)
	if err != nil {
		slog.Warn("could not serialise audit payload", "err", err)
		return nil
	}
	return string(encoded)
}

func nullIfEmpty(value string) any {
	if value == "" {
		return nil
	}
	return value
}
