package pricing

import (
	"github.com/shopspring/decimal"

	"ridehailing/internal/httpx"
)

// Every monetary amount is rounded here and nowhere else, at the scale of common/util/Money.
const MoneyScale = 2

// The multiplicative identity, not a price: scaled like the DECIMAL(4,2) multiplier columns.
var noAdjustment = decimal.RequireFromString("1.00")

// The JSON and SQL face of a BigDecimal; it keeps its own scale, so 20.00 never goes out as 20.
type Amount struct {
	decimal.Decimal
}

func Amt(value decimal.Decimal) Amount { return Amount{value} }

func AmtPtr(value *decimal.Decimal) *Amount {
	if value == nil {
		return nil
	}
	a := Amt(*value)
	return &a
}

// Jackson writes BigDecimal as an unquoted number; decimal's own MarshalJSON quotes it and trims zeros.
func (a Amount) MarshalJSON() ([]byte, error) {
	return []byte(Plain(a.Decimal)), nil
}

// BigDecimal.toString for a non negative scale, which decimal.String() is not because it trims zeros.
func Plain(value decimal.Decimal) string {
	if exp := value.Exponent(); exp < 0 {
		return value.StringFixed(-exp)
	}
	return value.String()
}

// Scale 2, HALF_UP - decimal.Round rounds half away from zero, which is HALF_UP.
func Round(value decimal.Decimal) decimal.Decimal {
	return value.Round(MoneyScale)
}

// Money.round: absent in, absent out, because rounding must not invent an amount nobody set.
func RoundOrAbsent(value *decimal.Decimal) *decimal.Decimal {
	if value == nil {
		return nil
	}
	rounded := Round(*value)
	return &rounded
}

func Zero() decimal.Decimal { return decimal.New(0, -MoneyScale) }

// Money.nonNegative: the same rule stated as a refusal, because every caller charges or stores this result.
func NonNegative(value *decimal.Decimal) (decimal.Decimal, error) {
	if value == nil {
		return decimal.Decimal{}, httpx.ErrValidation("A monetary amount is required")
	}
	if value.Sign() < 0 {
		return Zero(), nil
	}
	return Round(*value), nil
}

// The common case: an amount that is present by construction.
func NonNegativeOf(value decimal.Decimal) decimal.Decimal {
	result, _ := NonNegative(&value)
	return result
}
