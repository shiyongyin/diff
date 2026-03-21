#!/usr/bin/env bash
set -euo pipefail
SESSION_ID="${1:?Usage: ./scripts/demo/execute-apply.sh <sessionId>}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sed -e "s/__SESSION_ID__/$SESSION_ID/g" "$SCRIPT_DIR/payloads/apply-plan.template.json" | \
  curl -sS -X POST "$BASE_URL/api/tenantDiff/standalone/apply/execute" \
    -H 'Content-Type: application/json' \
    --data @-
echo
