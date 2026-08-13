package geo

import (
	"testing"

	"github.com/shopspring/decimal"
)

func dec(t *testing.T, s string) decimal.Decimal {
	t.Helper()
	d, err := decimal.NewFromString(s)
	if err != nil {
		t.Fatalf("bad decimal %q: %v", s, err)
	}
	return d
}

// Distances computed independently from the Java formula (R=6371.0088, scale 3 HALF_UP).
func TestDistanceKm(t *testing.T) {
	cases := []struct {
		name                               string
		fromLat, fromLng, toLat, toLng, km string
	}{
		{"bengaluru centre to koramangala", "12.9716", "77.5946", "12.9352", "77.6245", "5.185"},
		{"bengaluru centre to airport", "12.9716", "77.5946", "13.1986", "77.7066", "28.005"},
		{"same point is zero at scale 3", "12.9716", "77.5946", "12.9716", "77.5946", "0.000"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := DistanceKm(dec(t, c.fromLat), dec(t, c.fromLng), dec(t, c.toLat), dec(t, c.toLng))
			// StringFixed, not String: String trims trailing zeros and BigDecimal.toString does not.
			if got.StringFixed(DistanceScale) != c.km {
				t.Fatalf("distance = %s, want %s", got.StringFixed(DistanceScale), c.km)
			}
			if got.Exponent() != -DistanceScale {
				t.Fatalf("distance scale = %d, want %d", -got.Exponent(), DistanceScale)
			}
			back := DistanceKm(dec(t, c.toLat), dec(t, c.toLng), dec(t, c.fromLat), dec(t, c.fromLng))
			if !back.Equal(got) {
				t.Fatalf("distance is not symmetric: %s vs %s", back, got)
			}
		})
	}
}

func TestValidLatitude(t *testing.T) {
	cases := []struct {
		name  string
		value *decimal.Decimal
		want  bool
	}{
		{"absent", nil, false},
		{"bengaluru", ptr(dec(t, "12.9716")), true},
		{"south pole", ptr(dec(t, "-90")), true},
		{"north pole", ptr(dec(t, "90")), true},
		{"beyond north pole", ptr(dec(t, "90.0000001")), false},
		{"beyond south pole", ptr(dec(t, "-90.0000001")), false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := IsValidLatitude(c.value); got != c.want {
				t.Fatalf("IsValidLatitude = %v, want %v", got, c.want)
			}
		})
	}
}

func TestValidLongitude(t *testing.T) {
	cases := []struct {
		name  string
		value *decimal.Decimal
		want  bool
	}{
		{"absent", nil, false},
		{"bengaluru", ptr(dec(t, "77.5946")), true},
		{"antimeridian west", ptr(dec(t, "-180")), true},
		{"antimeridian east", ptr(dec(t, "180")), true},
		{"beyond antimeridian", ptr(dec(t, "180.0000001")), false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := IsValidLongitude(c.value); got != c.want {
				t.Fatalf("IsValidLongitude = %v, want %v", got, c.want)
			}
		})
	}
}

func ptr(d decimal.Decimal) *decimal.Decimal { return &d }
