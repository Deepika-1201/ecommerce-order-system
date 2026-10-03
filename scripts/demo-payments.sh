#!/usr/bin/env bash
# Phase 7 walkthrough against the real Payment Gateway (LLD §7.15). Each order's price picks one of the gateway's mock
# PSP scenarios by its last two digits: asha pays on the hosted checkout and the webhook confirms the order, then she
# cancels and is refunded; a PSP timeout and a PSP that never calls back are resolved by the gateway's own checks; a
# declined payment is cancelled; a forged webhook is refused. Needs curl and python3; safe to run again.
#   docker compose -f docker-compose.yml -f docker-compose.ecosystem.yml up --build --detach && scripts/demo-payments.sh
set -euo pipefail
source "$(dirname "$0")/demo-lib.sh"

RUN=$(date +%s)   # suffix for the slug, the SKUs and the keys; the keys also name this demo (see demo-orders.sh)

# await_order ORDER PATH=VALUE...: follows asha's order, as a client would, for up to 60 seconds, until every field
# has its value; sets ORDER_BODY.
await_order() {
  local order=$1 attempt
  shift
  for attempt in $(seq 1 120); do
    ORDER_BODY=$(api 200 GET "/v1/me/orders/$order" "$ASHA" "")
    if check "$ORDER_BODY" "$@" 2> /dev/null; then
      return 0
    fi
    sleep 0.5
  done
  check "$ORDER_BODY" "$@" || fail "order $order did not get there in 60 s"
}

# kettle PRICE_PAISE: an active kettle at this GST-inclusive price, with 1 unit in stock; sets SKU.
kettle() {
  SKU="KETTLE-$RUN-$1"
  local body product
  body="{\"title\": \"Kettle $1 $RUN\", \"category_id\": \"$CATEGORY\", \"gst_category\": \"STANDARD\", \"options\": []}"
  product=$(api 201 POST /v1/admin/catalog/products "$ADMIN" "$body" | json id)
  body="{\"sku\": \"$SKU\", \"option_values\": {}, \"price_paise\": $1}"
  api 201 POST "/v1/admin/catalog/products/$product/variants" "$ADMIN" "$body" > /dev/null
  api 200 POST "/v1/admin/catalog/products/$product/activate" "$ADMIN" "" > /dev/null
  api 200 POST "/v1/warehouse/stock/$SKU/receipts" "$MEERA" '{"quantity": 1}' \
    "Idempotency-Key: payments-receipt-$RUN-$1" > /dev/null
}

# place_order: asha's cart holds only the SKU; she quotes it and orders it, and the order waits for her payment;
# sets ORDER and CHECKOUT.
place_order() {
  local skus sku quote body
  skus=$(api 200 GET /v1/me/cart "$ASHA" "" | json lines | python3 -c '
import json, sys
print(" ".join(line["sku"] for line in json.load(sys.stdin)))')
  for sku in $skus; do   # lines from other demos
    api 200 DELETE "/v1/me/cart/lines/$sku" "$ASHA" "" > /dev/null
  done
  api 200 DELETE /v1/me/cart/coupon "$ASHA" "" > /dev/null
  api 200 PUT "/v1/me/cart/lines/$SKU" "$ASHA" '{"quantity": 1}' > /dev/null
  quote=$(api 201 POST /v1/me/cart/quotes "$ASHA" '{"delivery_state_code": "29"}' | json id)
  body="{\"quote_id\": \"$quote\", \"delivery_address_id\": \"$ADDRESS\"}"
  ORDER=$(api 202 POST /v1/me/orders "$ASHA" "$body" "Idempotency-Key: payments-place-$RUN-$SKU" | json id)
  await_order "$ORDER" status=AWAITING_PAYMENT
  CHECKOUT=$(json checkout_url <<< "$ORDER_BODY")
}

# pay_by_card: on the hosted checkout, asha chooses a card; sets PAGE to the page the gateway shows next.
pay_by_card() {
  curl -sS -o /dev/null -X POST "$CHECKOUT" --data-urlencode method=card || fail "the checkout page did not answer"
  PAGE=$(curl -sS "$CHECKOUT") || fail "the checkout page did not answer"
}

step "Tokens for asha (customer), admin and meera (warehouse)"
ASHA=$(token asha asha-local-only)
ADMIN=$(token admin admin-local-only)
MEERA=$(token meera meera-local-only)
echo "three tokens"

step "Admin: four kettles whose prices pick the mock PSP's scenarios; meera receives one of each"
BODY="{\"name\": \"Payments demo $RUN\", \"slug\": \"payments-demo-$RUN\"}"
CATEGORY=$(api 201 POST /v1/admin/catalog/categories "$ADMIN" "$BODY" | json id)
for price in 59900 59901 59903 59904; do
  kettle "$price"
done
echo "Rs 599.00 (pays), 599.01 (PSP timeout), 599.03 (declined), 599.04 (PSP silent)"
BODY='{"recipient_name": "Asha Rao", "phone": "98765 43210", "line1": "12, 4th Cross, Indiranagar",
  "city": "Bengaluru", "state_code": "29", "pin_code": "560038"}'
ADDRESS=$(api 201 POST /v1/me/addresses "$ASHA" "$BODY" | json id)

step "Rs 599.00: asha pays by card on the hosted checkout, approves on the PSP's page, and the webhook confirms"
SKU="KETTLE-$RUN-59900"
place_order
echo "AWAITING_PAYMENT; checkout at $CHECKOUT"
pay_by_card
PSP_PAGE=$(grep -o 'href="[^"]*/simulator/[^"]*"' <<< "$PAGE" | head -1 | sed 's/^href="//; s/"$//')
[[ -n $PSP_PAGE ]] || fail "the checkout did not send asha to the PSP's page"
curl -sS -o /dev/null -X POST "$PSP_PAGE" --data-urlencode outcome=success || fail "the PSP's page did not answer"
await_order "$ORDER" status=CONFIRMED
PAID=$ORDER
echo "CONFIRMED by the gateway's payment.succeeded webhook"

step "asha cancels it: the shipment is cancelled, and the gateway refunds her"
api 202 POST "/v1/me/orders/$PAID/cancel" "$ASHA" "" "Idempotency-Key: payments-cancel-$RUN" > /dev/null
await_order "$PAID" status=CANCELLED reason=CUSTOMER refund.status=SUCCEEDED refund.amount_paise=59900
echo "CANCELLED; refund of Rs 599.00 SUCCEEDED (refund.succeeded webhook)"

step "Rs 599.01: the PSP times out but takes the money; asha is back from the checkout, and the order is confirmed"
SKU="KETTLE-$RUN-59901"
place_order
pay_by_card
api 202 POST "/v1/me/orders/$ORDER/payment-check" "$ASHA" "" > /dev/null
await_order "$ORDER" status=CONFIRMED
echo "CONFIRMED once the gateway's status check found the success"

step "Rs 599.04: pending, and the PSP never calls back; the gateway's polls find the success"
SKU="KETTLE-$RUN-59904"
place_order
pay_by_card
await_order "$ORDER" status=CONFIRMED
echo "CONFIRMED"

step "Rs 599.03: the card is declined; the payment could be tried again, but asha cancels and the unit is released"
SKU="KETTLE-$RUN-59903"
place_order
pay_by_card
grep -q "not successful" <<< "$PAGE" || fail "the checkout did not report the decline"
check "$(api 200 GET "/v1/me/orders/$ORDER" "$ASHA" "")" status=AWAITING_PAYMENT
api 202 POST "/v1/me/orders/$ORDER/cancel" "$ASHA" "" "Idempotency-Key: payments-decline-$RUN" > /dev/null
await_order "$ORDER" status=CANCELLED reason=CUSTOMER
LEVELS=$(api 200 GET "/v1/warehouse/stock/$SKU" "$MEERA" "")
check "$LEVELS" on_hand=1 reserved=0 available=1
echo "CANCELLED (CUSTOMER), the payment cancelled at the gateway; 1 available"

step "A webhook signed with the wrong secret is refused"
NOW=$(date +%s)
expect_code 401 invalid_signature POST /v1/webhooks/payment-gateway "" \
  '{"id": "evt_forged", "type": "payment.succeeded", "created_at": "2026-10-03T10:00:00Z", "data": {"object": {}}}' \
  "PG-Signature: t=$NOW,v1=$(printf '%064d' 0)"
echo "401 invalid_signature"
api 204 DELETE "/v1/me/addresses/$ADDRESS" "$ASHA" "" > /dev/null

printf '\nDemo passed.\n'
