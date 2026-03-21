#!/usr/bin/env bash
set -euo pipefail
SESSION_ID="${1:?Usage: ./scripts/demo/get-business-detail.sh <sessionId> <businessKey>}"
BUSINESS_KEY="${2:?Usage: ./scripts/demo/get-business-detail.sh <sessionId> <businessKey>}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
BUSINESS_TYPE="${BUSINESS_TYPE:-EXAMPLE_PRODUCT}"
curl -sS "$BASE_URL/api/tenantDiff/standalone/session/getBusinessDetail?sessionId=$SESSION_ID&businessType=$BUSINESS_TYPE&businessKey=$BUSINESS_KEY"
echo
