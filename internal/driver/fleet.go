package driver

import (
	"context"
	"database/sql"
	"log/slog"
	"math"
)

// Reconciles the two independent notions of a live driver: MySQL reservation state and Redis presence.
func (s *Service) FleetSnapshot(ctx context.Context, status string, page, size int) (FleetSnapshotResponse, error) {
	// Redis is read exactly once, so the totals can never contradict the flags beside them.
	livePositions, err := s.LivePositions(ctx)
	if err != nil {
		return FleetSnapshotResponse{}, err
	}

	byStatus, err := s.countByStatus(ctx)
	if err != nil {
		return FleetSnapshotResponse{}, err
	}

	rows, err := s.listDrivers(ctx, status, size, page*size)
	if err != nil {
		return FleetSnapshotResponse{}, err
	}

	// Bookable is a two-store fact: AVAILABLE with an active vehicle in MySQL, and present in Redis.
	bookable, err := s.bookableDriverIDs(ctx, livePositions)
	if err != nil {
		return FleetSnapshotResponse{}, err
	}

	carTypes, err := s.activeCarTypes(ctx, rows)
	if err != nil {
		return FleetSnapshotResponse{}, err
	}

	summary, err := s.fleetSummary(ctx, byStatus, livePositions, int64(len(bookable)))
	if err != nil {
		return FleetSnapshotResponse{}, err
	}

	items := make([]FleetDriverResponse, 0, len(rows))
	for _, row := range rows {
		items = append(items, toFleetRow(row, livePositions, bookable, carTypes))
	}

	// The filtered total comes from the grouped counts, which already answer it exactly.
	totalElements := byStatus.total()
	if index := statusIndex(status); index >= 0 {
		totalElements = byStatus[index]
	}

	return FleetSnapshotResponse{
		Summary: summary,
		Drivers: FleetPageResponse{
			Items:         items,
			Page:          page,
			Size:          size,
			TotalElements: totalElements,
			TotalPages:    totalPages(totalElements, size),
		},
	}, nil
}

func (s *Service) fleetSummary(ctx context.Context, byStatus StatusCounts,
	livePositions map[int64]Point, bookable int64) (FleetSummaryResponse, error) {

	// status is NOT NULL, so the grouped counts already sum to the fleet.
	total := byStatus.total()
	onDuty := total - byStatus[statusIndex(StatusOffline)]

	// Asked against the live ids rather than derived from their size: the GEO set can hold a driver MySQL took OFFLINE.
	onDutyAndLive, err := s.countOnDutyIn(ctx, livePositions)
	if err != nil {
		return FleetSummaryResponse{}, err
	}
	ghosts := onDuty - onDutyAndLive

	ttl := s.LocationTTLSeconds(ctx)
	if ghosts > 0 {
		slog.Info("fleet view: on-duty drivers have no live position",
			"ghosts", ghosts, "onDuty", onDuty, "ttlSeconds", ttl)
	}

	return FleetSummaryResponse{
		TotalDrivers:              total,
		ByStatus:                  byStatus,
		ReportingLocation:         int64(len(livePositions)),
		OnDutyWithoutLiveLocation: ghosts,
		Bookable:                  bookable,
		LocationTTLSeconds:        ttl,
	}, nil
}

// One row per driver on the page, from MySQL only.
type fleetRow struct {
	id             int64
	fullName       string
	status         string
	rating         string
	totalRides     int
	lastLocationAt sql.NullTime
}

// Totals come from a GROUP BY so they hold for page 5 of 40 exactly as for page 0.
func (s *Service) countByStatus(ctx context.Context) (StatusCounts, error) {
	var counts StatusCounts
	rows, err := s.db.QueryContext(ctx,
		"SELECT status, COUNT(id) FROM driver_schema.drivers GROUP BY status")
	if err != nil {
		return counts, err
	}
	defer rows.Close()
	for rows.Next() {
		var status string
		var count int64
		if err := rows.Scan(&status, &count); err != nil {
			return counts, err
		}
		// Statuses with no drivers stay at zero rather than vanishing from the JSON.
		if index := statusIndex(status); index >= 0 {
			counts[index] = count
		}
	}
	return counts, rows.Err()
}

// Ordered by id: an unordered page would show an operator one driver twice and miss another.
func (s *Service) listDrivers(ctx context.Context, status string, limit, offset int) ([]fleetRow, error) {
	query := `SELECT id, full_name, status, rating, total_rides, last_location_at
		FROM driver_schema.drivers`
	args := []any{}
	if status != "" {
		query += " WHERE status = ?"
		args = append(args, status)
	}
	query += " ORDER BY id ASC LIMIT ? OFFSET ?"
	args = append(args, limit, offset)

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	list := make([]fleetRow, 0, limit)
	for rows.Next() {
		var row fleetRow
		if err := rows.Scan(&row.id, &row.fullName, &row.status, &row.rating,
			&row.totalRides, &row.lastLocationAt); err != nil {
			return nil, err
		}
		list = append(list, row)
	}
	return list, rows.Err()
}

// MySQL answers AVAILABLE with an active vehicle; the intersection applies the half only Redis knows.
func (s *Service) bookableDriverIDs(ctx context.Context, livePositions map[int64]Point) (map[int64]bool, error) {
	bookable := map[int64]bool{}
	if len(livePositions) == 0 {
		return bookable, nil
	}
	rows, err := s.db.QueryContext(ctx, `SELECT DISTINCT d.id
		FROM driver_schema.drivers d
		JOIN driver_schema.vehicles v ON v.driver_id = d.id
		WHERE d.status = 'AVAILABLE' AND v.active = 1`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		if _, live := livePositions[id]; live {
			bookable[id] = true
		}
	}
	return bookable, rows.Err()
}

// Sizes the divergence between the stores; skipped entirely for an empty live set rather than sent as an empty IN list.
func (s *Service) countOnDutyIn(ctx context.Context, livePositions map[int64]Point) (int64, error) {
	if len(livePositions) == 0 {
		return 0, nil
	}
	ids := make([]int64, 0, len(livePositions))
	for id := range livePositions {
		ids = append(ids, id)
	}
	query := "SELECT COUNT(id) FROM driver_schema.drivers WHERE id IN (" +
		placeholders(len(ids)) + ") AND status <> 'OFFLINE'"
	var count int64
	if err := s.db.QueryRowContext(ctx, query, int64Args(ids)...).Scan(&count); err != nil {
		return 0, err
	}
	return count, nil
}

// One query for the whole page: joining vehicles into the listing would duplicate or drop drivers.
func (s *Service) activeCarTypes(ctx context.Context, drivers []fleetRow) (map[int64][]string, error) {
	byDriver := map[int64][]string{}
	if len(drivers) == 0 {
		return byDriver, nil
	}
	ids := make([]int64, len(drivers))
	for i, driver := range drivers {
		ids[i] = driver.id
	}
	query := "SELECT driver_id, car_type FROM driver_schema.vehicles WHERE driver_id IN (" +
		placeholders(len(ids)) + ") AND active = 1"
	rows, err := s.db.QueryContext(ctx, query, int64Args(ids)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var driverID int64
		var carType string
		if err := rows.Scan(&driverID, &carType); err != nil {
			return nil, err
		}
		byDriver[driverID] = append(byDriver[driverID], carType)
	}
	return byDriver, rows.Err()
}

// Phone, e-mail and licence number are dropped on purpose: a headcount needs no personal data.
func toFleetRow(row fleetRow, livePositions map[int64]Point,
	bookable map[int64]bool, carTypes map[int64][]string) FleetDriverResponse {

	out := FleetDriverResponse{
		DriverID:       row.id,
		FullName:       row.fullName,
		Status:         row.status,
		ActiveCarTypes: carTypes[row.id],
		Rating:         Decimal(row.rating),
		TotalRides:     row.totalRides,
		Bookable:       bookable[row.id],
	}
	if out.ActiveCarTypes == nil {
		out.ActiveCarTypes = []string{}
	}
	if position, live := livePositions[row.id]; live {
		latitude, longitude := decimal6(position.Latitude), decimal6(position.Longitude)
		out.ReportingLocation = true
		out.LiveLatitude = &latitude
		out.LiveLongitude = &longitude
	}
	if row.lastLocationAt.Valid {
		at := row.lastLocationAt.Time
		out.LastLocationAt = &at
	}
	return out
}

func totalPages(totalElements int64, size int) int {
	if size <= 0 {
		return 1
	}
	return int(math.Ceil(float64(totalElements) / float64(size)))
}
