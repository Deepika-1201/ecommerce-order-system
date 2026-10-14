#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
export COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.yml:docker-compose.portfolio.yml:docker-compose.observability.yml}

if [[ ${1:-} == --help ]]; then
  printf 'Usage: bash scripts/demo-portfolio.sh\n'
  printf 'Runs the existing event and observability demos against an already-started local portfolio stack.\n'
  printf 'Requires Docker Compose, Bash, curl, Python 3, and the local demo identity accounts.\n'
  exit 0
fi
[[ $# -eq 0 ]] || { printf 'Unknown argument; use --help\n' >&2; exit 2; }
for dependency in docker curl python3; do
  command -v "$dependency" >/dev/null || { printf 'Missing prerequisite: %s\n' "$dependency" >&2; exit 2; }
done

printf 'E-commerce portfolio walkthrough\n'
printf 'Modular monolith; local payment adapter; simulated carrier; real PostgreSQL and Kafka.\n'
printf 'This demonstration creates synthetic local catalogue, cart, order, and email data.\n\n'
curl --max-time 10 -fsS "${MANAGEMENT:-http://localhost:8081}/actuator/health/readiness" >/dev/null
curl --max-time 10 -fsS "${GRAFANA:-http://localhost:3001}/api/health" >/dev/null
curl --max-time 10 -fsS "${MAILPIT:-http://localhost:8025}/livez" >/dev/null

bash scripts/demo-events.sh
bash scripts/demo-observability.sh

printf '\nPortfolio walkthrough passed.\n'
printf 'Dashboard: %s/d/ecommerce-operations\n' "${GRAFANA:-http://localhost:3001}"
printf 'Captured email: %s\n' "${MAILPIT:-http://localhost:8025}"
printf 'Failure evidence: ./gradlew portfolioFailureTest\n'
printf 'Measured performance and limits: docs/portfolio-performance.md\n'
printf 'Security scope: docs/portfolio-security.md\n'
printf 'Reviewer guide: docs/portfolio-guide.md\n'