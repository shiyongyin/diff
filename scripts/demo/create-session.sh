#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
curl -sS -X POST "$BASE_URL/api/tenantDiff/standalone/session/create" \
  -H 'Content-Type: application/json' \
  --data @"$SCRIPT_DIR/payloads/create-session.json"
echo
