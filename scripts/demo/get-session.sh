#!/usr/bin/env bash
set -euo pipefail
SESSION_ID="${1:?Usage: ./scripts/demo/get-session.sh <sessionId>}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
curl -sS "$BASE_URL/api/tenantDiff/standalone/session/get?sessionId=$SESSION_ID"
echo
