package payment

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"

	"ridehailing/internal/httpx"
)

// Adding a method is a constant plus one strategy here; no service or handler changes.
type Strategy interface {
	Method() Method
	// Never returns an error for a decline, and never writes the payments table - Service owns both.
	Collect(ctx context.Context, request Request) Result
}

// Shared body for every method a partner settles, so adding an instrument is one line rather than a fourth copy.
type partnerBacked struct {
	method  Method
	partner Partner
}

// Spring used a subclass per method; a method needing something different simply gets its own Strategy.
func NewPartnerBacked(method Method, partner Partner) Strategy {
	return partnerBacked{method: method, partner: partner}
}

func (s partnerBacked) Method() Method { return s.method }

func (s partnerBacked) Collect(ctx context.Context, request Request) Result {
	charge := s.partner.Charge(ctx, s.method, request)
	if !charge.Authorised {
		slog.Info("partner declined a payment", "partner", s.partner.Name(), "method", s.method,
			"amount", request.Amount.String(), "rideId", request.RideID, "reason", charge.DeclineReason)
		return Failed(charge.DeclineReason)
	}
	return Succeeded(charge.Reference)
}

// Takes no Partner: cash is already in the driver's hand, so there is nothing to authorise.
type cash struct {
	simulation *Simulation
}

// That asymmetry is why strategy and partner are two abstractions - collapsing them would need a no-op partner here.
func NewCash(simulation *Simulation) Strategy { return cash{simulation: simulation} }

func (s cash) Method() Method { return MethodCash }

// Records a fact rather than requesting one, so it only fails through the demo switch.
func (s cash) Collect(ctx context.Context, request Request) Result {
	if s.simulation.ShouldDecline(ctx, MethodCash) {
		slog.Info("cash collection marked failed by the simulated failure list", "rideId", request.RideID)
		return Failed("Simulated CASH failure: the rider did not pay the driver")
	}
	return Succeeded(reference(MethodCash, request.RideID))
}

// The registry every method must appear in; Go has no component scan, so it is built explicitly.
func DefaultStrategies(partner Partner, simulation *Simulation) []Strategy {
	return []Strategy{
		NewCash(simulation),
		NewPartnerBacked(MethodUPI, partner),
		NewPartnerBacked(MethodCard, partner),
		NewPartnerBacked(MethodWallet, partner),
		NewPartnerBacked(MethodNetbanking, partner),
	}
}

// Maps a method onto its strategy, with no fallback: money has no safe default.
type StrategyFactory struct {
	byMethod map[Method]Strategy
}

// Both checks fail construction on purpose: the method list and the strategies must not drift apart silently.
func NewStrategyFactory(strategies ...Strategy) (*StrategyFactory, error) {
	byMethod := make(map[Method]Strategy, len(Methods))
	for _, strategy := range strategies {
		if clash, taken := byMethod[strategy.Method()]; taken {
			return nil, fmt.Errorf("payment method %s is claimed by both %T and %T",
				strategy.Method(), clash, strategy)
		}
		byMethod[strategy.Method()] = strategy
	}
	for _, method := range Methods {
		if _, present := byMethod[method]; !present {
			return nil, errors.New("no PaymentStrategy implements " + string(method) +
				". Every payment method must be collectable before the application may serve traffic")
		}
	}
	slog.Info("payment strategies registered", "count", len(byMethod))
	return &StrategyFactory{byMethod: byMethod}, nil
}

// The exhaustiveness check above means only an unparsed method can reach the error.
func (f *StrategyFactory) ForMethod(method Method) (Strategy, error) {
	strategy, present := f.byMethod[method]
	if !present {
		return nil, httpx.Err("UNSUPPORTED_PAYMENT_METHOD", http.StatusBadRequest,
			"Payment method "+string(method)+" cannot be collected")
	}
	return strategy, nil
}
