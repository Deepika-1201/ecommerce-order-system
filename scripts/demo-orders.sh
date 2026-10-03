#!/usr/bin/env bash
# Phase 6 walkthrough against the compose stack (LLD §6.12): asha orders one of two units, retries safely with an
# Idempotency-Key and cancels, and the unit is back; an order for more than there is is rejected, naming the SKU.
# Payment success and shipping need the simulators, which have no HTTP surface (ADR-022): tests show them until
# phases 7 and 8. Needs curl and python3; safe to run again.
#   docker compose up --build --detach && scripts/demo-orders.sh
set -euo pipefail
source "$(dirname "$0")/demo-lib.sh"

RUN=$(date +%s)   # suffix for the slug, the SKU and the keys, so every run creates its own
# The keys also name this demo: in CI the demos run within the same second, and meera's receipt key would collide.
SKU="KETTLE-$RUN"
STOCK="/v1/warehouse/stock/$SKU"

# await_status ORDER STATUS: follows asha's order, as a client would, for up to 20 seconds; sets ORDER_BODY.
await_status() {
  local status="" attempt
  for attempt in $(seq 1 100); do
    ORDER_BODY=$(api 200 GET "/v1/me/orders/$1" "$ASHA" "")
    status=$(json status <<< "$ORDER_BODY")
    if [[ $status == "$2" ]]; then
      return 0
    fi
    sleep 0.2
  done
  fail "order $1 is $status after 20 s, expected $2"
}

# quote_for UNITS: asha's cart holds only this many of the SKU; sets QUOTE to a quote for Karnataka.
quote_for() {
  api 200 PUT "/v1/me/cart/lines/$SKU" "$ASHA" "{\"quantity\": $1}" > /dev/null
  QUOTE=$(api 201 POST /v1/me/cart/quotes "$ASHA" '{"delivery_state_code": "29"}' | json id)
}

step "Tokens for asha and ravi (customers), admin, meera (warehouse) and sunita (support)"
ASHA=$(token asha asha-local-only)
RAVI=$(token ravi ravi-local-only)
ADMIN=$(token admin admin-local-only)
MEERA=$(token meera meera-local-only)
SUNITA=$(token sunita sunita-local-only)
echo "five tokens"

step "Admin: a kettle at Rs 1,499, SKU $SKU; meera receives 2 units"
BODY="{\"name\": \"Orders demo $RUN\", \"slug\": \"orders-demo-$RUN\"}"
CATEGORY=$(api 201 POST /v1/admin/catalog/categories "$ADMIN" "$BODY" | json id)
BODY="{\"title\": \"Electric kettle $RUN\", \"category_id\": \"$CATEGORY\", \"gst_category\": \"STANDARD\", \"options\": []}"
PRODUCT=$(api 201 POST /v1/admin/catalog/products "$ADMIN" "$BODY" | json id)
BODY="{\"sku\": \"$SKU\", \"option_values\": {}, \"price_paise\": 149900}"
api 201 POST "/v1/admin/catalog/products/$PRODUCT/variants" "$ADMIN" "$BODY" > /dev/null
api 200 POST "/v1/admin/catalog/products/$PRODUCT/activate" "$ADMIN" "" > /dev/null
LEVELS=$(api 200 POST "$STOCK/receipts" "$MEERA" '{"quantity": 2}' "Idempotency-Key: orders-receipt-$RUN")
check "$LEVELS" on_hand=2 reserved=0
echo "2 on hand"

step "asha: a Karnataka address, 1 kettle in her cart, a quote and an order"
SKUS=$(api 200 GET /v1/me/cart "$ASHA" "" | json lines | python3 -c '
import json, sys
print(" ".join(line["sku"] for line in json.load(sys.stdin)))')
for sku in $SKUS; do   # lines from other demos
  api 200 DELETE "/v1/me/cart/lines/$sku" "$ASHA" "" > /dev/null
done
api 200 DELETE /v1/me/cart/coupon "$ASHA" "" > /dev/null
BODY='{"recipient_name": "Asha Rao", "phone": "98765 43210", "line1": "12, 4th Cross, Indiranagar",
  "city": "Bengaluru", "state_code": "29", "pin_code": "560038"}'
ADDRESS=$(api 201 POST /v1/me/addresses "$ASHA" "$BODY" | json id)
quote_for 1
PLACE="{\"quote_id\": \"$QUOTE\", \"delivery_address_id\": \"$ADDRESS\"}"
PLACED=$(api 202 POST /v1/me/orders "$ASHA" "$PLACE" "Idempotency-Key: orders-place-$RUN")
ORDER=$(json id <<< "$PLACED")
check "$PLACED" status=PLACED "lines.$SKU.quantity=1" totals.grand_total_paise=149900 delivery_address.city=Bengaluru
echo "order $(json number <<< "$PLACED"), PLACED: Rs 1,499, GST included"

step "asha: the same request with the same key returns the same order; ravi cannot see it"
REPLAYED=$(api 202 POST /v1/me/orders "$ASHA" "$PLACE" "Idempotency-Key: orders-place-$RUN")
check "$REPLAYED" "id=$ORDER"
expect_code 409 quote_already_ordered POST /v1/me/orders "$ASHA" "$PLACE" "Idempotency-Key: orders-again-$RUN"
expect_code 404 not_found GET "/v1/me/orders/$ORDER" "$RAVI" ""
echo "one order per quote; another customer's order is not found"

step "The order process reserves the unit and sets up the payment"
await_status "$ORDER" AWAITING_PAYMENT
echo "AWAITING_PAYMENT; pay at $(json checkout_url <<< "$ORDER_BODY")"
LEVELS=$(api 200 GET "$STOCK" "$MEERA" "")
check "$LEVELS" on_hand=2 reserved=1 available=1
echo "meera sees 1 unit reserved"

step "asha cancels: the payment is cancelled and the unit is available again"
CANCELLING=$(api 202 POST "/v1/me/orders/$ORDER/cancel" "$ASHA" "" "Idempotency-Key: orders-cancel-$RUN")
echo "accepted: $(json status <<< "$CANCELLING")"
await_status "$ORDER" CANCELLED
check "$ORDER_BODY" reason=CUSTOMER
LEVELS=$(api 200 GET "$STOCK" "$MEERA" "")
check "$LEVELS" on_hand=2 reserved=0 available=2
echo "CANCELLED (CUSTOMER); 2 available"

step "asha orders 3 kettles: there are 2, so the order is rejected, naming the SKU"
quote_for 3
BODY="{\"quote_id\": \"$QUOTE\", \"delivery_address_id\": \"$ADDRESS\"}"
REJECTED=$(api 202 POST /v1/me/orders "$ASHA" "$BODY" "Idempotency-Key: orders-too-many-$RUN" | json id)
await_status "$REJECTED" REJECTED
check "$ORDER_BODY" reason=OUT_OF_STOCK "unavailable_sku=$SKU"
LEVELS=$(api 200 GET "$STOCK" "$MEERA" "")
check "$LEVELS" reserved=0 available=2
echo "REJECTED (OUT_OF_STOCK, $SKU); nothing held"

step "sunita (support) reads it with its customer; asha cannot use support's path"
SEEN=$(api 200 GET "/v1/support/orders/$REJECTED" "$SUNITA" "")
check "$SEEN" status=REJECTED reason=OUT_OF_STOCK
echo "customer $(json customer_id <<< "$SEEN")"
expect_code 403 forbidden GET "/v1/support/orders/$REJECTED" "$ASHA" ""
api 204 DELETE "/v1/me/addresses/$ADDRESS" "$ASHA" "" > /dev/null
KEPT=$(api 200 GET "/v1/me/orders/$ORDER" "$ASHA" "")
check "$KEPT" delivery_address.city=Bengaluru
echo "the deleted address stays on the order, as a snapshot"

printf '\nDemo passed.\n'
