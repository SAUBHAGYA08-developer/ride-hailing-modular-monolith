// Package payment owns payment_schema: the only code allowed to read or write payments.
package payment

import (
	"strings"
	"time"

	"github.com/shopspring/decimal"
)

// Chosen when the driver completes the ride, not at booking: that is when it is a fact.
type Method string

const (
	// The only method collected in person, so the only one that never reaches a partner.
	MethodCash       Method = "CASH"
	MethodUPI        Method = "UPI"
	MethodCard       Method = "CARD"
	MethodWallet     Method = "WALLET"
	MethodNetbanking Method = "NETBANKING"
)

// Enum order of PaymentMethod, and the exhaustiveness list the strategy factory checks against.
var Methods = []Method{MethodCash, MethodUPI, MethodCard, MethodWallet, MethodNetbanking}

// Case and whitespace tolerant, like PaymentMethod.valueOf on a trimmed upper-cased string.
func ParseMethod(raw string) (Method, bool) {
	candidate := Method(strings.ToUpper(strings.TrimSpace(raw)))
	for _, method := range Methods {
		if method == candidate {
			return method, true
		}
	}
	return "", false
}

// A cancellation fee flows through this module too; the purpose is what keeps the two apart.
type Purpose string

const (
	PurposeRideFare        Purpose = "RIDE_FARE"
	PurposeCancellationFee Purpose = "CANCELLATION_FEE"
)

// No PENDING: the partner answers inside the completion transaction, so there is no third state.
type Status string

const (
	StatusSuccess Status = "SUCCESS"
	StatusFailed  Status = "FAILED"
)

// Every field is resolved from the ride row, never from a client body, so a caller cannot redirect a charge.
type Request struct {
	RideID   int64
	UserID   int64
	DriverID *int64
	Amount   decimal.Decimal
	Purpose  Purpose
}

// What callers and clients may see of a payment; the payments row never leaves this module.
type Summary struct {
	ID            int64     `json:"id"`
	RideID        int64     `json:"rideId"`
	Purpose       Purpose   `json:"purpose"`
	Method        Method    `json:"method"`
	Status        Status    `json:"status"`
	Amount        Num       `json:"amount"`
	Reference     *string   `json:"reference,omitempty"`
	FailureReason *string   `json:"failureReason,omitempty"`
	CollectedAt   time.Time `json:"collectedAt"`
}

// A decline is a result, not an error: the ride is over either way and must not be unwound.
type Result struct {
	Status        Status
	Reference     string
	FailureReason string
}

func Succeeded(reference string) Result {
	return Result{Status: StatusSuccess, Reference: reference}
}

func Failed(failureReason string) Result {
	return Result{Status: StatusFailed, FailureReason: failureReason}
}
