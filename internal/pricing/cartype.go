package pricing

import "math"

// The vehicle categories the platform sells; plain strings so every slice can use them without a shared enum.
const (
	CarTypeSedan     = "SEDAN"
	CarTypeHatchback = "HATCHBACK"
	CarTypeAuto      = "AUTO"
	CarTypeBike      = "BIKE"
)

// Which vehicle may serve which request; substitution never crosses a category family.
var acceptableCarTypes = map[string][]string{
	CarTypeHatchback: {CarTypeHatchback, CarTypeSedan},
	CarTypeSedan:     {CarTypeSedan},
	CarTypeAuto:      {CarTypeAuto},
	CarTypeBike:      {CarTypeBike},
}

// Ranks upgrades against downgrades only; the fare comes from the pricing rule, not from here.
var carTypeRank = map[string]int{
	CarTypeBike:      0,
	CarTypeAuto:      1,
	CarTypeHatchback: 2,
	CarTypeSedan:     3,
}

// Most preferred first, and the order is the business rule: matching walks it front to back.
func AcceptableFor(requested string) []string {
	if list, ok := acceptableCarTypes[requested]; ok {
		// Copied, so a caller cannot reorder the policy for everyone else.
		return append([]string(nil), list...)
	}
	// An unmapped category safely serves only itself.
	return []string{requested}
}

// Rank based, so a downgrade is never reported as an upgrade on the ride response.
func IsUpgrade(requested, assigned string) bool {
	if requested == "" || assigned == "" || assigned == requested {
		return false
	}
	return rankOf(assigned) > rankOf(requested)
}

func rankOf(carType string) int {
	if rank, ok := carTypeRank[carType]; ok {
		return rank
	}
	return math.MinInt
}

// A car type Jackson would have accepted for the CarType enum.
func IsCarType(value string) bool {
	_, ok := carTypeRank[value]
	return ok
}
