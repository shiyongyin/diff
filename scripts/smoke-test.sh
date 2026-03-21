#!/usr/bin/env bash
set -euo pipefail
# ============================================================================
# Smoke test: 启动 demo 应用 → 核心流程 → 验证 HTTP 200
# 用法: ./scripts/smoke-test.sh
# 环境变量:
#   BASE_URL  - 应用地址 (默认 http://localhost:8080)
#   MAX_WAIT  - 最大等待启动秒数 (默认 60)
#   SKIP_BOOT - 设为 1 则跳过启动步骤，直接对已运行的应用执行测试
# ============================================================================

BASE_URL="${BASE_URL:-http://localhost:8080}"
MAX_WAIT="${MAX_WAIT:-60}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_PID=""

cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    echo "[smoke] Stopping application (PID=$APP_PID)..."
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

assert_ok() {
  local step="$1"
  local http_code="$2"
  if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
    echo "[smoke] PASS: $step (HTTP $http_code)"
  else
    echo "[smoke] FAIL: $step (HTTP $http_code)" >&2
    exit 1
  fi
}

# ---------- 1. 启动应用 ----------
if [ "${SKIP_BOOT:-0}" != "1" ]; then
  echo "[smoke] Building and starting demo application..."
  cd "$REPO_ROOT"
  ./mvnw -pl tenant-diff-demo -am package -DskipTests -q
  java -jar tenant-diff-demo/target/tenant-diff-demo-0.0.1-SNAPSHOT.jar &
  APP_PID=$!
  echo "[smoke] Application PID=$APP_PID"
fi

# ---------- 2. 等待应用就绪 ----------
echo "[smoke] Waiting for application to start (max ${MAX_WAIT}s)..."
STARTED=0
for i in $(seq 1 "$MAX_WAIT"); do
  if curl -sf "$BASE_URL/api/tenantDiff/standalone/session/get?sessionId=0" > /dev/null 2>&1; then
    STARTED=1
    echo "[smoke] Application ready after ${i}s"
    break
  fi
  sleep 1
done
if [ "$STARTED" -ne 1 ]; then
  echo "[smoke] FAIL: Application did not start within ${MAX_WAIT}s" >&2
  exit 1
fi

# ---------- 3. 创建对比会话 ----------
echo "[smoke] Step 1: Create diff session..."
CREATE_RESPONSE=$(curl -sf -w "\n%{http_code}" -X POST "$BASE_URL/api/tenantDiff/standalone/session/create" \
  -H 'Content-Type: application/json' \
  -d '{"sourceTenantId":1,"targetTenantId":2,"scope":{"businessTypes":["EXAMPLE_PRODUCT"]}}')
CREATE_HTTP=$(echo "$CREATE_RESPONSE" | tail -1)
CREATE_BODY=$(echo "$CREATE_RESPONSE" | sed '$d')
assert_ok "create session" "$CREATE_HTTP"

# 提取 sessionId (兼容常见 JSON 格式)
SESSION_ID=$(echo "$CREATE_BODY" | grep -o '"sessionId"[[:space:]]*:[[:space:]]*[0-9]*' | head -1 | grep -o '[0-9]*$')
if [ -z "$SESSION_ID" ]; then
  echo "[smoke] FAIL: Could not extract sessionId from response" >&2
  echo "$CREATE_BODY" >&2
  exit 1
fi
echo "[smoke]   sessionId=$SESSION_ID"

# ---------- 4. 查询会话汇总 ----------
echo "[smoke] Step 2: Get session summary..."
HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/tenantDiff/standalone/session/get?sessionId=$SESSION_ID")
assert_ok "get session summary" "$HTTP_CODE"

# ---------- 5. 查询业务摘要列表 ----------
echo "[smoke] Step 3: List business summaries..."
HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/tenantDiff/standalone/session/listBusiness?sessionId=$SESSION_ID&pageNo=1&pageSize=20")
assert_ok "list business summaries" "$HTTP_CODE"

# ---------- 6. 查询业务明细 ----------
echo "[smoke] Step 4: Get business detail..."
HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/tenantDiff/standalone/session/getBusinessDetail?sessionId=$SESSION_ID&businessType=EXAMPLE_PRODUCT&businessKey=PROD-002")
assert_ok "get business detail" "$HTTP_CODE"

# ---------- 7. 执行 Apply ----------
echo "[smoke] Step 5: Execute apply..."
APPLY_RESPONSE=$(curl -sf -w "\n%{http_code}" -X POST "$BASE_URL/api/tenantDiff/standalone/apply/execute" \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":$SESSION_ID,\"direction\":\"A_TO_B\",\"options\":{\"allowDelete\":false,\"maxAffectedRows\":10,\"businessTypes\":[\"EXAMPLE_PRODUCT\"],\"diffTypes\":[\"INSERT\",\"UPDATE\"]}}")
APPLY_HTTP=$(echo "$APPLY_RESPONSE" | tail -1)
APPLY_BODY=$(echo "$APPLY_RESPONSE" | sed '$d')
assert_ok "execute apply" "$APPLY_HTTP"

# 提取 applyId
APPLY_ID=$(echo "$APPLY_BODY" | grep -o '"applyId"[[:space:]]*:[[:space:]]*[0-9]*' | head -1 | grep -o '[0-9]*$')
if [ -z "$APPLY_ID" ]; then
  echo "[smoke] WARN: Could not extract applyId, skipping rollback test"
else
  echo "[smoke]   applyId=$APPLY_ID"

  # ---------- 8. 执行 Rollback ----------
  echo "[smoke] Step 6: Rollback..."
  HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/tenantDiff/standalone/apply/rollback" \
    -H 'Content-Type: application/json' \
    -d "{\"applyId\":$APPLY_ID}")
  assert_ok "rollback" "$HTTP_CODE"
fi

echo ""
echo "[smoke] ========================================="
echo "[smoke]  All smoke tests passed!"
echo "[smoke] ========================================="
