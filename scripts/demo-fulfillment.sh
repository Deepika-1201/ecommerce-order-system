#!/usr/bin/env bash
# Phase 8 walkthrough against the ecosystem stack (LLD §8.14): orders paid on the real gateway's hosted checkout, then
# packed and handed over by meera, and carried by the carrier simulator, whose scenario each delivery PIN code picks by
# its last digit: delivered; delivered though its scans arrive out of order; returned to origin, restocked and refunded;
# booked after a carrier outage; refused at placement for an unserviceable PIN code; and a forged carrier webhook.
# Needs curl and python3; safe to run again.
#   docker compose -f docker-compose.yml -f docker-compose.ecosystem.yml up --build --detach && scripts/demo-fulfillment.sh
set -euo pipefail
source "$(dirname "$0")/demo-lib.sh"

RUN=$(date +%s)   # suffix for the slug, the SKUs and the keys; the keys also name this demo (see demo-orders.sh)

# await_order ORDER SECONDS PATH=VALUE...: follows asha's order, as a client would, until every field has its value;
# sets ORDER_BODY.
await_order() {
  local order=$1 seconds=$2 attempt
  shift 2
  for attempt in $(seq 1 $((seconds * 2))); do
    ORDER_BODY=$(api 200 GET "/v1/me/orders/$order" "$ASHA" "")
    if check "$ORDER_BODY" "$@" 2> /dev/null; then
      return 0
    fi
    sleep 0.5
  done
  check "$ORDER_BODY" "$@" || fail "order $order did not get there in $seconds s"
}

# lamp PIN: an active lamp, with 1 unit in stock, for delivery to that PIN code; sets SKU.
lamp() {
  SKU="LAMP-$RUN-$1"
  local body product
  body="{\"title\": \"Lamp $1 $RUN\", \"category_id\": \"$CATEGORY\", \"gst_category\": \"STANDARD\", \"options\": []}"
  product=$(api 201 POST /v1/admin/catalog/products "$ADMIN" "$body" | json id)
  body="{\"sku\": \"$SKU\", \"option_values\": {}, \"price_paise\": 59900}"
  api 201 POST "/v1/admin/catalog/products/$product/variants" "$ADMIN" "$body" > /dev/null
  api 200 POST "/v1/admin/catalog/products/$product/activate" "$ADMIN" "" > /dev/null
  api 200 POST "/v1/warehouse/stock/$SKU/receipts" "$MEERA" '{"quantity": 1}' \
    "Idempotency-Key: fulfillment-receipt-$RUN-$1" > /dev/null
}

# address PIN: asha's address at that PIN code in Bengaluru; sets ADDRESS.
address() {
  local body="{\"recipient_name\": \"Asha Rao\", \"phone\": \"98765 43210\", \"line1\": \"12, 4th Cross\",
    \"city\": \"Bengaluru\", \"state_code\": \"29\", \"pin_code\": \"$1\"}"
  ADDRESS=$(api 201 POST /v1/me/addresses "$ASHA" "$body" | json id)
}

# quote_lamp: asha's cart holds only the SKU, quoted for Karnataka; sets QUOTE.
quote_lamp() {
  local skus sku
  skus=$(api 200 GET /v1/me/cart "$ASHA" "" | json lines | python3 -c '
import json, sys
print(" ".join(line["sku"] for line in json.load(sys.stdin)))')
  for sku in $skus; do   # lines from other demos
    api 200 DELETE "/v1/me/cart/lines/$sku" "$ASHA" "" > /dev/null
  done
  api 200 DELETE /v1/me/cart/coupon "$ASHA" "" > /dev/null
  api 200 PUT "/v1/me/cart/lines/$SKU" "$ASHA" '{"quantity": 1}' > /dev/null
  QUOTE=$(api 201 POST /v1/me/cart/quotes "$ASHA" '{"delivery_state_code": "29"}' | json id)
}

# confirmed_order PIN: asha orders a lamp to that PIN code and pays on the hosted checkout; sets ORDER, CONFIRMED.
confirmed_order() {
  local body checkout page psp
  lamp "$1"
  address "$1"
  quote_lamp
  body="{\"quote_id\": \"$QUOTE\", \"delivery_address_id\": \"$ADDRESS\"}"
  ORDER=$(api 202 POST /v1/me/orders "$ASHA" "$body" "Idempotency-Key: fulfillment-place-$RUN-$1" | json id)
  await_order "$ORDER" 60 status=AWAITING_PAYMENT
  checkout=$(json checkout_url <<< "$ORDER_BODY")
  curl -sS -o /dev/null -X POST "$checkout" --data-urlencode method=card || fail "the checkout page did not answer"
  page=$(curl -sS "$checkout") || fail "the checkout page did not answer"
  psp=$(grep -o 'href="[^"]*/simulator/[^"]*"' <<< "$page" | head -1 | sed 's/^href="//; s/"$//')
  [[ -n $psp ]] || fail "the checkout did not send asha to the PSP's page"
  curl -sS -o /dev/null -X POST "$psp" --data-urlencode outcome=success || fail "the PSP's page did not answer"
  await_order "$ORDER" 60 status=CONFIRMED
}

# shipment_of ORDER STATUS SECONDS: meera's list of shipments in STATUS, until it holds the order's; sets SHIPMENT.
shipment_of() {
  local attempt list
  for attempt in $(seq 1 $(($3 * 2))); do
    list=$(api 200 GET "/v1/warehouse/shipments?status=$2&limit=50" "$MEERA" "")
    SHIPMENT=$(python3 -c '
import json, sys
items = [item for item in json.loads(sys.argv[1])["items"] if item["order_id"] == sys.argv[2]]
print(items[0]["id"] if items else "")' "$list" "$1")
    if [[ -n $SHIPMENT ]]; then
      return 0
    fi
    sleep 0.5
  done
  fail "order $1's shipment was not $2 in $3 s"
}

# ship ORDER: meera packs the booked shipment and hands it over to the carrier.
ship() {
  shipment_of "$1" BOOKED 30
  check "$(api 200 POST "/v1/warehouse/shipments/$SHIPMENT/packed" "$MEERA" "")" status=PACKED
  check "$(api 200 POST "/v1/warehouse/shipments/$SHIPMENT/handed-over" "$MEERA" "")" status=HANDED_OVER
}

# tracking BODY: the order's tracking, oldest first, as status names.
tracking() {
  python3 -c '
import json, sys
print(" ".join(step["status"] for step in reversed(json.loads(sys.argv[1])["shipment"]["tracking"])))' "$1"
}

step "Tokens for asha (customer), admin and meera (warehouse)"
ASHA=$(token asha asha-local-only)
ADMIN=$(token admin admin-local-only)
MEERA=$(token meera meera-local-only)
echo "three tokens"

BODY="{\"name\": \"Fulfillment demo $RUN\", \"slug\": \"fulfillment-demo-$RUN\"}"
CATEGORY=$(api 201 POST /v1/admin/catalog/categories "$ADMIN" "$BODY" | json id)

step "560038: asha pays; meera packs and hands the parcel over; the carrier delivers it"
confirmed_order 560038
await_order "$ORDER" 30 shipment.status=BOOKED "shipment.carrier=Simulated Carrier"
ship "$ORDER"
await_order "$ORDER" 30 status=SHIPPED
echo "SHIPPED, AWB $(json shipment.awb <<< "$ORDER_BODY")"
await_order "$ORDER" 60 status=DELIVERED shipment.status=DELIVERED
echo "DELIVERED; tracking: $(tracking "$ORDER_BODY")"

step "560003: the carrier's scans arrive out of order and late; the order is delivered all the same"
confirmed_order 560003
ship "$ORDER"
await_order "$ORDER" 60 status=DELIVERED shipment.status=DELIVERED
sleep 20   # the scenario's late scans, in transit and out for delivery, arrive after the delivery
await_order "$ORDER" 5 status=DELIVERED shipment.status=DELIVERED
echo "DELIVERED, unmoved by the late scans; tracking: $(tracking "$ORDER_BODY")"

step "560005: two failed attempts and a return to origin: restocked, and the refund succeeds"
confirmed_order 560005
RETURNED=$ORDER
RETURNED_SKU=$SKU
ship "$ORDER"
await_order "$ORDER" 90 status=RETURNED_TO_ORIGIN shipment.status=RTO_DELIVERED refund.status=SUCCEEDED \
  refund.amount_paise=59900
check "$(api 200 GET "/v1/warehouse/stock/$RETURNED_SKU" "$MEERA" "")" on_hand=1 reserved=0 available=1
echo "RETURNED_TO_ORIGIN; the lamp is back in stock; refund of Rs 599.00 SUCCEEDED"

step "560001: the carrier is down for the first two bookings; the third books"
confirmed_order 560001
shipment_of "$ORDER" BOOKED 60
echo "BOOKED after the carrier's outage"

step "560002: the carrier does not deliver there, so placement refuses before anything is held"
lamp 560002
address 560002
quote_lamp
expect_code 422 address_not_serviceable POST /v1/me/orders "$ASHA" \
  "{\"quote_id\": \"$QUOTE\", \"delivery_address_id\": \"$ADDRESS\"}" "Idempotency-Key: fulfillment-place-$RUN-560002"
check "$(api 200 GET "/v1/warehouse/stock/$SKU" "$MEERA" "")" on_hand=1 reserved=0
echo "422 address_not_serviceable; nothing reserved"

step "A carrier webhook signed with the wrong secret is refused"
NOW=$(date +%s)
expect_code 401 invalid_signature POST /v1/webhooks/carrier "" \
  '{"id": "evt_forged", "type": "tracking.scan", "created_at": "2026-10-04T10:00:00Z", "data": {}}' \
  "Carrier-Signature: t=$NOW,v1=$(printf '%064d' 0)"
echo "401 invalid_signature"

printf '\nDemo passed.\n'
