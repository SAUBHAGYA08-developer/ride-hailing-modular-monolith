package ride

import "sort"

// A driver Redis reported nearby and MySQL confirmed is bookable.
type Candidate struct {
	DriverID  int64
	VehicleID int64
	CarType   CarType
	Rating    float64
	// Straight-line km from the GEO search, carried so the winner's approach can be snapshotted.
	DistanceKm float64
	// Travels with the candidate so the reservation is a conditional UPDATE, not a read-then-write race.
	Version int64
}

// Shortest pickup leg wins; an equal distance is settled by the better rated driver.
func rankNearest(candidates []Candidate) []Candidate {
	ranked := make([]Candidate, len(candidates))
	copy(ranked, candidates)
	sort.SliceStable(ranked, func(i, j int) bool {
		if ranked[i].DistanceKm != ranked[j].DistanceKm {
			return ranked[i].DistanceKm < ranked[j].DistanceKm
		}
		return ranked[i].Rating > ranked[j].Rating
	})
	return ranked
}

// The requested car type always outranks an upgrade: a hatchback rider gets a sedan only when no hatchback is bookable.
func rankWithCarTypePreference(candidates []Candidate, preferenceOrder []CarType) []Candidate {
	ranked := rankNearest(candidates)
	// Stable, so the nearest-first order survives inside each car type group, exactly as Java's List.sort does.
	sort.SliceStable(ranked, func(i, j int) bool {
		return preferenceIndex(ranked[i].CarType, preferenceOrder) <
			preferenceIndex(ranked[j].CarType, preferenceOrder)
	})
	return ranked
}

// An unlisted type sorts last rather than being dropped, as indexOf returning -1 mapped to MAX_VALUE did.
func preferenceIndex(carType CarType, preferenceOrder []CarType) int {
	for index, preferred := range preferenceOrder {
		if preferred == carType {
			return index
		}
	}
	return len(preferenceOrder) + 1
}
