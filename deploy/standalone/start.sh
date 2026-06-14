#!/bin/bash
# ============================================================
# KBook 本地开发启动脚本（Linux / Mac — 不依赖 Docker）
# 前置条件：本地已安装 MySQL 8、Redis 7、ES 8.13、Qdrant v1.12
# 使用：./standalone/start.sh
# ============================================================
# 这个脚本会分别启动后端（Spring Boot）和前端（Vite dev server）。
# 首次运行前需要在 backend/ 目录执行 mvn package -DskipTests。
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

cd "$(dirname "$0")/../.."
PROJECT_ROOT="$(pwd)"
echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  KBook 本地开发启动                      ${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""
echo -e "${YELLOW}启动前请确认以下服务已在本地运行：${NC}"
echo "  - MySQL 8 （端口 3306）"
echo "  - Redis 7 （端口 6379）"
echo "  - Elasticsearch 8.13 （端口 9200）"
echo "  - Qdrant v1.12 （gRPC 端口 6334）"
echo ""

# ---- 后端 ----
echo -e "${YELLOW}[1/2] 启动后端（Spring Boot）...${NC}"
cd "$PROJECT_ROOT/backend"
if [ ! -f "target/kbook-server-1.0.0.jar" ]; then
    echo -e "${YELLOW}未找到 JAR 包，执行 mvn package...${NC}"
    mvn package -DskipTests -q
fi
# 后台启动后端，日志输出到 backend.log
nohup java -Xms256m -Xmx512m \
    -Dfile.encoding=UTF-8 \
    -Dsun.jnu.encoding=UTF-8 \
    -Dkbook.ai-config.path=src/main/resources/config/ai-config.json \
    -jar target/kbook-server-1.0.0.jar \
    > ../deploy/standalone/backend.log 2>&1 &
BACKEND_PID=$!
echo -e "${GREEN}✓ 后端已启动（PID: $BACKEND_PID），日志：standalone/backend.log${NC}"

# ---- 前端 ----
echo -e "${YELLOW}[2/2] 启动前端（Vite dev server）...${NC}"
cd "$PROJECT_ROOT/frontend"
# 前台启动前端（可按 Ctrl+C 停止）
echo -e "${GREEN}✓ 前端启动中...${NC}"
echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  KBook 本地开发已启动！${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "  前端地址：  ${BLUE}http://localhost:15173${NC}"
echo -e "  后端地址：  ${BLUE}http://localhost:8181${NC}"
echo ""
echo -e "  停止后端：  kill $BACKEND_PID"
echo -e "  停止前端：  Ctrl+C"
echo ""

npm run dev
