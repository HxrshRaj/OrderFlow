#!/usr/bin/env bash
# End-to-end proof that the whole stack does not oversell.
#
# Fires N concurrent "place order" requests for 1 unit of a SKU that has exactly 1 in stock,
# through the running docker-compose stack (Order Service -> Inventory Service -> Postgres),
# and asserts that exactly one order is CONFIRMED and the rest REJECTED.
#
# Usage:  ./scripts/e2e-oversell.sh [GATEWAY_URL] [RACERS]
set -euo pipefail

GATEWAY="${1:-http://localhost:8088}"
RACERS="${2:-15}"
SKU="SKU-LASTUNIT"          # seeded with quantity 1
PRICE=999.00

echo "Resetting ${SKU} to exactly 1 unit..."
current=$(curl -fsS "${GATEWAY}/api/inventory/${SKU}" | grep -o '"availableQuantity":[0-9]*' | grep -o '[0-9]*')
delta=$((1 - current))
if [ "${delta}" -ne 0 ]; then
  curl -fsS -X PATCH "${GATEWAY}/api/inventory/${SKU}" \
    -H 'Content-Type: application/json' -d "{\"quantityDelta\": ${delta}}" >/dev/null
fi
echo "  available = $(curl -fsS "${GATEWAY}/api/inventory/${SKU}" | grep -o '"availableQuantity":[0-9]*')"

echo "Firing ${RACERS} concurrent orders for 1x ${SKU}..."
tmp=$(mktemp -d)
for i in $(seq 1 "${RACERS}"); do
  (
    curl -fsS -X POST "${GATEWAY}/api/orders" \
      -H 'Content-Type: application/json' \
      -d "{\"customerId\":\"race-${i}\",\"lines\":[{\"sku\":\"${SKU}\",\"quantity\":1,\"unitPrice\":${PRICE}}]}" \
      > "${tmp}/resp-${i}.json" 2>/dev/null || echo '{"status":"ERROR"}' > "${tmp}/resp-${i}.json"
  ) &
done
wait

confirmed=$(grep -l '"status":"CONFIRMED"' "${tmp}"/resp-*.json | wc -l | tr -d ' ')
rejected=$(grep -l '"status":"REJECTED"'  "${tmp}"/resp-*.json | wc -l | tr -d ' ')
other=$((RACERS - confirmed - rejected))
final=$(curl -fsS "${GATEWAY}/api/inventory/${SKU}")
rm -rf "${tmp}"

echo
echo "  CONFIRMED : ${confirmed}"
echo "  REJECTED  : ${rejected}"
echo "  other     : ${other}"
echo "  inventory : ${final}"
echo

if [ "${confirmed}" -eq 1 ] && [ "${other}" -eq 0 ]; then
  echo "PASS - exactly one order won the last unit; no oversell."
  exit 0
fi
echo "FAIL - expected exactly 1 CONFIRMED and 0 errors."
exit 1
