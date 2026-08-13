package driver

import (
	"strconv"
	"time"
)

// Driver reservation states, in the same order as the Java enum.
const (
	StatusAvailable = "AVAILABLE"
	StatusBusy      = "BUSY"
	StatusOffline   = "OFFLINE"
)

var statusOrder = [3]string{StatusAvailable, StatusBusy, StatusOffline}

// MySQL DECIMAL text, emitted verbatim so 5.00 stays 5.00 as Jackson renders a BigDecimal.
type Decimal string

func (d Decimal) MarshalJSON() ([]byte, error) {
	if d == "" {
		return []byte("null"), nil
	}
	return []byte(d), nil
}

// Six decimal places, the scale the Java fleet row rounds a live position to.
func decimal6(value float64) Decimal {
	return Decimal(strconv.FormatFloat(value, 'f', 6, 64))
}

type DriverResponse struct {
	ID            int64     `json:"id"`
	UserID        int64     `json:"userId"`
	FullName      string    `json:"fullName"`
	Phone         string    `json:"phone"`
	LicenseNumber string    `json:"licenseNumber"`
	Status        string    `json:"status"`
	Rating        Decimal   `json:"rating"`
	TotalRides    int       `json:"totalRides"`
	CreatedAt     time.Time `json:"createdAt"`
}

type UpdateStatusRequest struct {
	Status *string `json:"status"`
}

type UpdateLocationRequest struct {
	Latitude  *float64 `json:"latitude"`
	Longitude *float64 `json:"longitude"`
}

// A driver seen in the GEO set; a hit proves proximity only, never availability.
type NearbyDriver struct {
	DriverID   int64
	DistanceKm float64
}

// Redis orders a point as (longitude, latitude); this type names the axes instead.
type Point struct {
	Latitude  float64
	Longitude float64
}

// A bookable driver with the vehicle that would serve the ride; version is the row version to reserve against.
type AvailableDriver struct {
	DriverID  int64
	VehicleID int64
	CarType   string
	Rating    float64
	Version   int64
}

type FleetSnapshotResponse struct {
	Summary FleetSummaryResponse `json:"summary"`
	Drivers FleetPageResponse    `json:"drivers"`
}

type FleetSummaryResponse struct {
	TotalDrivers              int64        `json:"totalDrivers"`
	ByStatus                  StatusCounts `json:"byStatus"`
	ReportingLocation         int64        `json:"reportingLocation"`
	OnDutyWithoutLiveLocation int64        `json:"onDutyWithoutLiveLocation"`
	Bookable                  int64        `json:"bookable"`
	LocationTTLSeconds        int          `json:"locationTtlSeconds"`
}

// Indexed by statusOrder so every status is rendered, at zero if need be, in enum order.
type StatusCounts [3]int64

func (c StatusCounts) MarshalJSON() ([]byte, error) {
	out := make([]byte, 0, 48)
	out = append(out, '{')
	for i, status := range statusOrder {
		if i > 0 {
			out = append(out, ',')
		}
		out = append(out, '"')
		out = append(out, status...)
		out = append(out, '"', ':')
		out = strconv.AppendInt(out, c[i], 10)
	}
	return append(out, '}'), nil
}

func (c StatusCounts) total() int64 {
	return c[0] + c[1] + c[2]
}

type FleetPageResponse struct {
	Items         []FleetDriverResponse `json:"items"`
	Page          int                   `json:"page"`
	Size          int                   `json:"size"`
	TotalElements int64                 `json:"totalElements"`
	TotalPages    int                   `json:"totalPages"`
}

// No phone, e-mail or licence number: a live-driver headcount needs no personal data.
type FleetDriverResponse struct {
	DriverID          int64      `json:"driverId"`
	FullName          string     `json:"fullName"`
	Status            string     `json:"status"`
	ActiveCarTypes    []string   `json:"activeCarTypes"`
	Rating            Decimal    `json:"rating"`
	TotalRides        int        `json:"totalRides"`
	ReportingLocation bool       `json:"reportingLocation"`
	Bookable          bool       `json:"bookable"`
	LiveLatitude      *Decimal   `json:"liveLatitude,omitempty"`
	LiveLongitude     *Decimal   `json:"liveLongitude,omitempty"`
	LastLocationAt    *time.Time `json:"lastLocationAt,omitempty"`
}

func isKnownStatus(status string) bool {
	return status == StatusAvailable || status == StatusBusy || status == StatusOffline
}

func statusIndex(status string) int {
	for i, known := range statusOrder {
		if known == status {
			return i
		}
	}
	return -1
}
