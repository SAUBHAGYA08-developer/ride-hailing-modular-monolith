package ride

import (
	"context"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
)

// Releases only if this caller still owns the lock, so a lapsed holder cannot free someone else's.
var releaseScript = redis.NewScript(
	`if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end`)

// Short lived coordination lock. It reduces contention; correctness always comes from MySQL.
type RedisBookingLock struct {
	client *redis.Client
}

func NewRedisBookingLock(client *redis.Client) *RedisBookingLock {
	return &RedisBookingLock{client: client}
}

// Same key as RedisKeys.bookingLock, so the Java and Go services contend on one lock.
func bookingLockKey(userID int64) string { return "lock:booking:user:" + itoa(userID) }

func (l *RedisBookingLock) Acquire(ctx context.Context, userID int64, ttl time.Duration) (string, bool) {
	token := uuid.NewString()
	acquired, err := l.client.SetNX(ctx, bookingLockKey(userID), token, ttl).Result()
	if err != nil || !acquired {
		return "", false
	}
	return token, true
}

func (l *RedisBookingLock) Release(ctx context.Context, userID int64, token string) {
	if err := releaseScript.Run(ctx, l.client, []string{bookingLockKey(userID)}, token).Err(); err != nil {
		slog.Warn("failed to release the booking lock, it will expire by TTL", "userId", userID, "err", err)
	}
}
