package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/shopspring/decimal"

	"ridehailing/internal/auth"
	"ridehailing/internal/config"
	"ridehailing/internal/driver"
	"ridehailing/internal/httpx"
	"ridehailing/internal/payment"
	"ridehailing/internal/pricing"
	"ridehailing/internal/rbac"
	"ridehailing/internal/ride"
	"ridehailing/internal/store"
	"ridehailing/internal/user"
)

func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))
	cfg := config.Load()

	db, err := store.OpenMySQL(cfg.DSN, cfg.DBPoolSize)
	if err != nil {
		slog.Error("cannot open MySQL", "err", err)
		os.Exit(1)
	}
	defer db.Close()

	rdb, err := store.OpenRedis(cfg.RedisAddr, cfg.RedisUser, cfg.RedisPass, cfg.RedisTLS)
	if err != nil {
		slog.Error("cannot open Redis", "err", err)
		os.Exit(1)
	}
	defer rdb.Close()

	jwtService, err := auth.NewService(cfg.JWTSecret, cfg.JWTIssuer, cfg.JWTExpiry)
	if err != nil {
		slog.Error("cannot start JWT service", "err", err)
		os.Exit(1)
	}

	// One reader for the whole process, so a config row is cached once rather than per module.
	configReader := pricing.NewConfigReader(db)
	cfgPort := configAdapter{configReader}

	permissions := rbac.NewStore(db)
	userSvc := user.NewService(db, permissions, jwtService)
	driverSvc := driver.NewService(db, rdb)
	pricingSvc := pricing.NewService(db, configReader)

	paymentFactory, err := payment.NewStrategyFactory(paymentStrategies(cfgPort)...)
	if err != nil {
		// Exhaustiveness is checked here rather than at the first charge: a method with no strategy must not reach traffic.
		slog.Error("payment strategies are incomplete", "err", err)
		os.Exit(1)
	}
	audit := ride.NewAudit(db)
	paymentSvc := payment.NewService(db, paymentFactory, audit)

	fleet := driverAdapter{driverSvc}
	booking := ride.NewBookingService(db, fleet, fleet, pricerAdapter{pricingSvc}, cfgPort,
		paymentSvc, ride.NewRedisBookingLock(rdb), audit, 0)
	lifecycle := ride.NewLifecycleService(db, fleet, cfgPort, paymentSvc, audit)
	query := ride.NewQueryService(db, cfgPort, paymentSvc)

	limiter := httpx.NewLimiter(rdb, intConfigAdapter{configReader})

	mux := http.NewServeMux()
	user.NewHandler(userSvc).Routes(mux)
	driver.NewHandler(driverSvc).Routes(mux)
	pricing.NewHandler(pricingSvc).Routes(mux)
	ride.NewHandler(booking, lifecycle, query).Routes(mux)
	payment.NewHandler(paymentSvc, query).Routes(mux)

	mux.HandleFunc("GET /actuator/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"status": "UP"})
	})
	// The same pages the Java service serves, so a browser cannot tell the two apart.
	mux.Handle("GET /app/", http.StripPrefix("/app/", http.FileServer(http.Dir("web"))))

	handler := httpx.Recover(httpx.AccessLog(httpx.WithRequestID(httpx.WithOptionalAuth(jwtService)(mux))))

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}

	go func() {
		slog.Info("listening", "port", cfg.Port)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server stopped", "err", err)
			os.Exit(1)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_ = server.Shutdown(shutdownCtx)
	slog.Info("stopped")

	// Referenced so the limiter stays wired even while individual routes own their own policies.
	_ = limiter
}

func paymentStrategies(cfg payment.ConfigReader) []payment.Strategy {
	simulation := payment.NewSimulation(cfg)
	partner := payment.NewMockPartner(simulation)
	return []payment.Strategy{
		// Cash takes no partner at all: it is collected in person, never through a gateway.
		payment.NewCash(simulation),
		payment.NewPartnerBacked(payment.MethodUPI, partner),
		payment.NewPartnerBacked(payment.MethodCard, partner),
		payment.NewPartnerBacked(payment.MethodWallet, partner),
		payment.NewPartnerBacked(payment.MethodNetbanking, partner),
	}
}

// The pricing reader is keyed without a context; the ride and payment ports pass one, so it is dropped here.
type configAdapter struct{ reader *pricing.ConfigReader }

func (c configAdapter) String(_ context.Context, key, fallback string) string {
	return c.reader.GetString(key, fallback)
}

func (c configAdapter) Int(_ context.Context, key string, fallback int) int {
	return c.reader.GetInt(key, fallback)
}

func (c configAdapter) Decimal(_ context.Context, key string, fallback decimal.Decimal) decimal.Decimal {
	return c.reader.GetDecimal(key, fallback)
}

type intConfigAdapter struct{ reader *pricing.ConfigReader }

func (c intConfigAdapter) GetInt(key string, fallback int) int { return c.reader.GetInt(key, fallback) }

// Each module declares its own candidate types so neither imports the other; this translates between them.
type driverAdapter struct{ svc *driver.Service }

func (d driverAdapter) FindNearby(ctx context.Context, lat, lng, radiusKm float64, limit int) ([]ride.NearbyDriver, error) {
	found, err := d.svc.FindNearby(ctx, lat, lng, radiusKm, limit)
	if err != nil {
		return nil, err
	}
	out := make([]ride.NearbyDriver, 0, len(found))
	for _, n := range found {
		out = append(out, ride.NearbyDriver{DriverID: n.DriverID, DistanceKm: n.DistanceKm})
	}
	return out, nil
}

func (d driverAdapter) FindAvailableCandidates(ctx context.Context, driverIDs []int64, carTypes []string) ([]ride.AvailableDriver, error) {
	found, err := d.svc.FindAvailableCandidates(ctx, driverIDs, carTypes)
	if err != nil {
		return nil, err
	}
	out := make([]ride.AvailableDriver, 0, len(found))
	for _, a := range found {
		out = append(out, ride.AvailableDriver{DriverID: a.DriverID, VehicleID: a.VehicleID,
			CarType: a.CarType, Rating: a.Rating, Version: a.Version})
	}
	return out, nil
}

func (d driverAdapter) Reserve(ctx context.Context, tx *sql.Tx, driverID, expectedVersion int64, actor string) (bool, error) {
	return d.svc.Reserve(ctx, tx, driverID, expectedVersion, actor)
}

func (d driverAdapter) Release(ctx context.Context, tx *sql.Tx, driverID int64, actor string) (bool, error) {
	return d.svc.Release(ctx, tx, driverID, actor)
}

func (d driverAdapter) CompleteRide(ctx context.Context, tx *sql.Tx, driverID int64, actor string) (bool, error) {
	return d.svc.CompleteRide(ctx, tx, driverID, actor)
}

// Ride owns a quote shape of its own so it never imports pricing's wire types; this is the only seam between them.
type pricerAdapter struct{ svc *pricing.Service }

func (p pricerAdapter) AcceptableFor(requested string) []string {
	return pricing.AcceptableFor(requested)
}

func (p pricerAdapter) Quote(ctx context.Context, pickupLat, pickupLng, distanceKm decimal.Decimal,
	carType, couponCode string, userID int64) (ride.FareQuote, error) {

	quote, err := p.svc.Quote(ctx, &pickupLat, &pickupLng, distanceKm, carType, couponCode, userID)
	if err != nil {
		return ride.FareQuote{}, err
	}

	breakdown := map[string]any{}
	if raw, err := json.Marshal(quote.Breakdown); err == nil {
		_ = json.Unmarshal(raw, &breakdown)
	}
	zone := ""
	if quote.PricingZoneCode != nil {
		zone = *quote.PricingZoneCode
	}
	coupon := ""
	if quote.CouponCode != nil {
		coupon = *quote.CouponCode
	}
	return ride.FareQuote{
		PricingRuleCode:    quote.PricingRuleCode,
		PricingZoneCode:    zone,
		DistanceKm:         quote.DistanceKm.Decimal,
		DistanceFare:       quote.DistanceFare.Decimal,
		CarTypeMultiplier:  quote.CarTypeMultiplier.Decimal,
		SurgeMultiplier:    quote.SurgeMultiplier.Decimal,
		MinimumFare:        quote.MinimumFare.Decimal,
		MinimumFareApplied: quote.MinimumFareApplied,
		FareBeforeDiscount: quote.FareBeforeDiscount.Decimal,
		CouponCode:         coupon,
		DiscountAmount:     quote.DiscountAmount.Decimal,
		TotalFare:          quote.TotalFare.Decimal,
		Breakdown:          breakdown,
	}, nil
}
