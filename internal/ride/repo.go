package ride

import (
	"context"
	"database/sql"
	"net/http"
	"strconv"
	"time"

	"github.com/shopspring/decimal"
	"ridehailing/internal/httpx"
)

// Both *sql.DB and *sql.Tx satisfy this, so a read can join the caller's transaction unchanged.
type queryer interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

const rideColumns = `id, user_id, driver_id, vehicle_id, status, requested_car_type, assigned_car_type,
    driver_pickup_distance_km, pickup_latitude, pickup_longitude, pickup_address, drop_latitude, drop_longitude,
    drop_address, distance_km, pricing_rule_code, pricing_zone_code, distance_fare, car_type_multiplier,
    surge_multiplier, minimum_fare, minimum_fare_applied, fare_before_discount, coupon_code, discount_amount,
    total_fare, fare_breakdown, requested_at, assigned_at, started_at, completed_at, cancelled_at, cancelled_by,
    cancellation_reason, cancellation_fee, version`

type repo struct {
	db *sql.DB
}

func (rp *repo) insert(ctx context.Context, q queryer, r *Ride, actor string) (int64, error) {
	res, err := q.ExecContext(ctx, `INSERT INTO ride_schema.rides
        (user_id, driver_id, vehicle_id, status, requested_car_type, assigned_car_type, driver_pickup_distance_km,
         pickup_latitude, pickup_longitude, pickup_address, drop_latitude, drop_longitude, drop_address, distance_km,
         pricing_rule_code, pricing_zone_code, distance_fare, car_type_multiplier, surge_multiplier, minimum_fare,
         minimum_fare_applied, fare_before_discount, coupon_code, discount_amount, total_fare, fare_breakdown,
         requested_at, assigned_at, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		r.UserID, r.DriverID, r.VehicleID, r.Status, r.RequestedCarType, r.AssignedCarType,
		decimalArg(r.DriverPickupDistanceKm), r.PickupLatitude.String(), r.PickupLongitude.String(), r.PickupAddress,
		r.DropLatitude.String(), r.DropLongitude.String(), r.DropAddress, r.DistanceKm.String(),
		r.PricingRuleCode, r.PricingZoneCode, r.DistanceFare.String(), r.CarTypeMultiplier.String(),
		r.SurgeMultiplier.String(), r.MinimumFare.String(), r.MinimumFareApplied, r.FareBeforeDiscount.String(),
		r.CouponCode, r.DiscountAmount.String(), r.TotalFare.String(), r.FareBreakdown,
		r.RequestedAt, r.AssignedAt, actor, actor)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// Only the mutable columns: the pricing snapshot is written once and never updated.
func (rp *repo) update(ctx context.Context, q queryer, r *Ride, actor string) error {
	res, err := q.ExecContext(ctx, `UPDATE ride_schema.rides
        SET status = ?, assigned_at = ?, started_at = ?, completed_at = ?, cancelled_at = ?, cancelled_by = ?,
            cancellation_reason = ?, cancellation_fee = ?, updated_by = ?, version = version + 1
        WHERE id = ? AND version = ?`,
		r.Status, r.AssignedAt, r.StartedAt, r.CompletedAt, r.CancelledAt, r.CancelledBy,
		r.CancellationReason, decimalArg(r.CancellationFee), actor, r.ID, r.Version)
	if err != nil {
		return err
	}
	affected, err := res.RowsAffected()
	if err != nil {
		return err
	}
	// Zero rows means the @Version guard lost: someone else edited this ride first.
	if affected == 0 {
		return httpx.ErrConcurrent()
	}
	r.Version++
	return nil
}

func (rp *repo) findByID(ctx context.Context, q queryer, rideID int64) (*Ride, error) {
	row := q.QueryRowContext(ctx, `SELECT `+rideColumns+` FROM ride_schema.rides WHERE id = ?`, rideID)
	found, err := scanRide(row)
	if err == sql.ErrNoRows {
		return nil, rideNotFound(rideID)
	}
	return found, err
}

func (rp *repo) listByUser(ctx context.Context, userID int64, page, size int) ([]Ride, int64, error) {
	return rp.list(ctx, `user_id`, userID, page, size)
}

func (rp *repo) listByDriver(ctx context.Context, driverID int64, page, size int) ([]Ride, int64, error) {
	return rp.list(ctx, `driver_id`, driverID, page, size)
}

// Newest first, matching the (user_id, requested_at DESC) and (driver_id, requested_at DESC) indexes.
func (rp *repo) list(ctx context.Context, column string, value int64, page, size int) ([]Ride, int64, error) {
	var total int64
	if err := rp.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM ride_schema.rides WHERE `+column+` = ?`, value).Scan(&total); err != nil {
		return nil, 0, err
	}

	rows, err := rp.db.QueryContext(ctx, `SELECT `+rideColumns+` FROM ride_schema.rides
        WHERE `+column+` = ? ORDER BY requested_at DESC LIMIT ? OFFSET ?`, value, size, page*size)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	rides := make([]Ride, 0, size)
	for rows.Next() {
		found, err := scanRide(rows)
		if err != nil {
			return nil, 0, err
		}
		rides = append(rides, *found)
	}
	return rides, total, rows.Err()
}

func (rp *repo) driverSummary(ctx context.Context, driverID int64) (*DriverSummary, error) {
	var (
		summary DriverSummary
		rating  sql.NullString
	)
	err := rp.db.QueryRowContext(ctx,
		`SELECT id, full_name, phone, rating FROM driver_schema.drivers WHERE id = ?`, driverID).
		Scan(&summary.ID, &summary.FullName, &summary.Phone, &rating)
	if err != nil {
		return nil, err
	}
	summary.Rating = num(parseDecimal(rating))
	return &summary, nil
}

func (rp *repo) vehicleSummary(ctx context.Context, vehicleID int64) (*VehicleSummary, error) {
	var (
		summary                 VehicleSummary
		makeOf, model, colourOf sql.NullString
	)
	err := rp.db.QueryRowContext(ctx,
		`SELECT id, car_type, registration_number, make, model, color FROM driver_schema.vehicles WHERE id = ?`,
		vehicleID).Scan(&summary.VehicleID, &summary.CarType, &summary.RegistrationNumber, &makeOf, &model, &colourOf)
	if err != nil {
		return nil, err
	}
	summary.Make = nullableString(makeOf)
	summary.Model = nullableString(model)
	summary.Color = nullableString(colourOf)
	return &summary, nil
}

// The assigned driver is resolved from the token through this lookup, never from the request body.
func (rp *repo) driverIDByUserID(ctx context.Context, userID int64) (*int64, error) {
	var driverID int64
	err := rp.db.QueryRowContext(ctx,
		`SELECT id FROM driver_schema.drivers WHERE user_id = ?`, userID).Scan(&driverID)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &driverID, nil
}

func (rp *repo) driverOwnerUserID(ctx context.Context, driverID int64) (int64, error) {
	var userID int64
	err := rp.db.QueryRowContext(ctx,
		`SELECT user_id FROM driver_schema.drivers WHERE id = ?`, driverID).Scan(&userID)
	if err == sql.ErrNoRows {
		return 0, httpx.ErrDriverNotFound("Driver " + itoa(driverID) + " does not exist")
	}
	return userID, err
}

func scanRide(row interface{ Scan(dest ...any) error }) (*Ride, error) {
	var (
		r                                                Ride
		driverID, vehicleID                              sql.NullInt64
		assignedCarType, pickupAddress, dropAddress      sql.NullString
		pricingZone, couponCode, fareBreakdown           sql.NullString
		cancelledBy, cancellationReason                  sql.NullString
		pickupDistance, cancellationFee                  sql.NullString
		pickupLat, pickupLng, dropLat, dropLng, distance sql.NullString
		distanceFare, carMultiplier, surge, minimumFare  sql.NullString
		beforeDiscount, discount, totalFare              sql.NullString
		assignedAt, startedAt, completedAt, cancelledAt  sql.NullTime
	)
	err := row.Scan(&r.ID, &r.UserID, &driverID, &vehicleID, &r.Status, &r.RequestedCarType, &assignedCarType,
		&pickupDistance, &pickupLat, &pickupLng, &pickupAddress, &dropLat, &dropLng, &dropAddress, &distance,
		&r.PricingRuleCode, &pricingZone, &distanceFare, &carMultiplier, &surge, &minimumFare,
		&r.MinimumFareApplied, &beforeDiscount, &couponCode, &discount, &totalFare, &fareBreakdown,
		&r.RequestedAt, &assignedAt, &startedAt, &completedAt, &cancelledAt, &cancelledBy,
		&cancellationReason, &cancellationFee, &r.Version)
	if err != nil {
		return nil, err
	}

	if driverID.Valid {
		r.DriverID = &driverID.Int64
	}
	if vehicleID.Valid {
		r.VehicleID = &vehicleID.Int64
	}
	if assignedCarType.Valid {
		assigned := CarType(assignedCarType.String)
		r.AssignedCarType = &assigned
	}
	r.DriverPickupDistanceKm = parseDecimalPtr(pickupDistance)
	r.PickupLatitude = parseDecimal(pickupLat)
	r.PickupLongitude = parseDecimal(pickupLng)
	r.PickupAddress = nullableString(pickupAddress)
	r.DropLatitude = parseDecimal(dropLat)
	r.DropLongitude = parseDecimal(dropLng)
	r.DropAddress = nullableString(dropAddress)
	r.DistanceKm = parseDecimal(distance)
	r.PricingZoneCode = nullableString(pricingZone)
	r.DistanceFare = parseDecimal(distanceFare)
	r.CarTypeMultiplier = parseDecimal(carMultiplier)
	r.SurgeMultiplier = parseDecimal(surge)
	r.MinimumFare = parseDecimal(minimumFare)
	r.FareBeforeDiscount = parseDecimal(beforeDiscount)
	r.CouponCode = nullableString(couponCode)
	r.DiscountAmount = parseDecimal(discount)
	r.TotalFare = parseDecimal(totalFare)
	r.FareBreakdown = nullableString(fareBreakdown)
	r.AssignedAt = nullableTime(assignedAt)
	r.StartedAt = nullableTime(startedAt)
	r.CompletedAt = nullableTime(completedAt)
	r.CancelledAt = nullableTime(cancelledAt)
	if cancelledBy.Valid {
		by := CancelledBy(cancelledBy.String)
		r.CancelledBy = &by
	}
	r.CancellationReason = nullableString(cancellationReason)
	r.CancellationFee = parseDecimalPtr(cancellationFee)
	return &r, nil
}

func nullableString(value sql.NullString) *string {
	if !value.Valid {
		return nil
	}
	return &value.String
}

func nullableTime(value sql.NullTime) *time.Time {
	if !value.Valid {
		return nil
	}
	return &value.Time
}

// Sent as a string so the driver never turns a DECIMAL into a float on the way in.
func decimalArg(value *decimal.Decimal) any {
	if value == nil {
		return nil
	}
	return value.String()
}

func rideNotFound(rideID int64) error {
	return httpx.Err("RIDE_NOT_FOUND", http.StatusNotFound, "Ride "+itoa(rideID)+" does not exist")
}

func itoa(value int64) string { return strconv.FormatInt(value, 10) }
