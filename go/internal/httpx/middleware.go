package httpx

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"ridehailing/internal/auth"
)

type ctxKey int

const (
	ctxRequestID ctxKey = iota
	ctxPrincipal
)

const RequestIDHeader = "X-Request-Id"

func RequestIDOf(r *http.Request) string {
	if r == nil {
		return ""
	}
	if v, ok := r.Context().Value(ctxRequestID).(string); ok {
		return v
	}
	return ""
}

func PrincipalOf(r *http.Request) (auth.Principal, bool) {
	p, ok := r.Context().Value(ctxPrincipal).(auth.Principal)
	return p, ok
}

// Correlates every log line and response with one id, as the Java RequestIdFilter does.
func WithRequestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := strings.TrimSpace(r.Header.Get(RequestIDHeader))
		if id == "" {
			id = uuid.NewString()
		}
		w.Header().Set(RequestIDHeader, id)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxRequestID, id)))
	})
}

// Attaches a principal when a bearer token parses; refusing the request is each route's own decision.
func WithOptionalAuth(jwtService *auth.Service) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			header := r.Header.Get("Authorization")
			if strings.HasPrefix(header, "Bearer ") {
				if p, err := jwtService.Parse(strings.TrimPrefix(header, "Bearer ")); err == nil {
					r = r.WithContext(context.WithValue(r.Context(), ctxPrincipal, p))
				} else {
					slog.Debug("rejected jwt", "err", err)
				}
			}
			next.ServeHTTP(w, r)
		})
	}
}

func Recover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				slog.Error("unhandled panic", "requestId", RequestIDOf(r), "panic", rec)
				Fail(w, r, ErrInternal())
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func AccessLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		next.ServeHTTP(w, r)
		slog.Info("request", "requestId", RequestIDOf(r), "method", r.Method,
			"path", r.URL.Path, "ms", time.Since(started).Milliseconds())
	})
}

// Route guards standing in for @PreAuthorize; the permission set comes from the token.
func Authenticated(h func(http.ResponseWriter, *http.Request)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := PrincipalOf(r); !ok {
			Fail(w, r, ErrUnauthenticated())
			return
		}
		h(w, r)
	}
}

func RequirePermission(permission string, h func(http.ResponseWriter, *http.Request)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, ok := PrincipalOf(r)
		if !ok {
			Fail(w, r, ErrUnauthenticated())
			return
		}
		if !p.Has(permission) {
			Fail(w, r, ErrAccessDenied())
			return
		}
		h(w, r)
	}
}
