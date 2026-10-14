#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/demo-lib.sh"

MAILPIT=${MAILPIT:-http://localhost:8025}
RUN="$(date +%s)-$$"

await_api() {
  local path=$1 token=$2 deadline=$((SECONDS + 60))
  shift 2
  while ((SECONDS < deadline)); do
    BODY=$(api 200 GET "$path" "$token" "")
    if check "$BODY" "$@" 2>/dev/null; then return; fi
  done
  check "$BODY" "$@" || fail "$path did not reach the expected state"
}

step "Tokens for ravi, admin, and the warehouse"
CUSTOMER=$(token ravi ravi-local-only)
ADMIN=$(token admin admin-local-only)
WAREHOUSE=$(token meera meera-local-only)

step "Create a product and consume its received-stock event"
BODY="{\"name\":\"Events $RUN\",\"slug\":\"events-$RUN\"}"
CATEGORY=$(api 201 POST /v1/admin/catalog/categories "$ADMIN" "$BODY" | json id)
BODY="{\"title\":\"Event demo $RUN\",\"category_id\":\"$CATEGORY\",\"gst_category\":\"STANDARD\",\"options\":[]}"
PRODUCT=$(api 201 POST /v1/admin/catalog/products "$ADMIN" "$BODY" | json id)
SKU="EVENT-$RUN"
BODY="{\"sku\":\"$SKU\",\"option_values\":{},\"price_paise\":59900}"
api 201 POST "/v1/admin/catalog/products/$PRODUCT/variants" "$ADMIN" "$BODY" >/dev/null
api 200 POST "/v1/admin/catalog/products/$PRODUCT/activate" "$ADMIN" "" >/dev/null
api 200 POST "/v1/warehouse/stock/$SKU/receipts" "$WAREHOUSE" '{"quantity":2}' \
  "Idempotency-Key: events-receipt-$RUN" >/dev/null
await_api "/v1/products/$PRODUCT" "" "variants.$SKU.availability_hint=IN_STOCK"
echo "Catalog hint: IN_STOCK"

step "Prepare a customer cart and an existing or new Karnataka address"
SKUS=$(api 200 GET /v1/me/cart "$CUSTOMER" "" | json lines | python3 -c \
  'import json,sys; print(" ".join(line["sku"] for line in json.load(sys.stdin)))')
for OLD_SKU in $SKUS; do
  api 200 DELETE "/v1/me/cart/lines/$OLD_SKU" "$CUSTOMER" "" >/dev/null
done
api 200 DELETE /v1/me/cart/coupon "$CUSTOMER" "" >/dev/null
api 200 PUT "/v1/me/cart/lines/$SKU" "$CUSTOMER" '{"quantity":2}' >/dev/null
QUOTE=$(api 201 POST /v1/me/cart/quotes "$CUSTOMER" '{"delivery_state_code":"29"}' | json id)
ADDRESS=$(api 200 GET /v1/me/addresses "$CUSTOMER" "" | json items | python3 -c \
  'import json,sys; print(next((item["id"] for item in json.load(sys.stdin) if item["state_code"] == "29" and item["pin_code"] == "560038"), ""))')
if [[ -z $ADDRESS ]]; then
  ADDRESS=$(api 201 POST /v1/me/addresses "$CUSTOMER" \
    '{"recipient_name":"Ravi","phone":"9876543210","line1":"12 Demo Street","city":"Bengaluru","state_code":"29","pin_code":"560038"}' | json id)
fi

step "Place and replay one order; Kafka updates the availability hint"
PLACE_BODY="{\"quote_id\":\"$QUOTE\",\"delivery_address_id\":\"$ADDRESS\"}"
ORDER=$(api 202 POST /v1/me/orders "$CUSTOMER" "$PLACE_BODY" "Idempotency-Key: events-place-$RUN" | json id)
REPLAY=$(api 202 POST /v1/me/orders "$CUSTOMER" "$PLACE_BODY" "Idempotency-Key: events-place-$RUN" | json id)
[[ $ORDER == "$REPLAY" ]] || fail "idempotent replay returned another order"
await_api "/v1/me/orders/$ORDER" "$CUSTOMER" status=AWAITING_PAYMENT
await_api "/v1/products/$PRODUCT" "" "variants.$SKU.availability_hint=SOLD_OUT"
echo "Order $ORDER awaiting payment; catalog hint: SOLD_OUT"

step "Cancel the order; the stock event restores the hint"
api 202 POST "/v1/me/orders/$ORDER/cancel" "$CUSTOMER" "" "Idempotency-Key: events-cancel-$RUN" >/dev/null
await_api "/v1/me/orders/$ORDER" "$CUSTOMER" status=CANCELLED
await_api "/v1/products/$PRODUCT" "" "variants.$SKU.availability_hint=IN_STOCK"
echo "Order CANCELLED; catalog hint: IN_STOCK"

step "Verify the two captured emails without sending anything outside Mailpit"
DEADLINE=$((SECONDS + 60))
MAIL_OK=false
while ((SECONDS < DEADLINE)); do
  MAIL=$(curl --max-time 5 -fsS --get --data-urlencode "query=$ORDER" "$MAILPIT/api/v1/search")
  if python3 -c 'import json,sys
messages=json.load(sys.stdin)["messages"]
subjects=sorted(item["Subject"] for item in messages)
sys.exit(0 if subjects == ["Order cancelled", "Order placed"] else 1)' <<< "$MAIL"; then
    MAIL_OK=true
    break
  fi
done
[[ $MAIL_OK == true ]] || fail "Mailpit did not capture one placed and one cancelled email for $ORDER"
printf '\nDemo passed. Order: %s. Mailpit: %s\n' "$ORDER" "$MAILPIT"