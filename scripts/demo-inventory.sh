#!/usr/bin/env bash
# Phase 5 walkthrough against the compose stack (LLD §5.12): meera, a warehouse operator, receives and adjusts stock of
# a catalog SKU, retries safely with an Idempotency-Key, and reads the SKU's movements; other roles are refused.
# Needs curl and python3; safe to run again.
#   docker compose up --build --detach && scripts/demo-inventory.sh
set -euo pipefail
source "$(dirname "$0")/demo-lib.sh"

RUN=$(date +%s)   # suffix for the slug, the SKU and the keys, so every run creates its own
SKU="LAMP-$RUN"
STOCK="/v1/warehouse/stock/$SKU"

step "Tokens for meera (warehouse), asha (customer) and admin"
MEERA=$(token meera meera-local-only)
ASHA=$(token asha asha-local-only)
ADMIN=$(token admin admin-local-only)
echo "three tokens"

step "Admin: a product with the SKU $SKU"
BODY="{\"name\": \"Inventory demo $RUN\", \"slug\": \"inventory-demo-$RUN\"}"
CATEGORY=$(api 201 POST /v1/admin/catalog/categories "$ADMIN" "$BODY" | json id)
BODY="{\"title\": \"Desk lamp $RUN\", \"category_id\": \"$CATEGORY\", \"gst_category\": \"STANDARD\", \"options\": []}"
PRODUCT=$(api 201 POST /v1/admin/catalog/products "$ADMIN" "$BODY" | json id)
BODY="{\"sku\": \"$SKU\", \"option_values\": {}, \"price_paise\": 149900}"
api 201 POST "/v1/admin/catalog/products/$PRODUCT/variants" "$ADMIN" "$BODY" > /dev/null
expect_code 404 not_found GET "$STOCK" "$MEERA" ""
echo "product $PRODUCT; no stock item until the first receipt"

step "meera: receives 5 units, and the same request with the same key is replayed and adds nothing"
RECEIPT="{\"quantity\": 5, \"reference\": \"DN-$RUN\"}"
LEVELS=$(api 200 POST "$STOCK/receipts" "$MEERA" "$RECEIPT" "Idempotency-Key: receipt-$RUN")
check "$LEVELS" on_hand=5 reserved=0 available=5 version=1
REPLAYED=$(api 200 POST "$STOCK/receipts" "$MEERA" "$RECEIPT" "Idempotency-Key: receipt-$RUN")
check "$REPLAYED" on_hand=5 version=1
LEVELS=$(api 200 GET "$STOCK" "$MEERA" "")
check "$LEVELS" on_hand=5 version=1
expect_code 422 idempotency_key_reused POST "$STOCK/receipts" "$MEERA" '{"quantity": 6}' \
  "Idempotency-Key: receipt-$RUN"
echo "5 on hand after the retry; the same key with another body: 422"

step "meera: a damaged unit; taking away more than is on hand is refused"
BODY='{"quantity_change": -1, "reason": "DAMAGED", "note": "Cracked shade"}'
LEVELS=$(api 200 POST "$STOCK/adjustments" "$MEERA" "$BODY" "Idempotency-Key: damaged-$RUN")
check "$LEVELS" on_hand=4 available=4 version=2
expect_code 409 adjustment_below_reserved POST "$STOCK/adjustments" "$MEERA" \
  '{"quantity_change": -5, "reason": "LOST"}' "Idempotency-Key: lost-$RUN"
expect_code 400 invalid_adjustment POST "$STOCK/adjustments" "$MEERA" \
  '{"quantity_change": 1, "reason": "DAMAGED"}' "Idempotency-Key: wrong-sign-$RUN"
echo "4 on hand; 5 lost: 409; a damaged unit that adds stock: 400"

step "meera: the SKU's movements, newest first"
MOVEMENTS=$(api 200 GET "$STOCK/movements" "$MEERA" "")
check "$MOVEMENTS" items.0.kind=ADJUSTMENT items.0.quantity=-1 items.0.reason=DAMAGED items.0.on_hand_after=4 \
  items.1.kind=RECEIPT items.1.quantity=5 "items.1.reference=DN-$RUN" items.1.on_hand_after=5
echo "the adjustment, then the receipt, each with the units on hand after it"

step "asha (customer) and admin get 403; no token gets 401"
expect_code 403 forbidden GET "$STOCK" "$ASHA" ""
expect_code 403 forbidden POST "$STOCK/receipts" "$ADMIN" '{"quantity": 1}' "Idempotency-Key: admin-$RUN"
expect_code 401 unauthorized GET /v1/warehouse/stock "" ""
echo "stock counts are the warehouse's to change"

printf '\nDemo passed.\n'
