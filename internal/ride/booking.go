package ride

import (
	"context"
	"database/sql"
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/shopspring/decimal"
	"ridehailing/internal/httpx"
)

// The candidate cap Java carried in app.booking.max-candidates.
const DefaultMaxCandidates = 20

const defaultSearchRadiusKm = 5

// Shared collaborators of the three ride services, so each file keeps the Java class it ports.
type core struct {
	db     *sql.DB
	repo   *repo
	mapper *mapper
	cfg    ConfigReader
	audit  *Audit
}

func newCore(db *sql.DB, cfg ConfigReader, payments Payments, audit *Audit) *core {
	repository := &repo{db: db}
	return &core{
		db:     db,
		repo:   repository,
		mapper: &mapper{repo: repository, payments: payments, cfg: cfg},
		cfg:    cfg,
		audit:  audit,
	}
}

// Orchestrates booking. Deliberately not one transaction: the Redis search and the
// pricing quote must not sit inside the reservation, which is kept as short as possible.
type BookingService struct {
	*core
	locator       DriverLocator
	reserver      DriverReserver
	pricer        Pricer
	lock          BookingLock
	maxCandidates int
}

func NewBookingService(db *sql.DB, locator DriverLocator, reserver DriverReserver, pricer Pricer,
	cfg ConfigReader, payments Payments, lock BookingLock, audit *Audit, maxCandidates int) *BookingService {

	if maxCandidates <= 0 {
		maxCandidates = DefaultMaxCandidates
	}
	return &BookingService{core: newCore(db, cfg, payments, audit), locator: locator, reserver: reserver,
		pricer: pricer, lock: lock, maxCandidates: maxCandidates}
}

func (s *BookingService) Book(ctx context.Context, userID int64, request CreateRideRequest) (RideResponse, error) {
	if err := request.Validate(); err != nil {
		return RideResponse{}, err
	}
	if s.lock == nil {
		return s.doBook(ctx, userID, request)
	}

	ttl := time.Duration(s.cfg.Int(ctx, ConfigBookingLockTTLSeconds, 10)) * time.Second
	token, acquired := s.lock.Acquire(ctx, userID, ttl)
	if !acquired {
		return RideResponse{}, httpx.Err("REQUEST_ALREADY_IN_PROGRESS", http.StatusConflict,
			"Another booking for this rider is still being processed")
	}
	defer s.lock.Release(ctx, userID, token)
	return s.doBook(ctx, userID, request)
}

func (s *BookingService) doBook(ctx context.Context, userID int64, request CreateRideRequest) (RideResponse, error) {
	distanceKm, err := validateTrip(request)
	if err != nil {
		return RideResponse{}, err
	}

	radiusKm := s.cfg.Decimal(ctx, ConfigSearchRadiusKm, decimal.NewFromInt(defaultSearchRadiusKm)).InexactFloat64()
	acceptable := acceptableCarTypes(s.pricer.AcceptableFor(string(*request.CarType)))

	pickupLat, _ := request.PickupLatitude.Float64()
	pickupLng, _ := request.PickupLongitude.Float64()
	nearby, err := s.locator.FindNearby(ctx, pickupLat, pickupLng, radiusKm, s.maxCandidates)
	if err != nil {
		return RideResponse{}, err
	}
	if len(nearby) == 0 {
		return RideResponse{}, noDriverInRadius(radiusKm)
	}

	// Redis only proposed candidates; MySQL decides who is actually bookable.
	driverIDs, distanceByDriver := firstDistancePerDriver(nearby)
	available, err := s.reserver.FindAvailableCandidates(ctx, driverIDs, carTypeNames(acceptable))
	if err != nil {
		return RideResponse{}, err
	}
	// The same code and message as an empty Redis result, which is what the Java original does.
	if len(available) == 0 {
		return RideResponse{}, noDriverInRadius(radiusKm)
	}

	candidates := make([]Candidate, 0, len(available))
	for _, driver := range available {
		distance, present := distanceByDriver[driver.DriverID]
		if !present {
			continue
		}
		candidates = append(candidates, Candidate{DriverID: driver.DriverID, VehicleID: driver.VehicleID,
			CarType: CarType(driver.CarType), Rating: driver.Rating, DistanceKm: distance, Version: driver.Version})
	}
	ranked := rankWithCarTypePreference(candidates, acceptable)

	// Priced before the transaction opens: it reads several tables and must not lengthen the driver reservation.
	quote, err := s.pricer.Quote(ctx, *request.PickupLatitude, *request.PickupLongitude, distanceKm,
		string(*request.CarType), request.couponCode(), userID)
	if err != nil {
		return RideResponse{}, err
	}

	created, err := s.reserveAndCreate(ctx, userID, request, distanceKm, quote, ranked)
	if err != nil {
		return RideResponse{}, err
	}

	s.audit.Record(ctx, "Ride", created.ID, "RIDE_CREATED", nil, map[string]any{
		"status": string(created.Status), "driverId": *created.DriverID,
		"requestedCarType": string(created.RequestedCarType), "assignedCarType": string(*created.AssignedCarType),
		"totalFare": created.TotalFare.String()})
	s.audit.Record(ctx, "Driver", *created.DriverID, "DRIVER_STATUS_CHANGED",
		map[string]any{"status": "AVAILABLE"}, map[string]any{"status": "BUSY", "rideId": created.ID})

	return s.mapper.toResponse(ctx, created)
}

// The one short transaction of the booking flow: claim a driver and create the ride.
func (s *BookingService) reserveAndCreate(ctx context.Context, userID int64, request CreateRideRequest,
	distanceKm decimal.Decimal, quote FareQuote, ranked []Candidate) (*Ride, error) {

	breakdown, err := json.Marshal(quote.Breakdown)
	if err != nil {
		return nil, httpx.ErrInternal()
	}
	actor := actorName(ctx)

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	var winner *Candidate
	for index := range ranked {
		candidate := ranked[index]
		// Zero rows means a competing booking claimed this driver since the availability read: expected, not an error.
		reserved, err := s.reserver.Reserve(ctx, tx, candidate.DriverID, candidate.Version, actor)
		if err != nil {
			return nil, err
		}
		if reserved {
			winner = &candidate
			break
		}
		slog.Debug("driver was taken by a competing booking, trying the next candidate",
			"driverId", candidate.DriverID)
	}
	if winner == nil {
		return nil, httpx.ErrNoDriver("No available driver could be reserved for this request")
	}

	now := time.Now().UTC()
	assignedCarType := winner.CarType
	// Not money, so it is rounded to the column's two decimals rather than through the money helper.
	pickupDistance := decimal.NewFromFloat(winner.DistanceKm).Round(moneyScale)
	created := &Ride{
		UserID:                 userID,
		DriverID:               &winner.DriverID,
		VehicleID:              &winner.VehicleID,
		Status:                 StatusDriverAssigned,
		RequestedCarType:       *request.CarType,
		AssignedCarType:        &assignedCarType,
		DriverPickupDistanceKm: &pickupDistance,
		PickupLatitude:         *request.PickupLatitude,
		PickupLongitude:        *request.PickupLongitude,
		PickupAddress:          request.PickupAddress,
		DropLatitude:           *request.DropLatitude,
		DropLongitude:          *request.DropLongitude,
		DropAddress:            request.DropAddress,
		DistanceKm:             distanceKm,
		PricingRuleCode:        quote.PricingRuleCode,
		PricingZoneCode:        emptyToNil(quote.PricingZoneCode),
		DistanceFare:           quote.DistanceFare,
		CarTypeMultiplier:      quote.CarTypeMultiplier,
		SurgeMultiplier:        quote.SurgeMultiplier,
		MinimumFare:            quote.MinimumFare,
		MinimumFareApplied:     quote.MinimumFareApplied,
		FareBeforeDiscount:     quote.FareBeforeDiscount,
		CouponCode:             emptyToNil(quote.CouponCode),
		DiscountAmount:         quote.DiscountAmount,
		TotalFare:              quote.TotalFare,
		FareBreakdown:          stringPtr(string(breakdown)),
		RequestedAt:            now,
		AssignedAt:             &now,
	}

	id, err := s.repo.insert(ctx, tx, created, actor)
	if err != nil {
		return nil, err
	}
	created.ID = id
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return created, nil
}

func validateTrip(request CreateRideRequest) (decimal.Decimal, error) {
	if !validLatitude(*request.PickupLatitude) || !validLongitude(*request.PickupLongitude) ||
		!validLatitude(*request.DropLatitude) || !validLongitude(*request.DropLongitude) {
		return decimal.Decimal{}, httpx.ErrInvalidTrip("Pickup or drop coordinates are out of range")
	}
	pickupLat, _ := request.PickupLatitude.Float64()
	pickupLng, _ := request.PickupLongitude.Float64()
	dropLat, _ := request.DropLatitude.Float64()
	dropLng, _ := request.DropLongitude.Float64()

	distanceKm := DistanceKm(pickupLat, pickupLng, dropLat, dropLng)
	if distanceKm.Sign() <= 0 {
		return decimal.Decimal{}, httpx.ErrInvalidTrip("Pickup and drop locations must be different")
	}
	return distanceKm, nil
}

// LinkedHashMap putIfAbsent: nearest hit wins for a repeated driver, and the search order is kept.
func firstDistancePerDriver(nearby []NearbyDriver) ([]int64, map[int64]float64) {
	driverIDs := make([]int64, 0, len(nearby))
	distanceByDriver := make(map[int64]float64, len(nearby))
	for _, hit := range nearby {
		if _, seen := distanceByDriver[hit.DriverID]; seen {
			continue
		}
		distanceByDriver[hit.DriverID] = hit.DistanceKm
		driverIDs = append(driverIDs, hit.DriverID)
	}
	return driverIDs, distanceByDriver
}

func acceptableCarTypes(names []string) []CarType {
	types := make([]CarType, 0, len(names))
	for _, name := range names {
		types = append(types, CarType(name))
	}
	return types
}

func carTypeNames(types []CarType) []string {
	names := make([]string, 0, len(types))
	for _, carType := range types {
		names = append(names, string(carType))
	}
	return names
}

func noDriverInRadius(radiusKm float64) error {
	return httpx.ErrNoDriver("No available driver found within " + javaDouble(radiusKm) + " km")
}

// Double.toString keeps a trailing .0, and this message is asserted by the existing pages.
func javaDouble(value float64) string {
	formatted := strconv.FormatFloat(value, 'f', -1, 64)
	for _, character := range formatted {
		if character == '.' {
			return formatted
		}
	}
	return formatted + ".0"
}

func emptyToNil(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}

func stringPtr(value string) *string { return &value }
