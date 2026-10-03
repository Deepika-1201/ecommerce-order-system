# Helpers for the demo scripts, which source this file after `set -euo pipefail`. Needs curl and python3; works with
# bash 3.2 (macOS) and later.
#
# Bash clears `set -e` inside "$(...)", so a function that makes several calls sets a variable instead of printing,
# and is never called inside one. A single call such as ID=$(api ... | json id) is safe: pipefail fails the assignment.

API=${API:-http://localhost:8080}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}

DEMO_DIR=$(mktemp -d)
trap 'rm -rf "$DEMO_DIR"' EXIT

step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }

# A field by a dotted path of object keys and list indexes; in a list, a SKU picks that line (lines.SKU-1.quantity).
FIELD_PY='
import json, sys

def field(value, path):
    for key in path.split("."):
        if isinstance(value, list) and not key.isdigit():
            value = next(item for item in value if item.get("sku") == key)
        else:
            value = value[int(key)] if isinstance(value, list) else value[key]
    return value

def text(value):
    return value if isinstance(value, str) else json.dumps(value)
'

# json PATH < body: one field; anything but a string prints as JSON. Empty input means the call before it failed and
# said why, so it exits quietly.
json() {
  python3 -c "$FIELD_PY"'
body = sys.stdin.read()
if not body:
    sys.exit(1)
print(text(field(json.loads(body), sys.argv[1])))
' "$1"
}

# check BODY PATH=VALUE...: fails, naming every mismatch, unless each field has its value.
check() {
  python3 -c "$FIELD_PY"'
document = json.loads(sys.argv[1])
wrong = []
for spec in sys.argv[2:]:
    path, wanted = spec.split("=", 1)
    try:
        actual = text(field(document, path))
    except (KeyError, IndexError, StopIteration):
        actual = "missing"
    if actual != wanted:
        wrong.append(f"{path} is {actual}, expected {wanted}")
if wrong:
    sys.exit("FAILED: " + "; ".join(wrong))
' "$@"
}

# api STATUS METHOD PATH TOKEN BODY [HEADER...]: prints the response body; fails on any other status.
# TOKEN and BODY may be empty.
api() {
  local expected=$1 method=$2 path=$3 token=$4 body=$5
  shift 5
  local args=(-sS -o "$DEMO_DIR/body" -w '%{http_code}' -X "$method")
  if [[ -n $token ]]; then args+=(-H "Authorization: Bearer $token"); fi
  if [[ -n $body ]]; then args+=(-H 'Content-Type: application/json' --data "$body"); fi
  local header
  for header in "$@"; do args+=(-H "$header"); done
  local code
  code=$(curl "${args[@]}" "$API$path") || fail "$method $path: no response"
  [[ $code == "$expected" ]] || fail "$method $path: expected $expected, got $code: $(cat "$DEMO_DIR/body")"
  cat "$DEMO_DIR/body"
}

# expect_code STATUS CODE METHOD PATH TOKEN BODY [HEADER...]: a call that must fail with this status and error code.
expect_code() {
  local wanted=$2 actual
  actual=$(api "$1" "${@:3}" | json code)
  [[ $actual == "$wanted" ]] || fail "$3 $4: expected error code $wanted, got $actual"
}

# token USER PASSWORD: an access token through the local-only password grant.
token() {
  curl -sS --fail-with-body -X POST "$KEYCLOAK/realms/ecommerce/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=ecommerce-cli -d "username=$1" -d "password=$2" | json access_token
}
