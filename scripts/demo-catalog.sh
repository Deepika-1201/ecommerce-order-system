#!/usr/bin/env bash
# Phase 3 walkthrough against the compose stack (LLD §3.12). Needs curl and python3; safe to run again.
#   docker compose up --build --detach && scripts/demo-catalog.sh
set -euo pipefail
source "$(dirname "$0")/demo-lib.sh"

RUN=$(date +%s)   # suffix for slugs and SKUs, so every run creates its own catalog entries
IMAGE_FILE="$DEMO_DIR/image.png"

step "Tokens for asha and ravi (customers) and admin"
ASHA=$(token asha asha-local-only)
RAVI=$(token ravi ravi-local-only)
ADMIN=$(token admin admin-local-only)
api 401 GET /v1/admin/catalog/products "" "" > /dev/null
api 403 GET /v1/admin/catalog/products "$ASHA" "" > /dev/null
echo "no token: 401; a customer on the admin API: 403"

step "Admin: a category, a product with two variants, activated"
FASHION=$(api 201 POST /v1/admin/catalog/categories "$ADMIN" \
  "{\"name\": \"Fashion $RUN\", \"slug\": \"fashion-$RUN\"}" | json id)
api 201 POST /v1/admin/catalog/categories "$ADMIN" \
  "{\"name\": \"Shirts\", \"slug\": \"shirts-$RUN\", \"parent_id\": \"$FASHION\"}" > /dev/null
SHIRTS=$(api 200 GET /v1/categories "" "" | python3 -c '
import json, sys
slug = sys.argv[1]
nodes = json.load(sys.stdin)["items"]
while nodes:
    node = nodes.pop()
    if node["slug"] == slug:
        print(node["id"])
    nodes.extend(node["children"])
' "shirts-$RUN")
PRODUCT=$(api 201 POST /v1/admin/catalog/products "$ADMIN" "{
  \"title\": \"Oxford shirt $RUN\", \"description\": \"Cotton oxford weave, button-down collar\",
  \"category_id\": \"$SHIRTS\", \"gst_category\": \"APPAREL\",
  \"options\": [{\"name\": \"size\", \"values\": [\"M\", \"L\"]}]}" | json id)
api 201 POST "/v1/admin/catalog/products/$PRODUCT/variants" "$ADMIN" \
  "{\"sku\": \"OXF-$RUN-M\", \"option_values\": {\"size\": \"M\"}, \"price_paise\": 149900}" > /dev/null
api 201 POST "/v1/admin/catalog/products/$PRODUCT/variants" "$ADMIN" \
  "{\"sku\": \"OXF-$RUN-L\", \"option_values\": {\"size\": \"L\"}, \"price_paise\": 159900}" > /dev/null
STATUS=$(api 200 POST "/v1/admin/catalog/products/$PRODUCT/activate" "$ADMIN" "" | json status)
echo "product $PRODUCT is $STATUS"

step "Admin: an image through a pre-signed upload URL"
python3 -c 'import base64, sys; sys.stdout.buffer.write(base64.b64decode(
"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))' > "$IMAGE_FILE"
SIZE=$(wc -c < "$IMAGE_FILE" | tr -d ' ')
UPLOAD=$(api 201 POST "/v1/admin/catalog/products/$PRODUCT/images" "$ADMIN" \
  "{\"content_type\": \"image/png\", \"size_bytes\": $SIZE, \"alt_text\": \"Front view\"}")
IMAGE=$(json image.id <<< "$UPLOAD")
UPLOAD_URL=$(json upload.url <<< "$UPLOAD")
UPLOAD_HEADERS=()
while IFS= read -r header; do UPLOAD_HEADERS+=(-H "$header"); done < <(json upload.headers <<< "$UPLOAD" | python3 -c '
import json, sys
for name, value in json.load(sys.stdin).items():
    if name.lower() != "content-length":   # curl sets it from the file
        print(f"{name}: {value}")
')
CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "${UPLOAD_HEADERS[@]}" --data-binary "@$IMAGE_FILE" "$UPLOAD_URL")
[[ $CODE == 200 ]] || fail "upload to storage: HTTP $CODE"
IMAGE_URL=$(api 200 POST "/v1/admin/catalog/products/$PRODUCT/images/$IMAGE/complete" "$ADMIN" "" | json url)
CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$IMAGE_URL")
[[ $CODE == 200 ]] || fail "public image URL: HTTP $CODE"
echo "uploaded $SIZE bytes; anyone can read $IMAGE_URL"

step "Anonymous: browse, search and read the product"
TITLE=$(api 200 GET "/v1/products?category=fashion-$RUN" "" "" | json items.0.title)
echo "category fashion-$RUN (with its subcategories): $TITLE"
FOUND=$(api 200 GET "/v1/products?q=oxford+$RUN" "" "" | json items.0.id)
[[ $FOUND == "$PRODUCT" ]] || fail "search did not find the product"
DETAIL=$(api 200 GET "/v1/products/$PRODUCT" "" "")
echo "search 'oxford $RUN' finds it; from $(json variants.0.price_paise <<< "$DETAIL") paise, $(json images <<< "$DETAIL" | python3 -c 'import json, sys; print(len(json.load(sys.stdin)))') image"
ETAG=$(curl -sS -D - -o /dev/null "$API/v1/products/$PRODUCT" | tr -d '\r' | awk 'tolower($1) == "etag:" {print $2}')
api 304 GET "/v1/products/$PRODUCT" "" "" "If-None-Match: $ETAG" > /dev/null
echo "revalidating with ETag $ETAG: 304 Not Modified"
[[ $(api 404 GET "/v1/products/$(python3 -c 'import uuid; print(uuid.uuid4())')" "" "" | json code) == not_found ]] \
  || fail "an unknown product is not not_found"

step "Customers: asha's address is invisible to ravi"
ADDRESS=$(api 201 POST /v1/me/addresses "$ASHA" '{"recipient_name": "Asha Rao", "phone": "98765 43210",
  "line1": "12, 4th Cross, Indiranagar", "city": "Bengaluru", "state_code": "29", "pin_code": "560038"}' | json id)
api 200 GET "/v1/me/addresses/$ADDRESS" "$ASHA" "" > /dev/null
api 404 GET "/v1/me/addresses/$ADDRESS" "$RAVI" "" > /dev/null
api 404 DELETE "/v1/me/addresses/$ADDRESS" "$RAVI" "" > /dev/null
api 204 DELETE "/v1/me/addresses/$ADDRESS" "$ASHA" "" > /dev/null
echo "asha: 200; ravi reading or deleting it: 404; asha deleted it"

printf '\nDemo passed.\n'
