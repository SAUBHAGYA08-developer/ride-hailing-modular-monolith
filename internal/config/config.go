package config

import (
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
)

// Same environment variables as the Java service, so one deployment config drives both.
type Config struct {
	Port       string
	DSN        string
	DBPoolSize int
	RedisAddr  string
	RedisUser  string
	RedisPass  string
	RedisTLS   bool
	JWTSecret  string
	JWTIssuer  string
	JWTExpiry  int
}

func Load() Config {
	return Config{
		Port:       env("PORT", env("SERVER_PORT", "8090")),
		DSN:        dsn(),
		DBPoolSize: envInt("DB_POOL_SIZE", 5),
		RedisAddr:  redisAddr(),
		RedisUser:  redisPart("user"),
		RedisPass:  redisPart("pass"),
		RedisTLS:   redisTLS(),
		JWTSecret:  env("JWT_SECRET", "dev-only-secret-change-me-please-0123456789abcdef"),
		JWTIssuer:  env("JWT_ISSUER", "ridehailing"),
		JWTExpiry:  envInt("JWT_EXPIRATION_SECONDS", 3600),
	}
}

// Go's driver wants user:pass@tcp(host:port)/db, so a JDBC DB_URL is translated rather than ignored.
func dsn() string {
	host, port, name := env("DB_HOST", "localhost"), env("DB_PORT", "3306"), env("DB_NAME", "ridehailing")
	tls := env("DB_USE_SSL", "false") == "true"
	// A managed MySQL usually presents its provider's own CA, so verification is opt in via DB_TLS_VERIFY.
	verify := env("DB_TLS_VERIFY", "false") == "true"

	// DB_URL wins when present, so one deployment config can drive this service and the Java one.
	if raw := env("DB_URL", ""); raw != "" {
		trimmed := strings.TrimPrefix(strings.TrimPrefix(raw, "jdbc:"), "mysql://")
		if parsed, err := url.Parse("mysql://" + trimmed); err == nil && parsed.Host != "" {
			if h, p, err := net.SplitHostPort(parsed.Host); err == nil {
				host, port = h, p
			} else {
				host = parsed.Host
			}
			if db := strings.TrimPrefix(parsed.Path, "/"); db != "" {
				name = db
			}
			// Both spellings appear in the wild; either one means the server demands TLS.
			mode := parsed.Query().Get("sslMode") + parsed.Query().Get("ssl-mode")
			tls = mode != "" && !strings.EqualFold(mode, "DISABLED") || parsed.Query().Get("useSSL") == "true"
			// JDBC REQUIRED encrypts without checking the server certificate; only VERIFY_* authenticates it.
			verify = strings.HasPrefix(strings.ToUpper(mode), "VERIFY")
		}
	}

	params := "?parseTime=true&loc=UTC&charset=utf8mb4"
	if tls {
		if verify {
			params += "&tls=true"
		} else {
			params += "&tls=skip-verify"
		}
	}
	return env("DB_USERNAME", "root") + ":" + env("DB_PASSWORD", "root") +
		"@tcp(" + host + ":" + port + ")/" + name + params
}

// A rediss:// URL carries host, port, user, password and TLS in one value; the fields below are the fallback.
func redisURL() *url.URL {
	raw := env("REDIS_URL", "")
	if raw == "" {
		return nil
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return nil
	}
	return parsed
}

func redisAddr() string {
	if u := redisURL(); u != nil {
		if _, _, err := net.SplitHostPort(u.Host); err == nil {
			return u.Host
		}
		if u.Scheme == "rediss" {
			return u.Host + ":6380"
		}
		return u.Host + ":6379"
	}
	return env("REDIS_HOST", "localhost") + ":" + env("REDIS_PORT", "6379")
}

func redisPart(which string) string {
	if u := redisURL(); u != nil && u.User != nil {
		if which == "user" {
			return u.User.Username()
		}
		pass, _ := u.User.Password()
		return pass
	}
	if which == "user" {
		return env("REDIS_USERNAME", "")
	}
	return env("REDIS_PASSWORD", "")
}

// The extra s in rediss:// is the whole TLS signal, exactly as the provider prints it.
func redisTLS() bool {
	if u := redisURL(); u != nil {
		return u.Scheme == "rediss"
	}
	return env("REDIS_SSL", "false") == "true"
}

func env(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int) int {
	if v, err := strconv.Atoi(env(key, "")); err == nil {
		return v
	}
	return fallback
}
