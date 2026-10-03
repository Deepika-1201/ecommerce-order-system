#!/usr/bin/env bash
# Phase 4 walkthrough against the compose stack (LLD §4.14): a guest cart merged into asha's at sign-in, and quotes
# with GST checked to the paise against worked example 1 of LLD §4.7. Needs curl and python3; safe to run again.
#   docker compose up --build --detach && scripts/demo-cart.sh
set -euo pipefail
source "$(dirname "$0")/demo-lib.sh"

RUN=$(date +%s)   # suffix for slugs, SKUs and the coupon code, so every run creates its own
SHIRT="SHIRT-$RUN"
SHOES="SHOES-$RUN"
BOTTLE="BOTTLE-$RUN"
COUPON="SAVE10-$RUN"

# product TITLE GST_CATEGORY PRICE_PAISE SKU: an active product with one variant.
product() {
  local id
  id=$(api 201 POST /v1/admin/catalog/products "$ADMIN" \
    "{\"title\": \"$1 $RUN\", \"category_id\": \"$CATEGORY\", \"gst_category\": \"$2\", \"options\": []}" | json id)
  api 201 POST "/v1/admin/catalog/products/$id/variants" "$ADMIN" \
    "{\"sku\": \"$4\", \"option_values\": {}, \"price_paise\": $3}" > /dev/null
  api 200 POST "/v1/admin/catalog/products/$id/activate" "$ADMIN" "" > /dev/null
}

step "Tokens for asha and ravi (customers) and admin"
ASHA=$(token asha asha-local-only)
RAVI=$(token ravi ravi-local-only)
ADMIN=$(token admin admin-local-only)
echo "three tokens"

step "Admin: the products of the LLD's worked example, and a coupon of 10% off, up to Rs 500"
CATEGORY=$(api 201 POST /v1/admin/catalog/categories "$ADMIN" \
  "{\"name\": \"Cart demo $RUN\", \"slug\": \"cart-demo-$RUN\"}" | json id)
product "Oxford shirt" APPAREL 129900 "$SHIRT"
product "Running shoes" FOOTWEAR 349900 "$SHOES"
product "Steel bottle" STANDARD 59900 "$BOTTLE"
api 201 POST /v1/admin/pricing/coupons "$ADMIN" \
  "{\"code\": \"$COUPON\", \"kind\": \"PERCENT\", \"percent_bps\": 1000, \"max_discount_paise\": 50000}" > /dev/null
echo "$SHIRT (apparel) at Rs 1,299, $SHOES (footwear) at Rs 3,499, $BOTTLE at Rs 599; coupon $COUPON"

step "Guest: a cart with two shirts, shoes and the coupon"
GUEST=$(api 201 POST /v1/guest/cart "" "" | json cart_token)
VERSION=$(api 200 PUT "/v1/guest/cart/lines/$SHIRT" "" '{"quantity": 2}' "Cart-Token: $GUEST" | json version)
expect_code 412 precondition_failed PUT "/v1/guest/cart/lines/$SHOES" "" '{"quantity": 1}' \
  "Cart-Token: $GUEST" "If-Match: \"$((VERSION - 1))\""
api 200 PUT "/v1/guest/cart/lines/$SHOES" "" '{"quantity": 1}' "Cart-Token: $GUEST" "If-Match: \"$VERSION\"" > /dev/null
CART=$(api 200 PUT /v1/guest/cart/coupon "" "{\"code\": \"$COUPON\"}" "Cart-Token: $GUEST")
check "$CART" "coupon_code=$COUPON" subtotal_paise=609700
echo "a write with a stale If-Match: 412; with the current version it succeeds"
QUOTE=$(api 201 POST /v1/guest/cart/quotes "" '{"delivery_state_code": "29"}' "Cart-Token: $GUEST")
check "$QUOTE" tax_regime=INTRA_STATE totals.gross_paise=609700 totals.discount_paise=50000 \
  totals.shipping_paise=0 totals.grand_total_paise=559700
GUEST_QUOTE=$(json id <<< "$QUOTE")
api 200 GET "/v1/guest/cart/quotes/$GUEST_QUOTE" "" "" "Cart-Token: $GUEST" > /dev/null
echo "quote for Karnataka: Rs 6,097 less Rs 500 (the cap), free shipping, Rs 5,597 to pay"

step "asha: a shirt and a bottle on another device, then sign-in merges the guest cart"
SKUS=$(api 200 GET /v1/me/cart "$ASHA" "" | json lines | python3 -c '
import json, sys
print(" ".join(line["sku"] for line in json.load(sys.stdin)))')
for sku in $SKUS; do   # lines from earlier runs
  api 200 DELETE "/v1/me/cart/lines/$sku" "$ASHA" "" > /dev/null
done
api 200 DELETE /v1/me/cart/coupon "$ASHA" "" > /dev/null
api 200 PUT "/v1/me/cart/lines/$SHIRT" "$ASHA" '{"quantity": 1}' > /dev/null
api 200 PUT "/v1/me/cart/lines/$BOTTLE" "$ASHA" '{"quantity": 1}' > /dev/null
MERGED=$(api 200 POST /v1/me/cart/merge "$ASHA" "" "Cart-Token: $GUEST")
check "$MERGED" "lines.$SHIRT.quantity=2" "lines.$SHOES.quantity=1" "lines.$BOTTLE.quantity=1" \
  "coupon_code=$COUPON" subtotal_paise=669600
expect_code 404 not_found GET /v1/guest/cart "" "" "Cart-Token: $GUEST"
VERSION=$(json version <<< "$MERGED")
RETRIED=$(api 200 POST /v1/me/cart/merge "$ASHA" "" "Cart-Token: $GUEST")
check "$RETRIED" "version=$VERSION" "lines.$SHOES.quantity=1"
echo "the guest's quantity wins (2 shirts), her bottle stays, the coupon moves over: worked example 1"
echo "the guest token is now 404, and a retried merge changes nothing"

step "asha: quotes for Karnataka (CGST and SGST) and Maharashtra (IGST)"
QUOTE=$(api 201 POST /v1/me/cart/quotes "$ASHA" '{"delivery_state_code": "29"}')
check "$QUOTE" tax_regime=INTRA_STATE totals.gross_paise=669600 totals.discount_paise=50000 \
  "lines.$SHIRT.discount_paise=19400" "lines.$SHOES.discount_paise=26127" "lines.$BOTTLE.discount_paise=4473" \
  "lines.$SHIRT.gst_rate_bps=500" "lines.$SHOES.gst_rate_bps=1800" "lines.$BOTTLE.gst_rate_bps=1800" \
  totals.cgst_paise=34646 totals.sgst_paise=34646 totals.igst_paise=0 totals.taxable_value_paise=550308 \
  totals.shipping_paise=0 totals.grand_total_paise=619600
ASHA_QUOTE=$(json id <<< "$QUOTE")
echo "Karnataka: CGST Rs 346.46 + SGST Rs 346.46 on Rs 5,503.08, total Rs 6,196.00, as in the LLD"
QUOTE=$(api 201 POST /v1/me/cart/quotes "$ASHA" '{"delivery_state_code": "27"}')
check "$QUOTE" tax_regime=INTER_STATE "lines.$SHIRT.igst_paise=11448" "lines.$SHOES.igst_paise=49389" \
  "lines.$BOTTLE.igst_paise=8455" totals.cgst_paise=0 totals.sgst_paise=0 totals.igst_paise=69292 \
  totals.taxable_value_paise=550308 totals.grand_total_paise=619600
echo "Maharashtra: IGST Rs 692.92 instead; prices include GST, so the total is the same"

step "ravi: asha's quote is 404, and his cart is his own"
api 200 GET "/v1/me/cart/quotes/$ASHA_QUOTE" "$ASHA" "" > /dev/null
expect_code 404 not_found GET "/v1/me/cart/quotes/$ASHA_QUOTE" "$RAVI" ""
RAVI_CART=$(api 200 GET /v1/me/cart "$RAVI" "")
[[ $RAVI_CART != *"$SHIRT"* ]] || fail "ravi sees asha's lines"
echo "asha reads her quote; ravi gets 404, and his cart has none of her lines"

printf '\nDemo passed.\n'
