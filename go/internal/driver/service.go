package driver

import (
	"context"
	"database/sql"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// Same MySQL rows and the same Redis keys as the Java service, so both can run side by side.
type Service struct {
	db  *sql.DB
	rdb *redis.Client
	cfg *configCache
}

func NewService(db *sql.DB, rdb *redis.Client) *Service {
	return &Service{db: db, rdb: rdb, cfg: newConfigCache(db, 300*time.Second)}
}

// Business settings live in MySQL; a GPS ping must not pay a round trip for one, so reads are cached.
type configCache struct {
	db      *sql.DB
	ttl     time.Duration
	mu      sync.RWMutex
	entries map[string]configEntry
}

type configEntry struct {
	value   string
	expires time.Time
}

func newConfigCache(db *sql.DB, ttl time.Duration) *configCache {
	return &configCache{db: db, ttl: ttl, entries: map[string]configEntry{}}
}

// Falls back rather than fails: an unreadable setting must not take an endpoint down.
func (c *configCache) getInt(ctx context.Context, key string, fallback int) int {
	raw, ok := c.get(ctx, key)
	if !ok {
		return fallback
	}
	parsed, err := strconv.Atoi(strings.TrimSpace(raw))
	if err != nil {
		slog.Warn("falling back for configuration", "key", key, "fallback", fallback)
		return fallback
	}
	return parsed
}

func (c *configCache) get(ctx context.Context, key string) (string, bool) {
	c.mu.RLock()
	entry, found := c.entries[key]
	c.mu.RUnlock()
	if found && time.Now().Before(entry.expires) {
		return entry.value, true
	}

	var value string
	err := c.db.QueryRowContext(ctx,
		"SELECT config_value FROM configuration_schema.configurations WHERE config_key = ?", key).Scan(&value)
	if err != nil {
		// A failed read is not cached, so the next call retries instead of pinning the fallback for 300s.
		slog.Warn("configuration read failed", "key", key, "err", err)
		return "", false
	}

	c.mu.Lock()
	c.entries[key] = configEntry{value: value, expires: time.Now().Add(c.ttl)}
	c.mu.Unlock()
	return value, true
}

// Placeholder list for an IN clause; callers must never pass an empty slice.
func placeholders(count int) string {
	if count <= 0 {
		return ""
	}
	return strings.Repeat(",?", count)[1:]
}

func int64Args(values []int64) []any {
	args := make([]any, len(values))
	for i, v := range values {
		args[i] = v
	}
	return args
}

func stringArgs(values []string) []any {
	args := make([]any, len(values))
	for i, v := range values {
		args[i] = v
	}
	return args
}
