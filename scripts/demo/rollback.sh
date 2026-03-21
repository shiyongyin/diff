#!/usr/bin/env bash
set -euo pipefail
APPLY_ID="${1:?Usage: ./scripts/demo/rollback.sh <applyId>}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sed -e "s/__APPLY_ID__/$APPLY_ID/g" "$SCRIPT_DIR/payloads/rollback.template.json" | \
  curl -sS -X POST "$BASE_URL/api/tenantDiff/standalone/apply/rollback" \
    -H 'Content-Type: application/json' \
    --data @-
echo
