#!/usr/bin/env bash
# 修复后复跑：目录主题覆盖校验 + 锚点块补回 + BOOK_QA 输出上限 16384 + 首轮条目化的目录结构提示
# 唯一变量 = 代码修复；QDRANT_RAG_MAX_CHARS 保持默认 0（与 §4.6.6 的"无上限"臂同口径）
# 可用 DATASET / OUT 覆盖样本与输出（默认全量真实黄金集）
set -u
cd /c/server/kbook

TOKEN_FILE=/tmp/eval-token-out.txt
TOKEN=$(sed -n '3p' "$TOKEN_FILE" | tr -d '\r\n')
LOG=/tmp/kbook-boot-postfix.log
DATASET=${DATASET:-docs/eval/golden-set.real.jsonl}
OUT=${OUT:-docs/eval/eval-report-24689-postfix.json}

stop_backend() {
  local PID
  PID=$(wmic process where "name='java.exe'" get ProcessId,CommandLine 2>/dev/null \
    | grep -i "server.port=18181" | grep -oE " [0-9]+ " | head -1 | tr -d ' ')
  if [ -n "${PID:-}" ]; then
    echo "[post] 停止后端 PID=$PID"
    taskkill //PID "$PID" //T //F >/dev/null 2>&1 || true
  fi
  sleep 5
}

stop_backend
echo "[post] 启动后端（默认配置，QDRANT_RAG_MAX_CHARS=0）"
cd /c/server/kbook/backend
mvn -o spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments=-Dserver.port=18181 > "$LOG" 2>&1 &
cd /c/server/kbook

for i in $(seq 1 90); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" \
    http://localhost:18181/api/user/me || echo 000)
  if [ "$CODE" = "200" ]; then echo "[post] 后端就绪 ($CODE)"; break; fi
  sleep 10
done

echo "[post] 开始评测 → $OUT $(date)"
KBOOK_EVAL_TOKEN="$TOKEN" node scripts/eval-bookqa.mjs \
  --base-url http://localhost:18181 \
  --dataset "$DATASET" \
  --runs 1 --timeout-ms 600000 \
  --out "$OUT"
echo "[post] 评测结束 $(date)"
stop_backend
