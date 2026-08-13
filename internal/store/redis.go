package store

import (
	"context"
	"crypto/tls"
	"time"

	"github.com/redis/go-redis/v9"
)

// Key names must match the Java service exactly: both read the same GEO set and freshness keys.
const (
	DriverGeoSet = "driver:locations"
)

func DriverFreshnessKey(driverID int64) string {
	return "driver:location:fresh:" + itoa(driverID)
}

func OpenRedis(addr, user, pass string, useTLS bool) (*redis.Client, error) {
	opts := &redis.Options{
		Addr:         addr,
		Username:     user,
		Password:     pass,
		DialTimeout:  10 * time.Second,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 10 * time.Second,
		PoolSize:     50,
		MinIdleConns: 5,
	}
	if useTLS {
		opts.TLSConfig = &tls.Config{MinVersion: tls.VersionTLS12}
	}
	client := redis.NewClient(opts)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := client.Ping(ctx).Err(); err != nil {
		return nil, err
	}
	return client, nil
}

func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	neg := v < 0
	if neg {
		v = -v
	}
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
