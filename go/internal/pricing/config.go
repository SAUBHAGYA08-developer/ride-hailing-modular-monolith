package pricing

import (
	"database/sql"
	"errors"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/shopspring/decimal"

	"ridehailing/internal/httpx"
)

// The canonical names of the DB driven settings this slice reads; ConfigKeys in Java.
const (
	KeyPricingActiveRule = "pricing.active.rule"
	KeySurgeEnabled      = "surge.enabled"
)

// app.cache.configuration-ttl-seconds: the Java default, so both services see a config change at the same pace.
const configCacheTTL = 300 * time.Second

// Read side of business configuration. MySQL stays the source of truth; the cache only removes the per request read.
type ConfigReader struct {
	db  *sql.DB
	ttl time.Duration

	mu     sync.RWMutex
	cached map[string]cachedConfig
}

type cachedConfig struct {
	value   string
	expires time.Time
}

func NewConfigReader(db *sql.DB) *ConfigReader {
	return &ConfigReader{db: db, ttl: configCacheTTL, cached: map[string]cachedConfig{}}
}

// The raw value, or CONFIGURATION_NOT_FOUND - ConfigurationService.getString(key) with no fallback.
func (c *ConfigReader) Raw(key string) (string, error) {
	if value, ok := c.fromCache(key); ok {
		return value, nil
	}
	var value string
	err := c.db.QueryRow(
		`SELECT config_value FROM configuration_schema.configurations WHERE config_key = ?`, key).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return "", httpx.Err("CONFIGURATION_NOT_FOUND", 404, "Unknown configuration key: "+key)
	}
	if err != nil {
		return "", httpx.Err("DEPENDENCY_UNAVAILABLE", 503, "A dependency is temporarily unavailable, please retry")
	}
	c.store(key, value)
	return value, nil
}

func (c *ConfigReader) GetString(key, fallback string) string {
	value, err := c.Raw(key)
	if err != nil {
		return fallback
	}
	return value
}

func (c *ConfigReader) GetInt(key string, fallback int) int {
	value, err := c.Raw(key)
	if err == nil {
		if parsed, convErr := strconv.Atoi(strings.TrimSpace(value)); convErr == nil {
			return parsed
		}
	}
	slog.Warn("falling back for configuration", "key", key, "fallback", fallback)
	return fallback
}

func (c *ConfigReader) GetDecimal(key string, fallback decimal.Decimal) decimal.Decimal {
	value, err := c.Raw(key)
	if err == nil {
		if parsed, convErr := decimal.NewFromString(strings.TrimSpace(value)); convErr == nil {
			return parsed
		}
	}
	slog.Warn("falling back for configuration", "key", key, "fallback", Plain(fallback))
	return fallback
}

// Boolean.parseBoolean: anything that is not "true" ignoring case is false.
func (c *ConfigReader) GetBool(key string, fallback bool) bool {
	value, err := c.Raw(key)
	if err != nil {
		return fallback
	}
	return strings.EqualFold(strings.TrimSpace(value), "true")
}

// Drops one key, for whoever owns the admin write path.
func (c *ConfigReader) Invalidate(key string) {
	c.mu.Lock()
	delete(c.cached, key)
	c.mu.Unlock()
}

func (c *ConfigReader) fromCache(key string) (string, bool) {
	c.mu.RLock()
	entry, ok := c.cached[key]
	c.mu.RUnlock()
	return entry.value, ok && time.Now().Before(entry.expires)
}

// Only present values are cached, as in Java: a missing key must stay missing until somebody inserts it.
func (c *ConfigReader) store(key, value string) {
	c.mu.Lock()
	c.cached[key] = cachedConfig{value: value, expires: time.Now().Add(c.ttl)}
	c.mu.Unlock()
}
