package pricing

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/shopspring/decimal"

	"ridehailing/internal/httpx"
)

func d(t *testing.T, s string) decimal.Decimal {
	t.Helper()
	value, err := decimal.NewFromString(s)
	if err != nil {
		t.Fatalf("bad decimal %q: %v", s, err)
	}
	return value
}

func pd(t *testing.T, s string) *decimal.Decimal {
	value := d(t, s)
	return &value
}

type fakeSource struct {
	zones       []Zone
	rules       map[string]Rule
	multipliers map[int64]map[string]decimal.Decimal
}

func (f *fakeSource) activeZones(context.Context) ([]Zone, error) { return f.zones, nil }

func (f *fakeSource) ruleByCode(_ context.Context, code string) (Rule, error) {
	rule, ok := f.rules[code]
	if !ok {
		return Rule{}, ruleNotFound("Pricing rule " + code + " does not exist")
	}
	return rule, nil
}

func (f *fakeSource) carTypeMultiplier(_ context.Context, ruleID int64, carType string) (*decimal.Decimal, error) {
	value, ok := f.multipliers[ruleID][carType]
	if !ok {
		return nil, nil
	}
	return &value, nil
}

type fakeConfig struct {
	activeRule string
	surge      bool
}

func (f fakeConfig) Raw(key string) (string, error) {
	if key == KeyPricingActiveRule {
		return f.activeRule, nil
	}
	return "", errors.New("unexpected configuration key " + key)
}

func (f fakeConfig) GetBool(key string, fallback bool) bool {
	if key == KeySurgeEnabled {
		return f.surge
	}
	return fallback
}

// The seeded STANDARD rule from V11: minimum fare 50, tiers 0-2 @10, 2-5 @8, 5+ @5.
func standardRule(t *testing.T) Rule {
	t.Helper()
	return Rule{
		ID: 1, Code: "STANDARD", Name: "Standard Pricing",
		MinimumFare: d(t, "50.00"), SurgeMultiplier: d(t, "1.00"), Active: true,
		Tiers: []Tier{
			{FromKm: d(t, "0.00"), ToKm: pd(t, "2.00"), RatePerKm: d(t, "10.00")},
			{FromKm: d(t, "2.00"), ToKm: pd(t, "5.00"), RatePerKm: d(t, "8.00")},
			{FromKm: d(t, "5.00"), ToKm: nil, RatePerKm: d(t, "5.00")},
		},
	}
}

func standardService(t *testing.T, surge bool) *Service {
	t.Helper()
	return &Service{
		src: &fakeSource{
			rules: map[string]Rule{"STANDARD": standardRule(t)},
			multipliers: map[int64]map[string]decimal.Decimal{1: {
				CarTypeSedan:     d(t, "1.00"),
				CarTypeHatchback: d(t, "0.90"),
				CarTypeAuto:      d(t, "0.65"),
				CarTypeBike:      d(t, "0.45"),
			}},
		},
		cfg: fakeConfig{activeRule: "STANDARD", surge: surge},
	}
}

func quote(t *testing.T, svc *Service, distanceKm, carType string) FareQuote {
	t.Helper()
	result, err := svc.Quote(context.Background(), nil, nil, d(t, distanceKm), carType, "", 7)
	if err != nil {
		t.Fatalf("Quote: %v", err)
	}
	return result
}

func assertAmount(t *testing.T, label string, got Amount, want string) {
	t.Helper()
	if Plain(got.Decimal) != want {
		t.Fatalf("%s = %s, want %s", label, Plain(got.Decimal), want)
	}
}

// The pinned ground truth: a 28.005 km AUTO trip on the seeded STANDARD rule.
func TestGroundTruthAutoTripOf28Km(t *testing.T) {
	got := quote(t, standardService(t, false), "28.005", CarTypeAuto)

	assertAmount(t, "distanceFare", got.DistanceFare, "159.03")
	assertAmount(t, "carTypeMultiplier", got.CarTypeMultiplier, "0.65")
	assertAmount(t, "totalFare", got.TotalFare, "103.37")
	assertAmount(t, "surgeMultiplier", got.SurgeMultiplier, "1.00")
	assertAmount(t, "minimumFare", got.MinimumFare, "50.00")
	assertAmount(t, "fareBeforeDiscount", got.FareBeforeDiscount, "103.37")
	assertAmount(t, "discountAmount", got.DiscountAmount, "0.00")
	if got.MinimumFareApplied {
		t.Fatal("minimum fare must not apply to a 28 km trip")
	}
	if got.PricingRuleCode != "STANDARD" || got.PricingZoneCode != nil {
		t.Fatalf("rule/zone = %s/%v, want STANDARD/nil", got.PricingRuleCode, got.PricingZoneCode)
	}
}

// The tier walk, line by line: 2 km at 10, 3 km at 8, the remaining 23.005 km at 5.
func TestTierBreakdownLines(t *testing.T) {
	tiers := quote(t, standardService(t, false), "28.005", CarTypeAuto).Breakdown.Tiers
	if len(tiers) != 3 {
		t.Fatalf("tier lines = %d, want 3", len(tiers))
	}
	want := []struct{ fromKm, toKm, ratePerKm, kmBilled, amount string }{
		{"0.00", "2.00", "10.00", "2.00", "20.00"},
		{"2.00", "5.00", "8.00", "3.00", "24.00"},
		{"5.00", "", "5.00", "23.005", "115.03"},
	}
	for i, w := range want {
		assertAmount(t, "fromKm", tiers[i].FromKm, w.fromKm)
		assertAmount(t, "ratePerKm", tiers[i].RatePerKm, w.ratePerKm)
		assertAmount(t, "kmBilled", tiers[i].KmBilled, w.kmBilled)
		assertAmount(t, "amount", tiers[i].Amount, w.amount)
		if w.toKm == "" {
			if tiers[i].ToKm != nil {
				t.Fatalf("tier %d must be open ended, got %s", i, Plain(tiers[i].ToKm.Decimal))
			}
			continue
		}
		if tiers[i].ToKm == nil {
			t.Fatalf("tier %d toKm is absent, want %s", i, w.toKm)
		}
		assertAmount(t, "toKm", *tiers[i].ToKm, w.toKm)
	}
}

func TestTierWalkAcrossDistances(t *testing.T) {
	cases := []struct {
		name                    string
		distanceKm              string
		distanceFare, totalFare string
		minimumFareApplied      bool
	}{
		{"inside the first tier only", "1.200", "12.00", "50.00", true},
		{"first two tiers", "3.500", "32.00", "50.00", true},
		{"just under the minimum fare", "6.000", "49.00", "50.00", true},
		{"exactly on a tier boundary", "5.000", "44.00", "50.00", true},
		{"above the minimum fare", "10.000", "69.00", "69.00", false},
		{"the pinned 28 km trip", "28.005", "159.03", "159.03", false},
		{"a zero distance trip", "0.000", "0.00", "50.00", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := quote(t, standardService(t, false), c.distanceKm, CarTypeSedan)
			assertAmount(t, "distanceFare", got.DistanceFare, c.distanceFare)
			assertAmount(t, "totalFare", got.TotalFare, c.totalFare)
			if got.MinimumFareApplied != c.minimumFareApplied {
				t.Fatalf("minimumFareApplied = %v, want %v", got.MinimumFareApplied, c.minimumFareApplied)
			}
		})
	}
}

func TestCarTypeMultiplierLookup(t *testing.T) {
	cases := []struct {
		name                  string
		carType               string
		multiplier, totalFare string
	}{
		{"sedan is the reference", CarTypeSedan, "1.00", "159.03"},
		{"hatchback", CarTypeHatchback, "0.90", "143.13"},
		{"auto", CarTypeAuto, "0.65", "103.37"},
		{"bike", CarTypeBike, "0.45", "71.56"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := quote(t, standardService(t, false), "28.005", c.carType)
			assertAmount(t, "carTypeMultiplier", got.CarTypeMultiplier, c.multiplier)
			assertAmount(t, "totalFare", got.TotalFare, c.totalFare)
		})
	}
}

// A missing row is priced at 1.00, so a category nobody configured is charged like a sedan.
func TestCarTypeMultiplierMissingRowFallsBackToOne(t *testing.T) {
	svc := &Service{
		src: &fakeSource{
			rules:       map[string]Rule{"STANDARD": standardRule(t)},
			multipliers: map[int64]map[string]decimal.Decimal{1: {CarTypeSedan: d(t, "1.00")}},
		},
		cfg: fakeConfig{activeRule: "STANDARD"},
	}
	got := quote(t, svc, "28.005", CarTypeBike)
	assertAmount(t, "carTypeMultiplier", got.CarTypeMultiplier, "1.00")
	assertAmount(t, "totalFare", got.TotalFare, "159.03")
}

// The factor comes from the requested car type; the assigned vehicle never reaches pricing.
func TestCarTypeMultiplierUsesRequestedType(t *testing.T) {
	got := quote(t, standardService(t, false), "28.005", CarTypeAuto)
	if got.Breakdown.CarType != CarTypeAuto {
		t.Fatalf("breakdown carType = %s, want %s", got.Breakdown.CarType, CarTypeAuto)
	}
	assertAmount(t, "carTypeMultiplier", got.CarTypeMultiplier, "0.65")
}

func TestSurgeIsGatedOnConfiguration(t *testing.T) {
	surging := standardRule(t)
	surging.SurgeMultiplier = d(t, "1.50")

	newService := func(surge bool) *Service {
		return &Service{
			src: &fakeSource{
				rules:       map[string]Rule{"STANDARD": surging},
				multipliers: map[int64]map[string]decimal.Decimal{1: {CarTypeAuto: d(t, "0.65")}},
			},
			cfg: fakeConfig{activeRule: "STANDARD", surge: surge},
		}
	}

	off := quote(t, newService(false), "28.005", CarTypeAuto)
	assertAmount(t, "surgeMultiplier", off.SurgeMultiplier, "1.00")
	assertAmount(t, "totalFare", off.TotalFare, "103.37")
	if off.Breakdown.SurgeEnabled {
		t.Fatal("breakdown must report surge as disabled")
	}

	on := quote(t, newService(true), "28.005", CarTypeAuto)
	assertAmount(t, "surgeMultiplier", on.SurgeMultiplier, "1.50")
	assertAmount(t, "totalFare", on.TotalFare, "155.05")
	if !on.Breakdown.SurgeEnabled {
		t.Fatal("breakdown must report surge as enabled")
	}
}

func zoneSource(t *testing.T, airportRuleActive bool) *fakeSource {
	t.Helper()
	airportRule := standardRule(t)
	airportRule.ID = 2
	airportRule.Code = "AIRPORT_STANDARD"
	airportRule.Name = "Airport Pricing"
	airportRule.MinimumFare = d(t, "120.00")
	airportRule.Active = airportRuleActive

	return &fakeSource{
		// Ordered by priority desc then radius asc, exactly as findActiveWithRules returns them.
		zones: []Zone{
			{Code: "AIRPORT", Name: "Kempegowda Airport", RuleCode: "AIRPORT_STANDARD",
				CentreLatitude: d(t, "13.1986"), CentreLongitude: d(t, "77.7066"),
				RadiusKm: d(t, "5.00"), Priority: 200},
			{Code: "BANGALORE", Name: "Bengaluru", RuleCode: "STANDARD",
				CentreLatitude: d(t, "12.9716"), CentreLongitude: d(t, "77.5946"),
				RadiusKm: d(t, "40.00"), Priority: 100},
		},
		rules: map[string]Rule{"STANDARD": standardRule(t), "AIRPORT_STANDARD": airportRule},
		multipliers: map[int64]map[string]decimal.Decimal{
			1: {CarTypeAuto: d(t, "0.65")},
			2: {CarTypeAuto: d(t, "0.65")},
		},
	}
}

func TestZoneResolution(t *testing.T) {
	cases := []struct {
		name             string
		pickupLat        *decimal.Decimal
		pickupLng        *decimal.Decimal
		wantRule         string
		wantZone         string
		airportRuleState bool
	}{
		{"the nested high priority zone wins", pd(t, "13.1986"), pd(t, "77.7066"), "AIRPORT_STANDARD", "AIRPORT", true},
		{"a city pickup falls to the city zone", pd(t, "12.9716"), pd(t, "77.5946"), "STANDARD", "BANGALORE", true},
		{"a pickup outside every zone uses the configured rule", pd(t, "19.0760"), pd(t, "72.8777"), "STANDARD", "", true},
		{"an absent pickup resolves no zone", nil, nil, "STANDARD", "", true},
		{"an out of range pickup resolves no zone", pd(t, "91.0"), pd(t, "77.5946"), "STANDARD", "", true},
		{"a zone pointing at an inactive rule falls back", pd(t, "13.1986"), pd(t, "77.7066"), "STANDARD", "AIRPORT", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			svc := &Service{src: zoneSource(t, c.airportRuleState), cfg: fakeConfig{activeRule: "STANDARD"}}
			got, err := svc.Quote(context.Background(), c.pickupLat, c.pickupLng, d(t, "28.005"), CarTypeAuto, "", 7)
			if err != nil {
				t.Fatalf("Quote: %v", err)
			}
			if got.PricingRuleCode != c.wantRule {
				t.Fatalf("pricingRuleCode = %s, want %s", got.PricingRuleCode, c.wantRule)
			}
			switch {
			case c.wantZone == "" && got.PricingZoneCode != nil:
				t.Fatalf("pricingZoneCode = %s, want absent", *got.PricingZoneCode)
			case c.wantZone != "" && (got.PricingZoneCode == nil || *got.PricingZoneCode != c.wantZone):
				t.Fatalf("pricingZoneCode = %v, want %s", got.PricingZoneCode, c.wantZone)
			}
		})
	}
}

func TestQuoteRefusals(t *testing.T) {
	cases := []struct {
		name       string
		distanceKm string
		carType    string
		wantCode   string
	}{
		{"a negative distance", "-0.001", CarTypeSedan, "VALIDATION_ERROR"},
		{"an absent car type", "5.000", "", "VALIDATION_ERROR"},
		{"an unknown car type", "5.000", "TRUCK", "VALIDATION_ERROR"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := standardService(t, false).Quote(context.Background(), nil, nil,
				d(t, c.distanceKm), c.carType, "", 7)
			assertCoded(t, err, c.wantCode)
		})
	}
}

func TestActiveRuleMustBeActive(t *testing.T) {
	inactive := standardRule(t)
	inactive.Active = false
	svc := &Service{
		src: &fakeSource{rules: map[string]Rule{"STANDARD": inactive}},
		cfg: fakeConfig{activeRule: "STANDARD"},
	}
	_, err := svc.Quote(context.Background(), nil, nil, d(t, "5.000"), CarTypeSedan, "", 7)
	assertCoded(t, err, "PRICING_RULE_NOT_FOUND")

	missing := &Service{src: &fakeSource{rules: map[string]Rule{}}, cfg: fakeConfig{activeRule: "GONE"}}
	_, err = missing.Quote(context.Background(), nil, nil, d(t, "5.000"), CarTypeSedan, "", 7)
	assertCoded(t, err, "PRICING_RULE_NOT_FOUND")
}

func TestEstimateTripChecks(t *testing.T) {
	svc := standardService(t, false)
	cases := []struct {
		name     string
		request  FareEstimateRequest
		wantCode string
	}{
		{"identical pickup and drop", FareEstimateRequest{
			PickupLatitude: pd(t, "12.9716"), PickupLongitude: pd(t, "77.5946"),
			DropLatitude: pd(t, "12.9716"), DropLongitude: pd(t, "77.5946"),
			CarType: CarTypeAuto}, "INVALID_TRIP"},
		{"an out of range drop", FareEstimateRequest{
			PickupLatitude: pd(t, "12.9716"), PickupLongitude: pd(t, "77.5946"),
			DropLatitude: pd(t, "99.0"), DropLongitude: pd(t, "77.7066"),
			CarType: CarTypeAuto}, "INVALID_TRIP"},
		{"an absent drop", FareEstimateRequest{
			PickupLatitude: pd(t, "12.9716"), PickupLongitude: pd(t, "77.5946"),
			CarType: CarTypeAuto}, "INVALID_TRIP"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := svc.Estimate(context.Background(), c.request, 7)
			assertCoded(t, err, c.wantCode)
		})
	}
}

// The airport trip the ground truth is taken from, end to end through the estimate path.
func TestEstimateAndItsJSONShape(t *testing.T) {
	request := FareEstimateRequest{
		PickupLatitude: pd(t, "12.9716"), PickupLongitude: pd(t, "77.5946"),
		DropLatitude: pd(t, "13.1986"), DropLongitude: pd(t, "77.7066"),
		CarType: CarTypeAuto, CouponCode: " welcome50 ",
	}
	got, err := standardService(t, false).Estimate(context.Background(), request, 7)
	if err != nil {
		t.Fatalf("Estimate: %v", err)
	}
	assertAmount(t, "distanceKm", got.DistanceKm, "28.005")
	assertAmount(t, "distanceFare", got.DistanceFare, "159.03")
	assertAmount(t, "totalFare", got.TotalFare, "103.37")
	// Coupons are out of scope: the code is echoed back upper cased, never applied.
	if got.CouponApplicable || got.CouponCode == nil || *got.CouponCode != "WELCOME50" {
		t.Fatalf("coupon = %v/%v, want WELCOME50 and not applicable", got.CouponCode, got.CouponApplicable)
	}
	assertAmount(t, "discountAmount", got.DiscountAmount, "0.00")

	body, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	want := `{"distanceKm":28.005,"pricingRuleCode":"STANDARD","pricingZoneCode":null,` +
		`"distanceFare":159.03,"carTypeMultiplier":0.65,"surgeMultiplier":1.00,"minimumFare":50.00,` +
		`"minimumFareApplied":false,"fareBeforeDiscount":103.37,"couponCode":"WELCOME50",` +
		`"couponApplicable":false,"couponReason":null,"couponMessage":null,"discountAmount":0.00,` +
		`"totalFare":103.37}`
	if string(body) != want {
		t.Fatalf("response JSON =\n%s\nwant\n%s", body, want)
	}
}

// Scale must survive serialisation: BigDecimal writes 20.00, not 20.
func TestAmountKeepsItsScale(t *testing.T) {
	cases := []struct{ in, want string }{
		{"20.00", "20.00"},
		{"1.00", "1.00"},
		{"0.00", "0.00"},
		{"28.005", "28.005"},
		{"159.03", "159.03"},
	}
	for _, c := range cases {
		body, err := json.Marshal(Amt(d(t, c.in)))
		if err != nil {
			t.Fatalf("marshal %s: %v", c.in, err)
		}
		if string(body) != c.want {
			t.Fatalf("marshalled %s as %s, want %s", c.in, body, c.want)
		}
	}
}

func TestRoundIsHalfUpAtScaleTwo(t *testing.T) {
	cases := []struct{ in, want string }{
		{"115.025", "115.03"},
		{"103.3695", "103.37"},
		{"0.005", "0.01"},
		{"1.004999", "1.00"},
		{"1.005", "1.01"},
		{"-1.005", "-1.01"},
		{"50", "50.00"},
	}
	for _, c := range cases {
		if got := Plain(Round(d(t, c.in))); got != c.want {
			t.Fatalf("Round(%s) = %s, want %s", c.in, got, c.want)
		}
	}
}

// Money.round passes absence through; Money.nonNegative refuses it.
func TestMoneyAbsenceContract(t *testing.T) {
	if RoundOrAbsent(nil) != nil {
		t.Fatal("RoundOrAbsent(nil) must stay absent")
	}
	if got := RoundOrAbsent(pd(t, "1.005")); got == nil || Plain(*got) != "1.01" {
		t.Fatalf("RoundOrAbsent(1.005) = %v, want 1.01", got)
	}
	if _, err := NonNegative(nil); err == nil {
		t.Fatal("NonNegative(nil) must refuse")
	} else {
		assertCoded(t, err, "VALIDATION_ERROR")
	}
	negative, err := NonNegative(pd(t, "-12.50"))
	if err != nil {
		t.Fatalf("NonNegative(-12.50): %v", err)
	}
	if Plain(negative) != "0.00" {
		t.Fatalf("NonNegative(-12.50) = %s, want 0.00", Plain(negative))
	}
	if got := Plain(Zero()); got != "0.00" {
		t.Fatalf("Zero() = %s, want 0.00", got)
	}
}

func TestConfigReaderParsesCachedValues(t *testing.T) {
	reader := &ConfigReader{ttl: time.Minute, cached: map[string]cachedConfig{}}
	reader.store(KeyPricingActiveRule, "STANDARD")
	reader.store(KeySurgeEnabled, "TRUE")
	reader.store("ride.search.radius.km", " 5 ")
	reader.store("ride.cancellation.fee.amount", " 25.50 ")
	reader.store("broken.int", "not-a-number")

	if got := reader.GetString(KeyPricingActiveRule, "OTHER"); got != "STANDARD" {
		t.Fatalf("GetString = %s, want STANDARD", got)
	}
	if !reader.GetBool(KeySurgeEnabled, false) {
		t.Fatal("GetBool must parse TRUE case insensitively")
	}
	if got := reader.GetInt("ride.search.radius.km", 1); got != 5 {
		t.Fatalf("GetInt = %d, want 5", got)
	}
	if got := reader.GetDecimal("ride.cancellation.fee.amount", Zero()); Plain(got) != "25.50" {
		t.Fatalf("GetDecimal = %s, want 25.50", Plain(got))
	}
	if got := reader.GetInt("broken.int", 42); got != 42 {
		t.Fatalf("GetInt on a bad value = %d, want the fallback 42", got)
	}

	reader.Invalidate(KeyPricingActiveRule)
	if _, ok := reader.fromCache(KeyPricingActiveRule); ok {
		t.Fatal("Invalidate must drop the entry")
	}
	reader.cached["expired"] = cachedConfig{value: "stale", expires: time.Now().Add(-time.Second)}
	if _, ok := reader.fromCache("expired"); ok {
		t.Fatal("an expired entry must not be served")
	}
}

func assertCoded(t *testing.T, err error, wantCode string) {
	t.Helper()
	if err == nil {
		t.Fatalf("expected error with code %s, got none", wantCode)
	}
	coded, ok := err.(httpx.Coded)
	if !ok {
		t.Fatalf("error %v is not an httpx.Coded", err)
	}
	if coded.Code != wantCode {
		t.Fatalf("error code = %s, want %s", coded.Code, wantCode)
	}
}
