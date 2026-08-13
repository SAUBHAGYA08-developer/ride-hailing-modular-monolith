package payment

import (
	"database/sql"

	"github.com/shopspring/decimal"
)

// Every monetary amount is rounded here and nowhere else, as com.ridehailing.common.util.Money does.
const MoneyScale = 2

// Jackson writes a BigDecimal as a JSON number with its scale intact, so this must not be a quoted string.
type Num decimal.Decimal

func (n Num) MarshalJSON() ([]byte, error) {
	return []byte(decimal.Decimal(n).String()), nil
}

func (n Num) Decimal() decimal.Decimal { return decimal.Decimal(n) }

// shopspring rounds half away from zero, which is exactly BigDecimal HALF_UP.
func Round(value decimal.Decimal) decimal.Decimal {
	return value.Round(MoneyScale)
}

func Zero() decimal.Decimal { return decimal.New(0, -MoneyScale) }

// Parsed rather than converted, so the scale MySQL reported survives into the response.
func parseNum(raw sql.NullString) Num {
	if !raw.Valid {
		return Num(decimal.Decimal{})
	}
	value, err := decimal.NewFromString(raw.String)
	if err != nil {
		return Num(decimal.Decimal{})
	}
	return Num(value)
}
