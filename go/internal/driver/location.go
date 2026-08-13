package driver

import (
	"context"
	"log/slog"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"
	"ridehailing/internal/httpx"
	"ridehailing/internal/store"
)

const (
	defaultLocationTTLSeconds = 60
	configLocationTTLSeconds  = "driver.location.ttl.seconds"
)

// How long a reported position stays trustworthy; published with the fleet counts so they can be read.
func (s *Service) LocationTTLSeconds(ctx context.Context) int {
	return s.cfg.getInt(ctx, configLocationTTLSeconds, defaultLocationTTLSeconds)
}

// Positions live in Redis only: a ping is a multi-times-a-minute event and the drivers row is the reservation row.
func (s *Service) UpdateLocation(ctx context.Context, driverID int64, latitude, longitude float64) error {
	if err := requireValidCoordinates(latitude, longitude); err != nil {
		return err
	}

	// Redis orders a point as (longitude, latitude), the reverse of how coordinates are quoted everywhere else.
	if err := s.rdb.GeoAdd(ctx, store.DriverGeoSet,
		&redis.GeoLocation{Name: member(driverID), Longitude: longitude, Latitude: latitude}).Err(); err != nil {
		return err
	}

	// Redis has no per-member TTL, so freshness is a separate key and its absence is what marks a position stale.
	ttl := time.Duration(s.LocationTTLSeconds(ctx)) * time.Second
	return s.rdb.Set(ctx, store.DriverFreshnessKey(driverID),
		strconv.FormatInt(time.Now().UnixMilli(), 10), ttl).Err()
}

// Proximity candidates, nearest first. Availability is decided against MySQL, never here.
func (s *Service) FindNearby(ctx context.Context, latitude, longitude float64, radiusKm float64, limit int) ([]NearbyDriver, error) {
	if err := requireValidCoordinates(latitude, longitude); err != nil {
		return nil, err
	}
	if radiusKm <= 0 || limit <= 0 {
		return []NearbyDriver{}, nil
	}

	hits, err := s.rdb.GeoSearchLocation(ctx, store.DriverGeoSet, &redis.GeoSearchLocationQuery{
		GeoSearchQuery: redis.GeoSearchQuery{
			Longitude:  longitude,
			Latitude:   latitude,
			Radius:     radiusKm,
			RadiusUnit: "km",
			Sort:       "ASC",
			Count:      limit,
		},
		WithDist: true,
	}).Result()
	if err != nil {
		return nil, err
	}

	nearby := make([]NearbyDriver, 0, len(hits))
	for _, hit := range hits {
		driverID, ok := parseMember(hit.Name)
		if !ok {
			s.evict(ctx, hit.Name)
			continue
		}
		exists, err := s.rdb.Exists(ctx, store.DriverFreshnessKey(driverID)).Result()
		if err != nil {
			return nil, err
		}
		if exists == 0 {
			// The driver stopped reporting, so the position is no longer trustworthy: drop it.
			s.evict(ctx, hit.Name)
			continue
		}
		nearby = append(nearby, NearbyDriver{DriverID: driverID, DistanceKm: hit.Dist})
	}
	return nearby, nil
}

// Every driver reporting a fresh position. Operator view only: it walks the whole GEO set.
func (s *Service) LiveDriverIDs(ctx context.Context) (map[int64]bool, error) {
	ids, err := s.liveIDs(ctx)
	if err != nil {
		return nil, err
	}
	live := make(map[int64]bool, len(ids))
	for _, id := range ids {
		live[id] = true
	}
	return live, nil
}

// Live coordinates for the fresh members, for the operator fleet view only.
func (s *Service) LivePositions(ctx context.Context) (map[int64]Point, error) {
	ids, err := s.liveIDs(ctx)
	if err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return map[int64]Point{}, nil
	}

	members := make([]string, len(ids))
	for i, id := range ids {
		members[i] = member(id)
	}
	points, err := s.rdb.GeoPos(ctx, store.DriverGeoSet, members...).Result()
	if err != nil {
		return nil, err
	}
	if len(points) != len(ids) {
		slog.Warn("GEOPOS returned nothing usable", "members", len(ids), "replies", len(points))
		return map[int64]Point{}, nil
	}

	positions := make(map[int64]Point, len(ids))
	for i, point := range points {
		// A nil means the member vanished between the two calls, which is not an error.
		if point != nil {
			positions[ids[i]] = Point{Latitude: point.Latitude, Longitude: point.Longitude}
		}
	}
	return positions, nil
}

func (s *Service) RemoveLocation(ctx context.Context, driverID int64) error {
	s.evict(ctx, member(driverID))
	return s.rdb.Del(ctx, store.DriverFreshnessKey(driverID)).Err()
}

// The live position, used only to take the MySQL recovery snapshot when a driver changes status.
func (s *Service) CurrentPosition(ctx context.Context, driverID int64) (Point, bool, error) {
	points, err := s.rdb.GeoPos(ctx, store.DriverGeoSet, member(driverID)).Result()
	if err != nil {
		return Point{}, false, err
	}
	if len(points) == 0 || points[0] == nil {
		return Point{}, false, nil
	}
	return Point{Latitude: points[0].Latitude, Longitude: points[0].Longitude}, true, nil
}

// One ZRANGE plus one MGET, so the cost is two round trips whether the fleet is 10 drivers or 10,000.
func (s *Service) liveIDs(ctx context.Context) ([]int64, error) {
	members, err := s.rdb.ZRange(ctx, store.DriverGeoSet, 0, -1).Result()
	if err != nil {
		return nil, err
	}
	if len(members) == 0 {
		return nil, nil
	}

	// Two parallel slices rather than a map: the MGET reply comes back positionally.
	candidates := make([]int64, 0, len(members))
	keys := make([]string, 0, len(members))
	for _, raw := range members {
		driverID, ok := parseMember(raw)
		if !ok {
			// Junk regardless of freshness, so this eviction needs no reply.
			s.evict(ctx, raw)
			continue
		}
		candidates = append(candidates, driverID)
		keys = append(keys, store.DriverFreshnessKey(driverID))
	}
	if len(candidates) == 0 {
		return nil, nil
	}

	freshness, err := s.rdb.MGet(ctx, keys...).Result()
	if err != nil || freshness == nil || len(freshness) != len(candidates) {
		// "Stale" and "could not tell" are indistinguishable here, so nothing is evicted on a bad read.
		slog.Warn("freshness lookup returned nothing usable; reporting an empty live set and evicting nothing",
			"members", len(candidates), "err", err)
		return nil, nil
	}

	live := make([]int64, 0, len(candidates))
	for i, value := range freshness {
		if value == nil {
			// What is left is a position, not a location: drop it so the next reader is not misled either.
			s.evict(ctx, member(candidates[i]))
			continue
		}
		live = append(live, candidates[i])
	}
	return live, nil
}

// Eviction heals the set on the way past, so a failure is logged rather than failing the read.
func (s *Service) evict(ctx context.Context, rawMember string) {
	if err := s.rdb.ZRem(ctx, store.DriverGeoSet, rawMember).Err(); err != nil {
		slog.Warn("could not evict stale member", "member", rawMember, "err", err)
	}
}

func requireValidCoordinates(latitude, longitude float64) error {
	if latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 {
		return httpx.ErrValidation("Latitude must be within [-90, 90] and longitude within [-180, 180]")
	}
	return nil
}

func member(driverID int64) string {
	return strconv.FormatInt(driverID, 10)
}

func parseMember(rawMember string) (int64, bool) {
	driverID, err := strconv.ParseInt(rawMember, 10, 64)
	if err != nil {
		slog.Warn("discarding unrecognised member", "member", rawMember, "key", store.DriverGeoSet)
		return 0, false
	}
	return driverID, true
}
