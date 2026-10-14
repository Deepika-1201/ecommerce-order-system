#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
WORKLOAD=${1:-browse}
[[ $WORKLOAD == browse || $WORKLOAD == contention ]] || { printf 'Choose browse or contention\n' >&2; exit 2; }
export COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.yml:docker-compose.portfolio.yml:docker-compose.observability.yml}
RUN_ID=${RUN_ID:-"$WORKLOAD-$(date -u +%Y%m%dT%H%M%S)-$$"}
[[ $RUN_ID =~ ^[A-Za-z0-9-]{1,40}$ ]] || { printf 'Invalid RUN_ID\n' >&2; exit 2; }
OUTPUT="build/reports/load/$RUN_ID"
[[ ! -e $OUTPUT ]] || { printf 'Refusing to overwrite %s\n' "$OUTPUT" >&2; exit 2; }
mkdir -p "$OUTPUT"
node scripts/load/report.mjs --self-test
node scripts/load/environment.mjs "$OUTPUT/environment-before.json"
APP=$(docker compose ps -q app)
NETWORK=$(docker inspect "$APP" --format '{{range $name, $value := .NetworkSettings.Networks}}{{$name}}{{"\n"}}{{end}}')
[[ $NETWORK != *$'\n'* ]] || { printf 'Application must use one local compose network\n' >&2; exit 2; }

ret=0
docker run --rm --user "$(id -u):$(id -g)" --network "$NETWORK" --cpus=1 --memory=256m \
  -v "$ROOT/scripts/load:/work:ro" -v "$ROOT/$OUTPUT:/results" \
  -e "RUN_ID=$RUN_ID" -e "WORKLOAD=$WORKLOAD" -e "RATE=${RATE:-10}" -e "SECONDS=${DURATION_SECONDS:-60}" \
  -e "VUS=${VUS:-8}" -e "BUYERS=${BUYERS:-24}" -e "STOCK=${STOCK:-5}" \
  grafana/k6:2.3.0 run --quiet /work/portfolio.js || ret=$?
node scripts/load/environment.mjs "$OUTPUT/environment-after.json"
[[ $ret -eq 0 ]] || { printf 'Load run failed; inspect %s (k6 exit %s)\n' "$OUTPUT" "$ret" >&2; exit "$ret"; }

AUDIT=-
if [[ $WORKLOAD == contention ]]; then
  SKU=$(node -p 'JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).sku' "$OUTPUT/summary.json")
  docker compose exec -T postgres psql -U ecommerce -d ecommerce -qAt -v ON_ERROR_STOP=1 \
    -v "run_id=$RUN_ID" -v "sku=$SKU" < scripts/load/audit.sql > "$OUTPUT/audit.json"
  AUDIT="$OUTPUT/audit.json"
fi
node scripts/load/report.mjs "$OUTPUT/summary.json" "$AUDIT" "$OUTPUT/verified.json"
printf '\nVerified load evidence: %s\n' "$OUTPUT"