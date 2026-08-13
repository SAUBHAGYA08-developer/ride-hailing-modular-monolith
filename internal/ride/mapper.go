package ride

import (
	"context"
	"log/slog"

	"github.com/shopspring/decimal"
	"ridehailing/internal/payment"
)

// Used only if the configuration row cannot be read, or reads as nonsense.
const defaultPickupSpeedKmph = 20

var minutesPerHour = decimal.NewFromInt(60)

type mapper struct {
	repo     *repo
	payments Payments
	cfg      ConfigReader
}

func (m *mapper) toResponse(ctx context.Context, r *Ride) (RideResponse, error) {
	return m.build(ctx, r, true)
}

// History rows skip the vehicle lookup: one query per row is an N+1, and a list needs no number plate.
func (m *mapper) toListResponse(ctx context.Context, r *Ride) (RideResponse, error) {
	return m.build(ctx, r, false)
}

func (m *mapper) build(ctx context.Context, r *Ride, includeVehicle bool) (RideResponse, error) {
	var driver *DriverSummary
	if r.DriverID != nil {
		found, err := m.repo.driverSummary(ctx, *r.DriverID)
		if err != nil {
			// A missing driver profile must not make an existing ride unreadable.
			slog.Warn("could not load the driver of a ride", "driverId", *r.DriverID, "rideId", r.ID)
		} else {
			driver = found
		}
	}

	var vehicle *VehicleSummary
	if includeVehicle && r.VehicleID != nil {
		found, err := m.repo.vehicleSummary(ctx, *r.VehicleID)
		if err != nil {
			// A missing vehicle row must not make an existing ride unreadable.
			slog.Warn("could not load the vehicle of a ride", "vehicleId", *r.VehicleID, "rideId", r.ID)
		} else {
			vehicle = found
		}
	}

	farePayment, err := m.farePayment(ctx, r)
	if err != nil {
		return RideResponse{}, err
	}

	response := RideResponse{
		ID:               r.ID,
		Status:           r.Status,
		UserID:           r.UserID,
		DriverID:         r.DriverID,
		Driver:           driver,
		Vehicle:          vehicle,
		RequestedCarType: r.RequestedCarType,
		AssignedCarType:  r.AssignedCarType,
		CarTypeUpgraded:  IsUpgrade(r.RequestedCarType, r.AssignedCarType),
		DistanceKm:       num(r.DistanceKm),
		PickupLatitude:   num(r.PickupLatitude),
		PickupLongitude:  num(r.PickupLongitude),
		PickupAddress:    r.PickupAddress,
		DropLatitude:     num(r.DropLatitude),
		DropLongitude:    num(r.DropLongitude),
		DropAddress:      r.DropAddress,
		Fare: FareSummary{
			PricingRuleCode:    r.PricingRuleCode,
			PricingZoneCode:    r.PricingZoneCode,
			DistanceFare:       num(r.DistanceFare),
			CarTypeMultiplier:  num(r.CarTypeMultiplier),
			SurgeMultiplier:    num(r.SurgeMultiplier),
			MinimumFare:        num(r.MinimumFare),
			MinimumFareApplied: r.MinimumFareApplied,
			FareBeforeDiscount: num(r.FareBeforeDiscount),
			CouponCode:         r.CouponCode,
			DiscountAmount:     num(r.DiscountAmount),
			TotalFare:          num(r.TotalFare),
		},
		RequestedAt:               r.RequestedAt,
		AssignedAt:                r.AssignedAt,
		StartedAt:                 r.StartedAt,
		CompletedAt:               r.CompletedAt,
		CancelledAt:               r.CancelledAt,
		CancelledBy:               r.CancelledBy,
		CancellationReason:        r.CancellationReason,
		CancellationFee:           numPtr(r.CancellationFee),
		Payment:                   farePayment,
		DriverPickupDistanceKm:    numPtr(r.DriverPickupDistanceKm),
		EstimatedPickupEtaMinutes: m.pickupEtaMinutes(ctx, r.DriverPickupDistanceKm),
	}
	if r.VehicleID != nil {
		response.VehicleID = *r.VehicleID
	}
	return response, nil
}

// Only a completed ride can have a fare payment, so every other status skips the lookup.
func (m *mapper) farePayment(ctx context.Context, r *Ride) (*payment.Summary, error) {
	if r.ID == 0 || r.Status != StatusCompleted || m.payments == nil {
		return nil, nil
	}
	return m.payments.FindLatest(ctx, r.ID, payment.PurposeRideFare)
}

// Rounded up, and a non-positive configured speed degrades to the default rather than dividing by zero.
func (m *mapper) pickupEtaMinutes(ctx context.Context, pickupDistanceKm *decimal.Decimal) *int {
	if pickupDistanceKm == nil || pickupDistanceKm.Sign() < 0 {
		return nil
	}
	speedKmph := defaultPickupSpeedKmph
	if m.cfg != nil {
		speedKmph = m.cfg.Int(ctx, ConfigPickupAverageSpeedKmph, defaultPickupSpeedKmph)
	}
	if speedKmph <= 0 {
		slog.Warn("configured pickup speed is not usable, falling back to the default",
			"configured", speedKmph, "fallback", defaultPickupSpeedKmph)
		speedKmph = defaultPickupSpeedKmph
	}
	minutes := int(pickupDistanceKm.Mul(minutesPerHour).
		Div(decimal.NewFromInt(int64(speedKmph))).Ceil().IntPart())
	return &minutes
}
