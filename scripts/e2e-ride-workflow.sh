#!/usr/bin/env bash
#
# Executes the ride workflow documented in WORKFLOW.md against a running
# application and fails loudly on the first unexpected result.
#
# Runs in CI and locally:
#   BASE=http://localhost:8081/api/v1 ./scripts/e2e-ride-workflow.sh
#
set -uo pipefail

BASE="${BASE:-http://localhost:8080/api/v1}"
PASS=0
FAIL=0

green() { printf '\033[0;32m%s\033[0m\n' "$1"; }
red()   { printf '\033[0;31m%s\033[0m\n' "$1"; }

# jq is not assumed: python3 exists on GitHub runners and on macOS.
jget() { python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(''); sys.exit()
for key in '$1'.split('.'):
    if d is None: break
    d = d.get(key) if isinstance(d, dict) else None
print('' if d is None else d)
"; }

check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then
        green "  PASS  $1"; PASS=$((PASS + 1))
    else
        red   "  FAIL  $1 — expected '$2', got '$3'"; FAIL=$((FAIL + 1))
    fi
}

step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

login() { # login <email> <password>
    curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"$1\",\"password\":\"$2\"}" | jget data.accessToken
}

# --------------------------------------------------------------------------
step "1. Rider login"
RIDER=$(login rahul@ridehailing.com 'User@123')
[ -n "$RIDER" ] && check "rider token issued" "yes" "yes" || check "rider token issued" "yes" "no"

step "2. Driver login"
DRIVER=$(login raj.kumar@ridehailing.com 'Driver@123')
[ -n "$DRIVER" ] && check "driver token issued" "yes" "yes" || check "driver token issued" "yes" "no"

step "3. Wrong password is rejected"
CODE=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d '{"email":"rahul@ridehailing.com","password":"nope"}' | jget error.code)
check "invalid credentials" "INVALID_CREDENTIALS" "$CODE"

# --------------------------------------------------------------------------
step "4. Driver goes online"
STATUS=$(curl -s -X PUT "$BASE/drivers/1/status" -H "Authorization: Bearer $DRIVER" \
    -H 'Content-Type: application/json' -d '{"status":"AVAILABLE"}' | jget data.status)
check "driver AVAILABLE" "AVAILABLE" "$STATUS"

step "5. Driver publishes a location (Redis GEO)"
HTTP=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/drivers/1/location" \
    -H "Authorization: Bearer $DRIVER" -H 'Content-Type: application/json' \
    -d '{"latitude":12.9750,"longitude":77.5990}')
check "location accepted" "204" "$HTTP"

# A second driver widens the candidate pool so matching has a real choice.
DRIVER2=$(login amit.sharma@ridehailing.com 'Driver@123')
curl -s -o /dev/null -X PUT "$BASE/drivers/2/status" -H "Authorization: Bearer $DRIVER2" \
    -H 'Content-Type: application/json' -d '{"status":"AVAILABLE"}'
curl -s -o /dev/null -X PUT "$BASE/drivers/2/location" -H "Authorization: Bearer $DRIVER2" \
    -H 'Content-Type: application/json' -d '{"latitude":12.9820,"longitude":77.6050}'

# --------------------------------------------------------------------------
step "6. Book a ride"
KEY="e2e-$(date +%s)-$RANDOM"
BODY='{"pickupLatitude":12.9716,"pickupLongitude":77.5946,"pickupAddress":"MG Road",
       "dropLatitude":12.9081,"dropLongitude":77.6476,"dropAddress":"HSR Layout",
       "carType":"HATCHBACK"}'
RIDE_JSON=$(curl -s -X POST "$BASE/rides" -H "Authorization: Bearer $RIDER" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY")

RIDE_ID=$(echo "$RIDE_JSON" | jget data.id)
check "ride requested"     "REQUESTED" "$(echo "$RIDE_JSON" | jget data.status)"
check "no driver yet"      "false"     "$([ -n "$(echo "$RIDE_JSON" | jget data.driverId)" ] && echo true || echo false)"
check "distance computed"  "9.102"     "$(echo "$RIDE_JSON" | jget data.distanceKm)"

step "6a. Matching offered the ride to exactly one driver"
# No driver is claimed at booking time any more - dispatchNext offers the top
# ranked candidate synchronously within the same request, so it is already
# waiting by the time the booking response comes back.
OFFER1=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/drivers/1/offers/pending" -H "Authorization: Bearer $DRIVER")
OFFER2=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/drivers/2/offers/pending" -H "Authorization: Bearer $DRIVER2")
WINNER=""; WTOKEN=""
if [ "$OFFER1" = "200" ]; then WINNER=1; WTOKEN=$DRIVER
elif [ "$OFFER2" = "200" ]; then WINNER=2; WTOKEN=$DRIVER2
fi
check "one driver was offered the ride" "true" "$([ -n "$WINNER" ] && echo true || echo false)"

step "6b. Offered driver accepts"
ACCEPT_JSON=$(curl -s -X POST "$BASE/rides/$RIDE_ID/offer/accept" -H "Authorization: Bearer $WTOKEN")
check "ride assigned"   "DRIVER_ASSIGNED" "$(echo "$ACCEPT_JSON" | jget data.status)"
check "driver attached" "$WINNER"         "$(echo "$ACCEPT_JSON" | jget data.driverId)"

step "7. Fare snapshot"
FARE=$(echo "$RIDE_JSON" | jget data.fare.totalFare)
check "pricing rule"       "STANDARD"  "$(echo "$RIDE_JSON" | jget data.fare.pricingRuleCode)"
check "pricing zone"       "BANGALORE" "$(echo "$RIDE_JSON" | jget data.fare.pricingZoneCode)"
check "distance fare"      "64.51"     "$(echo "$RIDE_JSON" | jget data.fare.distanceFare)"
check "hatchback factor"   "0.9"       "$(echo "$RIDE_JSON" | jget data.fare.carTypeMultiplier)"
check "total fare"         "58.06"     "$FARE"

step "8. Idempotent replay returns the same ride"
REPLAY_ID=$(curl -s -X POST "$BASE/rides" -H "Authorization: Bearer $RIDER" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY" | jget data.id)
check "no duplicate ride" "$RIDE_ID" "$REPLAY_ID"

step "9. Same key, different body is rejected"
REUSE=$(curl -s -X POST "$BASE/rides" -H "Authorization: Bearer $RIDER" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
    -d '{"pickupLatitude":12.9716,"pickupLongitude":77.5946,"dropLatitude":12.90,
         "dropLongitude":77.60,"carType":"SEDAN"}' | jget error.code)
check "key reuse detected" "IDEMPOTENCY_KEY_REUSED" "$REUSE"

# --------------------------------------------------------------------------
step "10. Ownership: rider cannot start the ride"
DENIED=$(curl -s -X POST "$BASE/rides/$RIDE_ID/start" -H "Authorization: Bearer $RIDER" | jget error.code)
check "rider blocked" "ACCESS_DENIED" "$DENIED"

step "11. Assigned driver starts the ride"
# WINNER/WTOKEN were fixed in step 6b to whichever driver accepted the offer.
check "started" "STARTED" "$(curl -s -X POST "$BASE/rides/$RIDE_ID/start" \
    -H "Authorization: Bearer $WTOKEN" | jget data.status)"

step "12. Invalid transition is rejected"
check "cannot start twice" "INVALID_RIDE_STATE_TRANSITION" \
    "$(curl -s -X POST "$BASE/rides/$RIDE_ID/start" -H "Authorization: Bearer $WTOKEN" | jget error.code)"

step "13. Ride in progress is readable by the rider"
check "rider can read" "STARTED" \
    "$(curl -s "$BASE/rides/$RIDE_ID" -H "Authorization: Bearer $RIDER" | jget data.status)"

step "14. Driver completes the ride"
DONE_JSON=$(curl -s -X POST "$BASE/rides/$RIDE_ID/complete" -H "Authorization: Bearer $WTOKEN" \
  -H "Content-Type: application/json" -d '{"paymentMethod":"CASH"}')
check "completed"        "COMPLETED" "$(echo "$DONE_JSON" | jget data.status)"
check "fare unchanged"   "$FARE"     "$(echo "$DONE_JSON" | jget data.fare.totalFare)"

step "15. Driver is released and the counter moved"
DRV=$(curl -s "$BASE/drivers/$WINNER" -H "Authorization: Bearer $WTOKEN")
check "driver AVAILABLE" "AVAILABLE" "$(echo "$DRV" | jget data.status)"
check "totalRides >= 1"  "true" \
    "$([ "$(echo "$DRV" | jget data.totalRides)" -ge 1 ] && echo true || echo false)"

# --------------------------------------------------------------------------
step "16. Audit trail recorded the lifecycle"
ADMIN=$(login admin@ridehailing.com 'Admin@123')
AUDIT=$(curl -s "$BASE/admin/audit-logs?entityType=Ride&entityId=$RIDE_ID&size=20" \
    -H "Authorization: Bearer $ADMIN")
for ACTION in RIDE_CREATED RIDE_STATUS_CHANGED; do
    echo "$AUDIT" | grep -q "$ACTION" \
        && check "audit has $ACTION" "yes" "yes" \
        || check "audit has $ACTION" "yes" "no"
done

step "17. Coupon below its minimum is reported, not thrown"
COUPON=$(curl -s -X POST "$BASE/coupons/WELCOME10/validate" -H "Authorization: Bearer $RIDER" \
    -H 'Content-Type: application/json' -d '{"fareAmount":50.00}')
check "coupon invalid"  "False"                  "$(echo "$COUPON" | jget data.valid)"
check "coupon reason"   "COUPON_NOT_APPLICABLE"  "$(echo "$COUPON" | jget data.reason)"

step "18. Business configuration is DB driven"
check "radius readable" "5" \
    "$(curl -s "$BASE/configurations/ride.search.radius.km" -H "Authorization: Bearer $ADMIN" | jget data.value)"

# --------------------------------------------------------------------------
printf '\n\033[1m%s\033[0m\n' "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
