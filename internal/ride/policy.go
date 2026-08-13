package ride

import (
	"math"
	"net/http"
	"time"

	"github.com/shopspring/decimal"
	"ridehailing/internal/httpx"
)

// ----------------------------------------------------------------- geometry

const earthRadiusKm = 6371.0088

// Great circle distance, kept to three decimals like GeoUtils.
func DistanceKm(fromLat, fromLng, toLat, toLng float64) decimal.Decimal {
	dLat := degreesToRadians(toLat - fromLat)
	dLng := degreesToRadians(toLng - fromLng)
	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(degreesToRadians(fromLat))*math.Cos(degreesToRadians(toLat))*
			math.Sin(dLng/2)*math.Sin(dLng/2)
	c := 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
	return decimal.NewFromFloat(earthRadiusKm * c).Round(distanceScale)
}

func degreesToRadians(degrees float64) float64 { return degrees * math.Pi / 180 }

func validLatitude(value decimal.Decimal) bool {
	asFloat, _ := value.Float64()
	return asFloat >= -90 && asFloat <= 90
}

func validLongitude(value decimal.Decimal) bool {
	asFloat, _ := value.Float64()
	return asFloat >= -180 && asFloat <= 180
}

// ------------------------------------------------------------ state machine

// The only definition of which ride status may follow which, so COMPLETED and CANCELLED are terminal by construction.
var allowedTransitions = map[Status][]Status{
	StatusRequested:      {StatusDriverAssigned, StatusCancelled},
	StatusDriverAssigned: {StatusStarted, StatusCancelled},
	StatusStarted:        {StatusCompleted},
	StatusCompleted:      {},
	StatusCancelled:      {},
}

func CanTransition(from, to Status) bool {
	if from == "" || to == "" {
		return false
	}
	for _, allowed := range allowedTransitions[from] {
		if allowed == to {
			return true
		}
	}
	return false
}

func assertCanTransition(from, to Status) error {
	if CanTransition(from, to) {
		return nil
	}
	return httpx.Err("INVALID_RIDE_STATE_TRANSITION", http.StatusConflict,
		"A ride cannot move from "+string(from)+" to "+string(to))
}

// -------------------------------------------------------- cancellation fee

// The single place that decides whether a cancellation costs the rider anything.
//
// Only the rider is ever charged, nothing is charged unless a driver was
// actually assigned, and a grace window measured from the assignment makes an
// immediate change of mind free.
func CancellationFeeFor(cancelledBy CancelledBy, statusBeforeCancel Status, assignedAt *time.Time,
	cancelledAt time.Time, configuredFee decimal.Decimal, graceSeconds int64) decimal.Decimal {

	if cancelledBy != CancelledByUser {
		return zeroMoney()
	}
	if statusBeforeCancel != StatusDriverAssigned || assignedAt == nil || cancelledAt.IsZero() {
		return zeroMoney()
	}
	if configuredFee.Sign() <= 0 {
		return zeroMoney()
	}
	// A negative duration can only come from clock skew; treating it as inside the window fails in the rider's favour.
	heldSeconds := int64(math.Floor(cancelledAt.Sub(*assignedAt).Seconds()))
	// Charged at exactly graceSeconds: the window is the free period, and its last instant has passed.
	if heldSeconds < graceSeconds {
		return zeroMoney()
	}
	return roundMoney(configuredFee)
}
