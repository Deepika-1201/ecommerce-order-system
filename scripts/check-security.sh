#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
TRIVY=${TRIVY:-trivy}
MODE=${1:-jar}
REPORTS=build/reports/security
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
mkdir -p "$REPORTS"

case "$MODE" in
  jar)
    "$TRIVY" rootfs --scanners vuln --list-all-pkgs --severity HIGH,CRITICAL \
      --ignorefile /dev/null --format json --output "$REPORTS/jar-vulnerabilities.json" --timeout 10m build/libs
    node scripts/check-security-report.mjs jar "$REPORTS/jar-vulnerabilities.json"
    ;;
  image)
    IMAGE=${2:?Pass the application image name}
    docker image save --output "$TEMP/application.tar" "$IMAGE"
    "$TRIVY" image --input "$TEMP/application.tar" --scanners vuln --list-all-pkgs --severity HIGH,CRITICAL \
      --ignorefile /dev/null --format json --output "$REPORTS/image-vulnerabilities.json" --timeout 10m
    node scripts/check-security-report.mjs image "$REPORTS/image-vulnerabilities.json"
    ;;
  secrets)
    "$TRIVY" fs --scanners secret --skip-dirs .git --skip-dirs .gradle --skip-dirs build --skip-dirs bin \
      --ignorefile /dev/null --format json --output "$TEMP/secrets.json" --timeout 5m .
    node scripts/check-security-report.mjs secrets "$TEMP/secrets.json"
    ;;
  *)
    printf 'Usage: bash scripts/check-security.sh <jar|image IMAGE|secrets>\n' >&2
    exit 2
    ;;
esac