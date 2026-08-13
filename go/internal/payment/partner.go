package payment

import (
	"context"
	"log/slog"
	"strconv"
	"strings"
	"time"
)

// Canonical name of the demo switch; the same configuration row the Java service reads.
const ConfigSimulatedFailureMethods = "payment.simulated.failure.methods"

// Not an empty string: ConfigurationService rejects a blank STRING, so a cleared row could never be restored.
const simulationNone = "NONE"

// Only the one method the simulation needs, so the pricing module's cached reader satisfies it as is.
type ConfigReader interface {
	String(ctx context.Context, key string, fallback string) string
}

// The partner's answer, kept separate from Result, which is the platform's decision about it.
type Charge struct {
	Authorised    bool
	Reference     string
	DeclineReason string
}

func Authorised(reference string) Charge { return Charge{Authorised: true, Reference: reference} }

func Declined(reason string) Charge { return Charge{DeclineReason: reason} }

// The outbound port a real PSP will implement: adding one is a new struct, with no change above this line.
type Partner interface {
	// Appears in logs so an operator can tell which partner answered.
	Name() string
	// The method is a parameter, not a Request field: the strategy knows the instrument, the caller does not.
	Charge(ctx context.Context, method Method, request Request) Charge
}

// The one place a simulated decline is decided, and configuration rather than random so it is reproducible.
type Simulation struct {
	cfg ConfigReader
}

func NewSimulation(cfg ConfigReader) *Simulation { return &Simulation{cfg: cfg} }

// An unknown name is warned and ignored, never an error: a typo must not take collection down.
func (s *Simulation) ShouldDecline(ctx context.Context, method Method) bool {
	if s == nil || s.cfg == nil || method == "" {
		return false
	}
	configured := s.cfg.String(ctx, ConfigSimulatedFailureMethods, simulationNone)
	if strings.TrimSpace(configured) == "" {
		return false
	}

	for _, token := range strings.Split(configured, ",") {
		candidate := strings.ToUpper(strings.TrimSpace(token))
		if candidate == "" || candidate == simulationNone {
			continue
		}
		if candidate == string(method) {
			return true
		}
		if _, known := ParseMethod(candidate); !known {
			slog.Warn("ignoring unknown payment method in the simulated failure list",
				"method", candidate, "key", ConfigSimulatedFailureMethods)
		}
	}
	return false
}

// The stand-in partner: authorises everything except the methods named in payment.simulated.failure.methods.
type MockPartner struct {
	simulation *Simulation
}

func NewMockPartner(simulation *Simulation) *MockPartner {
	return &MockPartner{simulation: simulation}
}

func (p *MockPartner) Name() string { return "MOCK" }

// No network, no sleep, no randomness, so a charge costs nothing inside the completion transaction.
func (p *MockPartner) Charge(ctx context.Context, method Method, request Request) Charge {
	if p.simulation.ShouldDecline(ctx, method) {
		slog.Info("mock partner declined a charge: the method is in the simulated failure list",
			"method", method, "rideId", request.RideID)
		return Declined("Simulated " + string(method) + " failure at the payment partner")
	}
	return Authorised(reference(method, request.RideID))
}

// Unique in practice, and payments.reference carries a unique key to prove it.
func reference(method Method, rideID int64) string {
	return string(method) + "-" + strconv.FormatInt(rideID, 10) + "-" +
		strconv.FormatInt(time.Now().UnixMilli(), 10)
}
