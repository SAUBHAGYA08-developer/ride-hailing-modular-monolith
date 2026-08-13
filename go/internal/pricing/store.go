package pricing

import (
	"context"
	"database/sql"
	"errors"

	"github.com/shopspring/decimal"

	"ridehailing/internal/httpx"
)

// One complete fare policy; every number the fare engine uses lives here or on its tiers.
type Rule struct {
	ID              int64
	Code            string
	Name            string
	MinimumFare     decimal.Decimal
	SurgeMultiplier decimal.Decimal
	Active          bool
	Tiers           []Tier
}

// One slab of the distance ladder; the interval is half open [FromKm, ToKm).
type Tier struct {
	FromKm    decimal.Decimal
	ToKm      *decimal.Decimal
	RatePerKm decimal.Decimal
}

// A circular area that maps a pickup point to a pricing rule.
type Zone struct {
	Code            string
	Name            string
	RuleCode        string
	CentreLatitude  decimal.Decimal
	CentreLongitude decimal.Decimal
	RadiusKm        decimal.Decimal
	Priority        int
}

// The reads a quote needs; an interface so the arithmetic can be tested without MySQL.
type pricingSource interface {
	activeZones(ctx context.Context) ([]Zone, error)
	ruleByCode(ctx context.Context, code string) (Rule, error)
	carTypeMultiplier(ctx context.Context, ruleID int64, carType string) (*decimal.Decimal, error)
}

type sqlSource struct{ db *sql.DB }

// Candidate zones for a pickup, highest priority first then smallest radius, so the most specific one wins.
func (s sqlSource) activeZones(ctx context.Context) ([]Zone, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT z.code, z.name, r.code, z.centre_latitude, z.centre_longitude, z.radius_km, z.priority
		FROM pricing_schema.pricing_zones z
		JOIN pricing_schema.pricing_rules r ON r.id = z.pricing_rule_id
		WHERE z.active = 1
		ORDER BY z.priority DESC, z.radius_km ASC`)
	if err != nil {
		return nil, dependencyUnavailable()
	}
	defer rows.Close()

	var zones []Zone
	for rows.Next() {
		var z Zone
		if err := rows.Scan(&z.Code, &z.Name, &z.RuleCode, &z.CentreLatitude, &z.CentreLongitude,
			&z.RadiusKm, &z.Priority); err != nil {
			return nil, dependencyUnavailable()
		}
		zones = append(zones, z)
	}
	if rows.Err() != nil {
		return nil, dependencyUnavailable()
	}
	return zones, nil
}

// The whole aggregate in one round trip, like the entity graph on findByCode, with the tiers already ordered.
func (s sqlSource) ruleByCode(ctx context.Context, code string) (Rule, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT r.id, r.code, r.name, r.minimum_fare, r.surge_multiplier, r.active,
		       t.from_km, t.to_km, t.rate_per_km
		FROM pricing_schema.pricing_rules r
		LEFT JOIN pricing_schema.pricing_distance_tiers t ON t.pricing_rule_id = r.id
		WHERE r.code = ?
		ORDER BY t.from_km`, code)
	if err != nil {
		return Rule{}, dependencyUnavailable()
	}
	defer rows.Close()

	var rule Rule
	found := false
	for rows.Next() {
		var fromKm, toKm, ratePerKm decimal.NullDecimal
		if err := rows.Scan(&rule.ID, &rule.Code, &rule.Name, &rule.MinimumFare, &rule.SurgeMultiplier,
			&rule.Active, &fromKm, &toKm, &ratePerKm); err != nil {
			return Rule{}, dependencyUnavailable()
		}
		found = true
		// The left join yields one all null tier row for a rule that has no tiers at all.
		if !fromKm.Valid {
			continue
		}
		tier := Tier{FromKm: fromKm.Decimal, RatePerKm: ratePerKm.Decimal}
		if toKm.Valid {
			// Present means a closed tier; absent marks the open ended top tier.
			upper := toKm.Decimal
			tier.ToKm = &upper
		}
		rule.Tiers = append(rule.Tiers, tier)
	}
	if rows.Err() != nil {
		return Rule{}, dependencyUnavailable()
	}
	if !found {
		return Rule{}, ruleNotFound("Pricing rule " + code + " does not exist")
	}
	return rule, nil
}

// Absent is a real case: PricingService silently prices a missing row at 1.00.
func (s sqlSource) carTypeMultiplier(ctx context.Context, ruleID int64, carType string) (*decimal.Decimal, error) {
	var multiplier decimal.Decimal
	err := s.db.QueryRowContext(ctx, `
		SELECT multiplier FROM pricing_schema.pricing_car_type_multipliers
		WHERE pricing_rule_id = ? AND car_type = ?`, ruleID, carType).Scan(&multiplier)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, dependencyUnavailable()
	}
	return &multiplier, nil
}

// PRICING_RULE_NOT_FOUND is a 404 in ErrorCode, so clients keep branching on the same code and status.
func ruleNotFound(message string) error {
	return httpx.Err("PRICING_RULE_NOT_FOUND", 404, message)
}

// A well formed request that may succeed on a retry is a 503, not an internal error.
func dependencyUnavailable() error {
	return httpx.Err("DEPENDENCY_UNAVAILABLE", 503, "A dependency is temporarily unavailable, please retry")
}
