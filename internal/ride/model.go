// Package ride owns ride_schema: booking, the lifecycle transitions and the read side.
package ride

import (
	"context"
	"database/sql"
	"time"

	"github.com/shopspring/decimal"
)

// Vehicle categories the platform sells; substitution rules come from the pricing module.
type CarType string

const (
	CarSedan     CarType = "SEDAN"
	CarHatchback CarType = "HATCHBACK"
	CarAuto      CarType = "AUTO"
	CarBike      CarType = "BIKE"
)

// Ranks upgrades against downgrades only; the fare comes from the pricing rule, not from here.
var carTypeRank = map[CarType]int{CarBike: 0, CarAuto: 1, CarHatchback: 2, CarSedan: 3}

func ValidCarType(value CarType) bool {
	_, known := carTypeRank[value]
	return known
}

// Rank based, so a downgrade is never reported as an upgrade on the ride response.
func IsUpgrade(requested CarType, assigned *CarType) bool {
	if assigned == nil || *assigned == requested {
		return false
	}
	assignedRank, assignedKnown := carTypeRank[*assigned]
	requestedRank, requestedKnown := carTypeRank[requested]
	if !assignedKnown {
		return false
	}
	if !requestedKnown {
		return true
	}
	return assignedRank > requestedRank
}

// Lifecycle of a ride. The legal moves between these values live in the state machine.
type Status string

const (
	StatusRequested      Status = "REQUESTED"
	StatusDriverAssigned Status = "DRIVER_ASSIGNED"
	StatusStarted        Status = "STARTED"
	StatusCompleted      Status = "COMPLETED"
	StatusCancelled      Status = "CANCELLED"
)

// Always derived from the authenticated principal, never from the request body, so the trail cannot be forged.
type CancelledBy string

const (
	CancelledByUser   CancelledBy = "USER"
	CancelledByDriver CancelledBy = "DRIVER"
	CancelledByAdmin  CancelledBy = "ADMIN"
)

// A ride and the immutable pricing snapshot it was sold at.
type Ride struct {
	ID        int64
	UserID    int64
	DriverID  *int64
	VehicleID *int64
	Status    Status

	RequestedCarType CarType
	AssignedCarType  *CarType

	// Straight-line haversine km from the Redis GEO search, snapshotted at assignment.
	DriverPickupDistanceKm *decimal.Decimal

	PickupLatitude  decimal.Decimal
	PickupLongitude decimal.Decimal
	PickupAddress   *string
	DropLatitude    decimal.Decimal
	DropLongitude   decimal.Decimal
	DropAddress     *string
	DistanceKm      decimal.Decimal

	// Pricing snapshot, written once at creation and never updated.
	PricingRuleCode    string
	PricingZoneCode    *string
	DistanceFare       decimal.Decimal
	CarTypeMultiplier  decimal.Decimal
	SurgeMultiplier    decimal.Decimal
	MinimumFare        decimal.Decimal
	MinimumFareApplied bool
	FareBeforeDiscount decimal.Decimal
	CouponCode         *string
	DiscountAmount     decimal.Decimal
	TotalFare          decimal.Decimal
	FareBreakdown      *string

	RequestedAt        time.Time
	AssignedAt         *time.Time
	StartedAt          *time.Time
	CompletedAt        *time.Time
	CancelledAt        *time.Time
	CancelledBy        *CancelledBy
	CancellationReason *string
	CancellationFee    *decimal.Decimal

	// Optimistic locking column: a concurrent edit of the same ride surfaces as CONCURRENT_MODIFICATION.
	Version int64
}

// Jackson writes a BigDecimal as a JSON number with its scale intact, so this must not be a quoted string.
type Num decimal.Decimal

func (n Num) MarshalJSON() ([]byte, error) {
	return []byte(decimal.Decimal(n).String()), nil
}

// Needed because MarshalJSON writes a bare number: without this, a stored response cannot be read back
// and an idempotent replay fails.
func (n *Num) UnmarshalJSON(raw []byte) error {
	var value decimal.Decimal
	if err := value.UnmarshalJSON(raw); err != nil {
		return err
	}
	*n = Num(value)
	return nil
}

func (n Num) Decimal() decimal.Decimal { return decimal.Decimal(n) }

func num(value decimal.Decimal) Num { return Num(value) }

func numPtr(value *decimal.Decimal) *Num {
	if value == nil {
		return nil
	}
	converted := Num(*value)
	return &converted
}

// Distance keeps three decimals, as GeoUtils and the distance_km column do.
const distanceScale = 3

// Every monetary amount is rounded here and nowhere else, half away from zero as BigDecimal HALF_UP.
const moneyScale = 2

func roundMoney(value decimal.Decimal) decimal.Decimal { return value.Round(moneyScale) }

func zeroMoney() decimal.Decimal { return decimal.New(0, -moneyScale) }

// Parsed rather than converted, so the scale MySQL reported survives into the response.
func parseDecimal(raw sql.NullString) decimal.Decimal {
	if !raw.Valid {
		return decimal.Decimal{}
	}
	value, err := decimal.NewFromString(raw.String)
	if err != nil {
		return decimal.Decimal{}
	}
	return value
}

func parseDecimalPtr(raw sql.NullString) *decimal.Decimal {
	if !raw.Valid {
		return nil
	}
	value, err := decimal.NewFromString(raw.String)
	if err != nil {
		return nil
	}
	return &value
}

type ctxKey int

const ctxActor ctxKey = iota

// The values Spring read from a thread local: who acted, under which request, from where.
type Actor struct {
	Name      string
	RequestID string
	IP        string
}

// SYSTEM is the fallback written into created_by when nothing identified the caller.
const SystemActor = "SYSTEM"

func WithActor(ctx context.Context, actor Actor) context.Context {
	if actor.Name == "" {
		actor.Name = SystemActor
	}
	return context.WithValue(ctx, ctxActor, actor)
}

func ActorOf(ctx context.Context) Actor {
	if value, ok := ctx.Value(ctxActor).(Actor); ok {
		return value
	}
	return Actor{Name: SystemActor}
}

func actorName(ctx context.Context) string { return ActorOf(ctx).Name }
