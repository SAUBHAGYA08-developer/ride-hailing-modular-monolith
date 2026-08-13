package pricing

import (
	"slices"
	"testing"
)

// The order is the business rule: matching walks the list front to back, so HATCHBACK before SEDAN matters.
func TestAcceptableForOrdering(t *testing.T) {
	cases := []struct {
		requested string
		want      []string
	}{
		{CarTypeHatchback, []string{CarTypeHatchback, CarTypeSedan}},
		{CarTypeSedan, []string{CarTypeSedan}},
		{CarTypeAuto, []string{CarTypeAuto}},
		{CarTypeBike, []string{CarTypeBike}},
		{"TRUCK", []string{"TRUCK"}},
	}
	for _, c := range cases {
		t.Run(c.requested, func(t *testing.T) {
			if got := AcceptableFor(c.requested); !slices.Equal(got, c.want) {
				t.Fatalf("AcceptableFor(%s) = %v, want %v", c.requested, got, c.want)
			}
		})
	}
}

// The returned slice is a copy, so a caller reordering it cannot change the policy for everyone else.
func TestAcceptableForIsNotAliased(t *testing.T) {
	first := AcceptableFor(CarTypeHatchback)
	first[0], first[1] = first[1], first[0]
	if got := AcceptableFor(CarTypeHatchback); got[0] != CarTypeHatchback {
		t.Fatalf("policy was mutated by a caller: %v", got)
	}
}

func TestIsUpgrade(t *testing.T) {
	cases := []struct {
		name                string
		requested, assigned string
		want                bool
	}{
		{"hatchback served by a sedan", CarTypeHatchback, CarTypeSedan, true},
		{"auto served by a hatchback", CarTypeAuto, CarTypeHatchback, true},
		{"bike served by a sedan", CarTypeBike, CarTypeSedan, true},
		{"sedan served by a hatchback is a downgrade", CarTypeSedan, CarTypeHatchback, false},
		{"hatchback served by an auto is a downgrade", CarTypeHatchback, CarTypeAuto, false},
		{"sedan served by a bike is a downgrade", CarTypeSedan, CarTypeBike, false},
		{"same category", CarTypeSedan, CarTypeSedan, false},
		{"absent assignment", CarTypeSedan, "", false},
		{"absent request", "", CarTypeSedan, false},
		{"unknown assignment never outranks", CarTypeBike, "TRUCK", false},
		{"an unranked request is outranked by any known type", "TRUCK", CarTypeSedan, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := IsUpgrade(c.requested, c.assigned); got != c.want {
				t.Fatalf("IsUpgrade(%s, %s) = %v, want %v", c.requested, c.assigned, got, c.want)
			}
		})
	}
}

func TestIsCarType(t *testing.T) {
	for _, valid := range []string{CarTypeSedan, CarTypeHatchback, CarTypeAuto, CarTypeBike} {
		if !IsCarType(valid) {
			t.Fatalf("%s must be a car type", valid)
		}
	}
	for _, invalid := range []string{"", "sedan", "TRUCK"} {
		if IsCarType(invalid) {
			t.Fatalf("%q must not be a car type", invalid)
		}
	}
}
