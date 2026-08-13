// Package geo is the port of common/geo/GeoUtils: great circle distance and coordinate sanity checks.
package geo

import (
	"math"

	"github.com/shopspring/decimal"
)

// Same radius and scale as GeoUtils - a different distance changes every fare.
const (
	earthRadiusKm = 6371.0088
	DistanceScale = 3
)

// Trip distance in kilometres; the BigDecimal overload of GeoUtils.distanceKm, which also goes through double.
func DistanceKm(fromLat, fromLng, toLat, toLng decimal.Decimal) decimal.Decimal {
	return DistanceKmFloat(fromLat.InexactFloat64(), fromLng.InexactFloat64(),
		toLat.InexactFloat64(), toLng.InexactFloat64())
}

// Haversine, then rounded to 3 dp HALF_UP exactly as GeoUtils does.
func DistanceKmFloat(fromLat, fromLng, toLat, toLng float64) decimal.Decimal {
	dLat := toRadians(toLat - fromLat)
	dLng := toRadians(toLng - fromLng)
	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(toRadians(fromLat))*math.Cos(toRadians(toLat))*
			math.Sin(dLng/2)*math.Sin(dLng/2)
	c := 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
	// NewFromFloat takes the shortest round tripping decimal, which is what BigDecimal.valueOf(double) does.
	return decimal.NewFromFloat(earthRadiusKm * c).Round(DistanceScale)
}

// Java's Math.toRadians divides before multiplying; keeping that order keeps the last bit identical.
func toRadians(degrees float64) float64 {
	return degrees / 180.0 * math.Pi
}

// Absent is invalid, like the null check in GeoUtils.isValidLatitude.
func IsValidLatitude(latitude *decimal.Decimal) bool {
	return latitude != nil && InLatitudeRange(*latitude)
}

func IsValidLongitude(longitude *decimal.Decimal) bool {
	return longitude != nil && InLongitudeRange(*longitude)
}

// Range only, for callers that already hold a present coordinate.
func InLatitudeRange(latitude decimal.Decimal) bool {
	f := latitude.InexactFloat64()
	return f >= -90 && f <= 90
}

func InLongitudeRange(longitude decimal.Decimal) bool {
	f := longitude.InexactFloat64()
	return f >= -180 && f <= 180
}
