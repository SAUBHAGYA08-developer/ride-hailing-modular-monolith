package ride

import (
	"context"
	"database/sql"
	"time"

	"github.com/shopspring/decimal"
	"ridehailing/internal/payment"
)

// A bookable driver together with the vehicle that would serve the ride.
type AvailableDriver struct {
	DriverID  int64
	VehicleID int64
	CarType   string
	Rating    float64
	// The row version read during selection, handed back to Reserve unchanged so the claim is a compare-and-set.
	Version int64
}

// A driver seen in the Redis GEO set. A hit proves proximity only; MySQL decides availability.
type NearbyDriver struct {
	DriverID   int64
	DistanceKm float64
}

// Implemented by internal/driver; declared here so this package compiles against a signature, not a type.
type DriverReserver interface {
	FindAvailableCandidates(ctx context.Context, driverIDs []int64, carTypes []string) ([]AvailableDriver, error)
	// False means zero rows matched: a competing booking won the driver, which is expected rather than an error.
	Reserve(ctx context.Context, tx *sql.Tx, driverID, expectedVersion int64, actor string) (bool, error)
	Release(ctx context.Context, tx *sql.Tx, driverID int64, actor string) (bool, error)
	// Frees the driver and increments their ride counter in one statement, so a replay cannot double count.
	CompleteRide(ctx context.Context, tx *sql.Tx, driverID int64, actor string) (bool, error)
}

type DriverLocator interface {
	FindNearby(ctx context.Context, lat, lng float64, radiusKm float64, limit int) ([]NearbyDriver, error)
}

// The result of pricing one trip; every field is copied onto the ride row as its immutable snapshot.
type FareQuote struct {
	PricingRuleCode    string
	PricingZoneCode    string
	DistanceKm         decimal.Decimal
	DistanceFare       decimal.Decimal
	CarTypeMultiplier  decimal.Decimal
	SurgeMultiplier    decimal.Decimal
	MinimumFare        decimal.Decimal
	MinimumFareApplied bool
	FareBeforeDiscount decimal.Decimal
	CouponCode         string
	CouponID           *int64
	DiscountAmount     decimal.Decimal
	TotalFare          decimal.Decimal
	Breakdown          map[string]any
}

// Implemented by internal/pricing; main.go adapts its concrete quote onto this shape.
type Pricer interface {
	Quote(ctx context.Context, pickupLat, pickupLng, distanceKm decimal.Decimal,
		carType string, couponCode string, userID int64) (FareQuote, error)
	// Most preferred first: a requested type always precedes the upgrades that may substitute for it.
	AcceptableFor(requested string) []string
}

// The cached configuration reader every business setting is read through.
type ConfigReader interface {
	String(ctx context.Context, key string, fallback string) string
	Int(ctx context.Context, key string, fallback int) int
	Decimal(ctx context.Context, key string, fallback decimal.Decimal) decimal.Decimal
}

// Satisfied by payment.Service; the transaction is passed in so the ride row and its money row commit together.
type Payments interface {
	Collect(ctx context.Context, ex payment.Execer, method payment.Method,
		request payment.Request, actor string) (payment.Summary, error)
	FindLatest(ctx context.Context, rideID int64, purpose payment.Purpose) (*payment.Summary, error)
}

// Coordination only. Correctness comes from the conditional UPDATE in MySQL, never from this lock.
type BookingLock interface {
	// Returns the lock token when acquired, and false when the lock is held elsewhere.
	Acquire(ctx context.Context, userID int64, ttl time.Duration) (string, bool)
	Release(ctx context.Context, userID int64, token string)
}

// Canonical names of the configuration rows this module reads.
const (
	ConfigSearchRadiusKm            = "ride.search.radius.km"
	ConfigCancellationFeeAmount     = "ride.cancellation.fee.amount"
	ConfigCancellationGraceSeconds  = "ride.cancellation.fee.grace.seconds"
	ConfigBookingLockTTLSeconds     = "ride.booking.lock.ttl.seconds"
	ConfigPickupAverageSpeedKmph    = "ride.pickup.average.speed.kmph"
	ConfigCancellationFeeMethod     = "payment.cancellation.fee.method"
)
