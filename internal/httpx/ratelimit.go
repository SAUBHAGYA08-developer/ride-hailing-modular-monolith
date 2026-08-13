package httpx

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"strconv"

	"github.com/redis/go-redis/v9"
)

// Fixed window counter. INCR and PEXPIRE run in one Lua call so a window can never exist without a TTL.
var incrementScript = redis.NewScript(`
local current = redis.call('INCR', KEYS[1])
if current == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return { current, redis.call('PTTL', KEYS[1]) }
`)

// Only the int lookups are needed, so the limiter does not depend on whoever owns configuration.
type IntConfig interface {
	GetInt(key string, fallback int) int
}

type Subject int

const (
	SubjectPrincipal Subject = iota
	SubjectIP
)

// Limits are database driven; these constants are the fallback when configuration cannot be read.
type Policy struct {
	Name           string
	MaxKey         string
	WindowKey      string
	DefaultMax     int
	DefaultWindowS int
	Subject        Subject
}

var (
	PolicyLogin          = Policy{"LOGIN", "api.rate-limit.login.max", "api.rate-limit.login.window.seconds", 5, 60, SubjectIP}
	PolicyRideCreate     = Policy{"RIDE_CREATE", "api.rate-limit.ride.max", "api.rate-limit.ride.window.seconds", 10, 60, SubjectPrincipal}
	PolicyDriverLocation = Policy{"DRIVER_LOCATION", "api.rate-limit.location.max", "api.rate-limit.location.window.seconds", 60, 60, SubjectPrincipal}
	PolicyPricingQuote   = Policy{"PRICING_QUOTE", "api.rate-limit.quote.max", "api.rate-limit.quote.window.seconds", 60, 60, SubjectPrincipal}
	PolicyAdminAPI       = Policy{"ADMIN_API", "api.rate-limit.admin.max", "api.rate-limit.admin.window.seconds", 30, 60, SubjectPrincipal}
)

type Limiter struct {
	rdb *redis.Client
	cfg IntConfig
}

func NewLimiter(rdb *redis.Client, cfg IntConfig) *Limiter { return &Limiter{rdb, cfg} }

// Key layout matches RedisKeys.rateLimit exactly: both services share one Redis.
func (l *Limiter) Limit(p Policy, h func(http.ResponseWriter, *http.Request)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		subject := l.subjectOf(p, r)
		if subject == "" {
			h(w, r)
			return
		}
		max := l.cfg.GetInt(p.MaxKey, p.DefaultMax)
		windowMillis := int64(l.cfg.GetInt(p.WindowKey, p.DefaultWindowS)) * 1000
		key := "ratelimit:" + p.Name + ":" + subject

		current, ttlMillis, err := l.incr(r.Context(), key, windowMillis)
		if err != nil {
			// Rate limiting protects capacity; refusing traffic because the protection is down is self inflicted.
			slog.Error("rate limiter unavailable, failing open", "key", key, "err", err)
			h(w, r)
			return
		}
		if current > int64(max) {
			retryAfter := (ttlMillis + 999) / 1000
			if retryAfter < 1 {
				retryAfter = 1
			}
			w.Header().Set("Retry-After", strconv.FormatInt(retryAfter, 10))
			Fail(w, r, Err("RATE_LIMIT_EXCEEDED", http.StatusTooManyRequests,
				"Too many requests, retry in "+strconv.FormatInt(retryAfter, 10)+"s"))
			return
		}
		h(w, r)
	}
}

func (l *Limiter) incr(ctx context.Context, key string, windowMillis int64) (int64, int64, error) {
	res, err := incrementScript.Run(ctx, l.rdb, []string{key}, windowMillis).Slice()
	if err != nil {
		return 0, 0, err
	}
	if len(res) < 2 {
		return 0, 0, nil
	}
	current, _ := res[0].(int64)
	ttl, _ := res[1].(int64)
	return current, ttl, nil
}

func (l *Limiter) subjectOf(p Policy, r *http.Request) string {
	if p.Subject == SubjectIP {
		return clientIP(r)
	}
	if principal, ok := PrincipalOf(r); ok {
		return strconv.FormatInt(principal.UserID, 10)
	}
	// No identity yet means the route's own auth guard will refuse it anyway.
	return ""
}

func clientIP(r *http.Request) string {
	if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
		for i := 0; i < len(forwarded); i++ {
			if forwarded[i] == ',' {
				return forwarded[:i]
			}
		}
		return forwarded
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
