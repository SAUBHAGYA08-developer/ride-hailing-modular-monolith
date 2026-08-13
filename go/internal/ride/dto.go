package ride

import (
	"time"

	"github.com/shopspring/decimal"
	"ridehailing/internal/httpx"
	"ridehailing/internal/payment"
)

const maxAddressLength = 255
const maxCouponCodeLength = 40

// There is deliberately no userId field: the rider is always the authenticated principal.
type CreateRideRequest struct {
	PickupLatitude  *decimal.Decimal `json:"pickupLatitude"`
	PickupLongitude *decimal.Decimal `json:"pickupLongitude"`
	PickupAddress   *string          `json:"pickupAddress"`
	DropLatitude    *decimal.Decimal `json:"dropLatitude"`
	DropLongitude   *decimal.Decimal `json:"dropLongitude"`
	DropAddress     *string          `json:"dropAddress"`
	CarType         *CarType         `json:"carType"`
	CouponCode      *string          `json:"couponCode"`
}

// Stands in for the bean validation annotations, so a malformed booking is rejected before any lookup.
func (r CreateRideRequest) Validate() error {
	if r.PickupLatitude == nil || r.PickupLongitude == nil || r.DropLatitude == nil || r.DropLongitude == nil {
		return httpx.ErrValidation("Pickup and drop coordinates are required")
	}
	if r.CarType == nil {
		return httpx.ErrValidation("carType is required")
	}
	if !ValidCarType(*r.CarType) {
		return httpx.ErrMalformed()
	}
	if !validLatitude(*r.PickupLatitude) || !validLongitude(*r.PickupLongitude) ||
		!validLatitude(*r.DropLatitude) || !validLongitude(*r.DropLongitude) {
		return httpx.ErrValidation("Coordinates must be within -90..90 and -180..180")
	}
	if tooLong(r.PickupAddress, maxAddressLength) || tooLong(r.DropAddress, maxAddressLength) {
		return httpx.ErrValidation("An address may not exceed 255 characters")
	}
	if tooLong(r.CouponCode, maxCouponCodeLength) {
		return httpx.ErrValidation("A coupon code may not exceed 40 characters")
	}
	return nil
}

func (r CreateRideRequest) couponCode() string {
	if r.CouponCode == nil {
		return ""
	}
	return *r.CouponCode
}

// Asked at completion, not at booking: only the driver can say how they were actually paid.
type CompleteRideRequest struct {
	PaymentMethod *string `json:"paymentMethod"`
}

// Mandatory rather than defaulting to CASH - guessing how money moved would turn a client bug into a bogus ledger.
func (r CompleteRideRequest) Method() (payment.Method, error) {
	if r.PaymentMethod == nil || *r.PaymentMethod == "" {
		return "", httpx.ErrValidation("paymentMethod is required")
	}
	method, known := payment.ParseMethod(*r.PaymentMethod)
	if !known {
		return "", httpx.ErrMalformed()
	}
	return method, nil
}

type CancelRideRequest struct {
	Reason *string `json:"reason"`
}

func (r CancelRideRequest) Validate() error {
	if tooLong(r.Reason, maxAddressLength) {
		return httpx.ErrValidation("A cancellation reason may not exceed 255 characters")
	}
	return nil
}

func tooLong(value *string, max int) bool { return value != nil && len(*value) > max }

// The slice of a driver profile other modules are allowed to show a rider.
type DriverSummary struct {
	ID       int64  `json:"id"`
	FullName string `json:"fullName"`
	Phone    string `json:"phone"`
	Rating   Num    `json:"rating"`
}

// What a rider needs to identify the car at the kerb; no audit columns, no ownership detail.
type VehicleSummary struct {
	VehicleID          int64   `json:"vehicleId"`
	CarType            CarType `json:"carType"`
	RegistrationNumber string  `json:"registrationNumber"`
	Make               *string `json:"make,omitempty"`
	Model              *string `json:"model,omitempty"`
	Color              *string `json:"color,omitempty"`
}

// The pricing snapshot as stored on the ride. It never changes after creation.
type FareSummary struct {
	PricingRuleCode    string  `json:"pricingRuleCode"`
	PricingZoneCode    *string `json:"pricingZoneCode,omitempty"`
	DistanceFare       Num     `json:"distanceFare"`
	CarTypeMultiplier  Num     `json:"carTypeMultiplier"`
	SurgeMultiplier    Num     `json:"surgeMultiplier"`
	MinimumFare        Num     `json:"minimumFare"`
	MinimumFareApplied bool    `json:"minimumFareApplied"`
	FareBeforeDiscount Num     `json:"fareBeforeDiscount"`
	CouponCode         *string `json:"couponCode,omitempty"`
	DiscountAmount     Num     `json:"discountAmount"`
	TotalFare          Num     `json:"totalFare"`
}

// Field for field the Java RideResponse record, because the existing pages render these names.
type RideResponse struct {
	ID       int64          `json:"id"`
	Status   Status         `json:"status"`
	UserID   int64          `json:"userId"`
	DriverID *int64         `json:"driverId,omitempty"`
	Driver   *DriverSummary `json:"driver,omitempty"`

	VehicleID int64 `json:"vehicleId,omitempty"`
	// Null on paginated history: one lookup per row would be an N+1 against a remote database.
	Vehicle *VehicleSummary `json:"vehicle,omitempty"`

	RequestedCarType CarType  `json:"requestedCarType"`
	AssignedCarType  *CarType `json:"assignedCarType,omitempty"`
	CarTypeUpgraded  bool     `json:"carTypeUpgraded"`

	DistanceKm      Num     `json:"distanceKm"`
	PickupLatitude  Num     `json:"pickupLatitude"`
	PickupLongitude Num     `json:"pickupLongitude"`
	PickupAddress   *string `json:"pickupAddress,omitempty"`
	DropLatitude    Num     `json:"dropLatitude"`
	DropLongitude   Num     `json:"dropLongitude"`
	DropAddress     *string `json:"dropAddress,omitempty"`

	Fare FareSummary `json:"fare"`

	RequestedAt time.Time  `json:"requestedAt"`
	AssignedAt  *time.Time `json:"assignedAt,omitempty"`
	StartedAt   *time.Time `json:"startedAt,omitempty"`
	CompletedAt *time.Time `json:"completedAt,omitempty"`
	CancelledAt *time.Time `json:"cancelledAt,omitempty"`

	CancelledBy        *CancelledBy `json:"cancelledBy,omitempty"`
	CancellationReason *string      `json:"cancellationReason,omitempty"`
	// Kept out of the fare: that snapshot never changes, whereas this is decided at cancellation time.
	CancellationFee *Num `json:"cancellationFee,omitempty"`

	// The fare payment, absent until completion; a decline shows as FAILED with its reason.
	Payment *payment.Summary `json:"payment,omitempty"`

	// Straight-line km at assignment time, not road distance and not live.
	DriverPickupDistanceKm *Num `json:"driverPickupDistanceKm,omitempty"`
	// Rounded up: an ETA that rounds down promises a driver who cannot arrive that soon.
	EstimatedPickupEtaMinutes *int `json:"estimatedPickupEtaMinutes,omitempty"`
}

type PageResponse struct {
	Items         []RideResponse `json:"items"`
	Page          int            `json:"page"`
	Size          int            `json:"size"`
	TotalElements int64          `json:"totalElements"`
	TotalPages    int            `json:"totalPages"`
}
