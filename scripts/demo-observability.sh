#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/demo-lib.sh"

PROMETHEUS=${PROMETHEUS:-http://localhost:9090}
GRAFANA=${GRAFANA:-http://localhost:3001}
MANAGEMENT=${MANAGEMENT:-http://localhost:8081}

await_series() {
  local expression=$1 deadline=$((SECONDS + 60))
  while ((SECONDS < deadline)); do
    BODY=$(curl --max-time 5 -fsS --get --data-urlencode "query=$expression" "$PROMETHEUS/api/v1/query")
    if [[ $(printf '%s' "$BODY" | json status) == success && $(printf '%s' "$BODY" | json data.result) != '[]' ]]; then
      return
    fi
  done
  fail "Prometheus has no matching sample: $expression"
}

step "Management-only metrics and a healthy Prometheus target"
curl --max-time 5 -fsS "$MANAGEMENT/actuator/prometheus" >/dev/null
api 401 GET /actuator/prometheus "" "" >/dev/null
await_series 'min(up{job="ecommerce"}) == 1'

step "Exercise placement, cancellation, and an out-of-stock rejection"
bash "$(dirname "$0")/demo-orders.sh"

step "Observe durable state, queue age, and HTTP traffic through Prometheus"
await_series 'max(ecom_orders_current{job="ecommerce",status="REJECTED"}) > 0'
await_series 'max(ecom_orders_current{job="ecommerce",status="CANCELLED"}) > 0'
await_series 'max(ecom_outbox_pending{job="ecommerce",channel="kafka"}) == 0'
await_series 'max(ecom_outbox_oldest_age_seconds{job="ecommerce",channel="kafka"}) == 0'
await_series 'max(ecom_notifications_current{job="ecommerce",status="SENT"}) > 0'
curl --max-time 5 -fsS "$API/v1/products" >/dev/null
await_series 'sum(http_server_requests_seconds_count{job="ecommerce",uri="/v1/products"}) > 0'
for attempt in {1..10}; do
  curl --max-time 5 -fsS "$API/v1/products" >/dev/null
done
await_series 'sum(rate(http_server_requests_seconds_count{job="ecommerce",uri=~"/v1/.*"}[5m])) > 0'
await_series 'count(http_server_requests_seconds_bucket{job="ecommerce",uri=~"/v1/.*"}) > 0'

step "Verify the provisioned dashboard and loaded operational rules"
BODY=$(curl --max-time 5 -fsS "$GRAFANA/api/dashboards/uid/ecommerce-operations")
check "$BODY" dashboard.uid=ecommerce-operations 'dashboard.panels.0.title=Scrape Health'
BODY=$(curl --max-time 5 -fsS "$PROMETHEUS/api/v1/rules")
check "$BODY" status=success data.groups.0.name=ecommerce-portfolio
docker compose logs --no-color --since=5m app | grep -F order_milestone_committed >/dev/null

printf '\nObservability demo passed. Dashboard: %s/d/ecommerce-operations\n' "$GRAFANA"