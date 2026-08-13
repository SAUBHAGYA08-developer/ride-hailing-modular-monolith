package pricing

import (
	"context"
	"database/sql"
	"log/slog"
	"sort"
	"strings"

	"github.com/shopspring/decimal"

	"ridehailing/internal/geo"
	"ridehailing/internal/httpx"
)

// The result of pricing one trip; the ride slice copies these onto the ride row and stores Breakdown verbatim.
type FareQuote struct {
	PricingRuleCode    string    `json:"pricingRuleCode"`
	PricingZoneCode    *string   `json:"pricingZoneCode"`
	DistanceKm         Amount    `json:"distanceKm"`
	DistanceFare       Amount    `json:"distanceFare"`
	CarTypeMultiplier  Amount    `json:"carTypeMultiplier"`
	SurgeMultiplier    Amount    `json:"surgeMultiplier"`
	MinimumFare        Amount    `json:"minimumFare"`
	MinimumFareApplied bool      `json:"minimumFareApplied"`
	FareBeforeDiscount Amount    `json:"fareBeforeDiscount"`
	CouponCode         *string   `json:"couponCode"`
	DiscountAmount     Amount    `json:"discountAmount"`
	TotalFare          Amount    `json:"totalFare"`
	Breakdown          Breakdown `json:"breakdown"`
}

// A struct rather than a map, because Jackson writes a LinkedHashMap in insertion order and Go sorts map keys.
type Breakdown struct {
	PricingRuleCode    string     `json:"pricingRuleCode"`
	PricingRuleName    string     `json:"pricingRuleName"`
	PricingZoneCode    *string    `json:"pricingZoneCode"`
	PricingZoneName    *string    `json:"pricingZoneName"`
	DistanceKm         Amount     `json:"distanceKm"`
	Tiers              []TierLine `json:"tiers"`
	DistanceFare       Amount     `json:"distanceFare"`
	CarType            string     `json:"carType"`
	CarTypeMultiplier  Amount     `json:"carTypeMultiplier"`
	SurgeEnabled       bool       `json:"surgeEnabled"`
	SurgeMultiplier    Amount     `json:"surgeMultiplier"`
	MinimumFare        Amount     `json:"minimumFare"`
	MinimumFareApplied bool       `json:"minimumFareApplied"`
	FareBeforeDiscount Amount     `json:"fareBeforeDiscount"`
	CouponCode         *string    `json:"couponCode"`
	DiscountAmount     Amount     `json:"discountAmount"`
	TotalFare          Amount     `json:"totalFare"`
}

// One line of the slab ladder, in the key order the Java breakdown builds it.
type TierLine struct {
	FromKm    Amount  `json:"fromKm"`
	ToKm      *Amount `json:"toKm"`
	RatePerKm Amount  `json:"ratePerKm"`
	KmBilled  Amount  `json:"kmBilled"`
	Amount    Amount  `json:"amount"`
}

// Only the two reads the fare engine needs, so tests can drive it without MySQL.
type configSource interface {
	Raw(key string) (string, error)
	GetBool(key string, fallback bool) bool
}

// The single place a fare is computed; every input comes from pricing_schema or from business configuration.
type Service struct {
	src pricingSource
	cfg configSource
}

func NewService(db *sql.DB, cfg *ConfigReader) *Service {
	return &Service{src: sqlSource{db: db}, cfg: cfg}
}

// couponCode is accepted and ignored: coupons are out of scope for this port, so the discount is always zero.
func (s *Service) Quote(ctx context.Context,
	pickupLatitude, pickupLongitude *decimal.Decimal,
	distanceKm decimal.Decimal,
	requestedCarType string,
	couponCode string,
	userID int64) (FareQuote, error) {

	if distanceKm.Sign() < 0 {
		return FareQuote{}, httpx.ErrValidation("Distance in kilometres must be zero or greater")
	}
	if !IsCarType(requestedCarType) {
		return FareQuote{}, httpx.ErrValidation("A car type is required to quote a fare")
	}

	zone, err := s.resolveZone(ctx, pickupLatitude, pickupLongitude)
	if err != nil {
		return FareQuote{}, err
	}
	var zoneCode, zoneName *string
	if zone != nil {
		zoneCode, zoneName = &zone.Code, &zone.Name
	}

	rule, err := s.ruleFor(ctx, zone)
	if err != nil {
		return FareQuote{}, err
	}

	distanceFare, tierLines := tieredDistanceFare(rule, distanceKm)

	carTypeMultiplier, err := s.carTypeMultiplier(ctx, rule, requestedCarType)
	if err != nil {
		return FareQuote{}, err
	}
	surgeEnabled := s.cfg.GetBool(KeySurgeEnabled, false)
	surgeMultiplier := noAdjustment
	if surgeEnabled {
		surgeMultiplier = rule.SurgeMultiplier
	}

	// Rounded once, after both factors: rounding in between applies the half up step twice to the same amount.
	adjustedFare := Round(distanceFare.Mul(carTypeMultiplier).Mul(surgeMultiplier))

	minimumFare := Round(rule.MinimumFare)
	minimumFareApplied := adjustedFare.Cmp(minimumFare) < 0
	fareBeforeDiscount := adjustedFare
	if minimumFareApplied {
		fareBeforeDiscount = minimumFare
	}

	// No coupon is ever applied here, so the ride snapshot records no code and no discount.
	discountAmount := Zero()
	totalFare := NonNegativeOf(fareBeforeDiscount.Sub(discountAmount))

	quote := FareQuote{
		PricingRuleCode:    rule.Code,
		PricingZoneCode:    zoneCode,
		DistanceKm:         Amt(distanceKm),
		DistanceFare:       Amt(distanceFare),
		CarTypeMultiplier:  Amt(carTypeMultiplier),
		SurgeMultiplier:    Amt(surgeMultiplier),
		MinimumFare:        Amt(minimumFare),
		MinimumFareApplied: minimumFareApplied,
		FareBeforeDiscount: Amt(fareBeforeDiscount),
		CouponCode:         nil,
		DiscountAmount:     Amt(discountAmount),
		TotalFare:          Amt(totalFare),
	}
	quote.Breakdown = Breakdown{
		PricingRuleCode:    rule.Code,
		PricingRuleName:    rule.Name,
		PricingZoneCode:    zoneCode,
		PricingZoneName:    zoneName,
		DistanceKm:         quote.DistanceKm,
		Tiers:              tierLines,
		DistanceFare:       quote.DistanceFare,
		CarType:            requestedCarType,
		CarTypeMultiplier:  quote.CarTypeMultiplier,
		SurgeEnabled:       surgeEnabled,
		SurgeMultiplier:    quote.SurgeMultiplier,
		MinimumFare:        quote.MinimumFare,
		MinimumFareApplied: minimumFareApplied,
		FareBeforeDiscount: quote.FareBeforeDiscount,
		CouponCode:         nil,
		DiscountAmount:     quote.DiscountAmount,
		TotalFare:          quote.TotalFare,
	}
	return quote, nil
}

// The pickup decides the zone, never the drop: the pickup is fixed for the life of the ride.
func (s *Service) resolveZone(ctx context.Context, latitude, longitude *decimal.Decimal) (*Zone, error) {
	if !geo.IsValidLatitude(latitude) || !geo.IsValidLongitude(longitude) {
		return nil, nil
	}
	zones, err := s.src.activeZones(ctx)
	if err != nil {
		return nil, err
	}
	// Ordered by priority then radius, so the first hit is the most specific zone.
	for i := range zones {
		distance := geo.DistanceKm(zones[i].CentreLatitude, zones[i].CentreLongitude, *latitude, *longitude)
		if distance.Cmp(zones[i].RadiusKm) <= 0 {
			return &zones[i], nil
		}
	}
	return nil, nil
}

// A zone's rule wins; a pickup outside every zone, or a zone pointing at a deactivated rule, falls back.
func (s *Service) ruleFor(ctx context.Context, zone *Zone) (Rule, error) {
	if zone != nil {
		zoneRule, err := s.src.ruleByCode(ctx, zone.RuleCode)
		if err != nil {
			return Rule{}, err
		}
		if zoneRule.Active {
			return zoneRule, nil
		}
		slog.Warn("pricing zone points at an inactive rule - falling back to the global default",
			"zone", zone.Code, "rule", zoneRule.Code)
	}
	return s.activeRule(ctx)
}

func (s *Service) activeRule(ctx context.Context) (Rule, error) {
	code, err := s.cfg.Raw(KeyPricingActiveRule)
	if err != nil {
		return Rule{}, err
	}
	rule, err := s.src.ruleByCode(ctx, code)
	if err != nil {
		return Rule{}, err
	}
	if !rule.Active {
		return Rule{}, ruleNotFound("Pricing rule " + rule.Code + " is not active")
	}
	return rule, nil
}

// The factor is looked up against the requested car type, never the assigned one.
func (s *Service) carTypeMultiplier(ctx context.Context, rule Rule, requestedCarType string) (decimal.Decimal, error) {
	multiplier, err := s.src.carTypeMultiplier(ctx, rule.ID, requestedCarType)
	if err != nil {
		return decimal.Decimal{}, err
	}
	if multiplier == nil {
		// A missing row silently prices at 1.00, so a category with no row is charged like a sedan - a real trap.
		return noAdjustment, nil
	}
	return *multiplier, nil
}

// Slab pricing: each tier bills only the part of the trip inside its own half open interval.
func tieredDistanceFare(rule Rule, distanceKm decimal.Decimal) (decimal.Decimal, []TierLine) {
	distanceFare := Zero()
	lines := make([]TierLine, 0, len(rule.Tiers))
	for _, tier := range sortedTiers(rule) {
		upperBound := distanceKm
		if tier.ToKm != nil {
			// min keeps the operand it returns, so a closed tier keeps its own DECIMAL(6,2) scale.
			upperBound = decimal.Min(*tier.ToKm, distanceKm)
		}
		billableKm := upperBound.Sub(tier.FromKm)
		if billableKm.Sign() <= 0 {
			continue
		}
		// Each line is rounded on its own so the breakdown adds up exactly to the distance fare.
		amount := Round(billableKm.Mul(tier.RatePerKm))
		distanceFare = distanceFare.Add(amount)
		lines = append(lines, TierLine{
			FromKm:    Amt(tier.FromKm),
			ToKm:      AmtPtr(tier.ToKm),
			RatePerKm: Amt(tier.RatePerKm),
			KmBilled:  Amt(billableKm),
			Amount:    Amt(amount),
		})
	}
	return distanceFare, lines
}

// Walked in ascending fromKm order, as sortedTiers does, whatever order the rows arrived in.
func sortedTiers(rule Rule) []Tier {
	tiers := append([]Tier(nil), rule.Tiers...)
	sort.SliceStable(tiers, func(i, j int) bool { return tiers[i].FromKm.Cmp(tiers[j].FromKm) < 0 })
	return tiers
}

// Prices a trip nobody has booked; the port of FareEstimateService, minus the coupon retry it needs.
func (s *Service) Estimate(ctx context.Context, request FareEstimateRequest, userID int64) (FareEstimateResponse, error) {
	distanceKm, err := tripDistance(request)
	if err != nil {
		return FareEstimateResponse{}, err
	}
	requestedCoupon := strings.TrimSpace(request.CouponCode)

	quote, err := s.Quote(ctx, request.PickupLatitude, request.PickupLongitude, distanceKm,
		request.CarType, requestedCoupon, userID)
	if err != nil {
		return FareEstimateResponse{}, err
	}

	// A quote never applies a coupon in this port, so the requested code is echoed back, upper cased, as data.
	var reportedCoupon *string
	if requestedCoupon != "" {
		upper := strings.ToUpper(requestedCoupon)
		reportedCoupon = &upper
	}

	return FareEstimateResponse{
		DistanceKm:         quote.DistanceKm,
		PricingRuleCode:    quote.PricingRuleCode,
		PricingZoneCode:    quote.PricingZoneCode,
		DistanceFare:       quote.DistanceFare,
		CarTypeMultiplier:  quote.CarTypeMultiplier,
		SurgeMultiplier:    quote.SurgeMultiplier,
		MinimumFare:        quote.MinimumFare,
		MinimumFareApplied: quote.MinimumFareApplied,
		FareBeforeDiscount: quote.FareBeforeDiscount,
		CouponCode:         reportedCoupon,
		CouponApplicable:   false,
		CouponReason:       nil,
		CouponMessage:      nil,
		DiscountAmount:     quote.DiscountAmount,
		TotalFare:          quote.TotalFare,
	}, nil
}

// The same two checks a booking makes, so an estimate refuses exactly the trips a booking would refuse.
func tripDistance(request FareEstimateRequest) (decimal.Decimal, error) {
	if !geo.IsValidLatitude(request.PickupLatitude) || !geo.IsValidLongitude(request.PickupLongitude) ||
		!geo.IsValidLatitude(request.DropLatitude) || !geo.IsValidLongitude(request.DropLongitude) {
		return decimal.Decimal{}, httpx.ErrInvalidTrip("Pickup or drop coordinates are out of range")
	}
	distanceKm := geo.DistanceKm(*request.PickupLatitude, *request.PickupLongitude,
		*request.DropLatitude, *request.DropLongitude)
	if distanceKm.Sign() <= 0 {
		return decimal.Decimal{}, httpx.ErrInvalidTrip("Pickup and drop locations must be different")
	}
	return distanceKm, nil
}
