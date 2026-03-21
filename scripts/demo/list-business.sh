#!/usr/bin/env bash
set -euo pipefail
SESSION_ID="${1:?Usage: ./scripts/demo/list-business.sh <sessionId>}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
PAGE_NO="${PAGE_NO:-1}"
PAGE_SIZE="${PAGE_SIZE:-20}"
curl -sS "$BASE_URL/api/tenantDiff/standalone/session/listBusiness?sessionId=$SESSION_ID&pageNo=$PAGE_NO&pageSize=$PAGE_SIZE"
echo
