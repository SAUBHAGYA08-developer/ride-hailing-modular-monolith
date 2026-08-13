package config

import (
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
		RedisAddr:  env("REDIS_HOST", "localhost") + ":" + env("REDIS_PORT", "6379"),
		RedisUser:  env("REDIS_USERNAME", ""),
		RedisPass:  env("REDIS_PASSWORD", ""),
		RedisTLS:   env("REDIS_SSL", "false") == "true",
		JWTSecret:  env("JWT_SECRET", "dev-only-secret-change-me-please-0123456789abcdef"),
		JWTIssuer:  env("JWT_ISSUER", "ridehailing"),
		JWTExpiry:  envInt("JWT_EXPIRATION_SECONDS", 3600),
	}
}

// Go's driver wants user:pass@tcp(host:port)/db, not a JDBC URL, so it is assembled from parts.
func dsn() string {
	params := "?parseTime=true&loc=UTC&charset=utf8mb4"
	if env("DB_USE_SSL", "false") == "true" {
		params += "&tls=true"
	}
	return env("DB_USERNAME", "root") + ":" + env("DB_PASSWORD", "root") +
		"@tcp(" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "3306") + ")/" +
		env("DB_NAME", "ridehailing") + params
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
