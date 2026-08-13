package driver

import (
	"context"
	"database/sql"
)

// Resolves Redis proximity hits against the authoritative state, carrying the vehicle and the version to reserve against.
func (s *Service) FindAvailableCandidates(ctx context.Context, driverIDs []int64, carTypes []string) ([]AvailableDriver, error) {
	if len(driverIDs) == 0 || len(carTypes) == 0 {
		return []AvailableDriver{}, nil
	}

	query := `SELECT d.id, v.id, v.car_type, d.rating, d.version
		FROM driver_schema.drivers d
		JOIN driver_schema.vehicles v ON v.driver_id = d.id
		WHERE d.id IN (` + placeholders(len(driverIDs)) + `)
		  AND d.status = 'AVAILABLE'
		  AND v.active = 1
		  AND v.car_type IN (` + placeholders(len(carTypes)) + `)`
	args := append(int64Args(driverIDs), stringArgs(carTypes)...)

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	candidates := make([]AvailableDriver, 0, len(driverIDs))
	for rows.Next() {
		var candidate AvailableDriver
		if err := rows.Scan(&candidate.DriverID, &candidate.VehicleID, &candidate.CarType,
			&candidate.Rating, &candidate.Version); err != nil {
			return nil, err
		}
		candidates = append(candidates, candidate)
	}
	return candidates, rows.Err()
}

// Claims a driver: the status and version predicates make this one atomic compare-and-set, never a read-then-write.
func (s *Service) Reserve(ctx context.Context, tx *sql.Tx, driverID, expectedVersion int64, actor string) (bool, error) {
	return claimed(tx.ExecContext(ctx,
		`UPDATE driver_schema.drivers SET status='BUSY', version = version + 1,
			updated_at = CURRENT_TIMESTAMP(6), updated_by = ?
		WHERE id = ? AND status = 'AVAILABLE' AND version = ?`,
		actor, driverID, expectedVersion))
}

// Hands a driver back. No version predicate: the BUSY predicate alone keeps a release idempotent.
func (s *Service) Release(ctx context.Context, tx *sql.Tx, driverID int64, actor string) (bool, error) {
	return claimed(tx.ExecContext(ctx,
		`UPDATE driver_schema.drivers SET status='AVAILABLE', version = version + 1,
			updated_at = CURRENT_TIMESTAMP(6), updated_by = ?
		WHERE id = ? AND status = 'BUSY'`,
		actor, driverID))
}

// Release plus the ride counter, so a replayed completion cannot double count.
func (s *Service) CompleteRide(ctx context.Context, tx *sql.Tx, driverID int64, actor string) (bool, error) {
	return claimed(tx.ExecContext(ctx,
		`UPDATE driver_schema.drivers SET status='AVAILABLE', total_rides = total_rides + 1,
			version = version + 1, updated_at = CURRENT_TIMESTAMP(6), updated_by = ?
		WHERE id = ? AND status = 'BUSY'`,
		actor, driverID))
}

// Exactly one of N concurrent claims affects a row; everyone else must fall through to the next candidate.
func claimed(result sql.Result, err error) (bool, error) {
	if err != nil {
		return false, err
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return false, err
	}
	return affected == 1, nil
}
