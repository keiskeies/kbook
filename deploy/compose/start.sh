#!/bin/bash
# ============================================================
# KBook Docker Compose 启动（Linux / Mac）
# 使用：
#   1. 先执行 ../package.sh 生成 package/
#   2. cp ../.env.example .env 并编辑
#   3. ./start.sh
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
cd "$(dirname "$0")"

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  KBook Docker 启动                        ${NC}"
echo -e "${BLUE}============================================${NC}"

# 1. Docker
echo -e "${YELLOW}[1/5] 检查 Docker...${NC}"
command -v docker >/dev/null 2>&1 || { echo -e "${RED}请安装 Docker${NC}"; exit 1; }
command -v docker compose >/dev/null 2>&1 || { echo -e "${RED}请安装 docker compose${NC}"; exit 1; }
echo -e "${GREEN}✓${NC}"

# 2. .env
echo -e "${YELLOW}[2/5] 检查 .env...${NC}"
if [ ! -f ".env" ]; then
    [ -f "../.env.example" ] && cp ../.env.example .env
    echo -e "${RED}.env 不存在，已从 .env.example 创建，请编辑后重试${NC}"
    echo "  必改：JWT_SECRET / MYSQL_PASSWORD / REDIS_PASSWORD / KBOOK_ADMIN_PASSWORD"
    exit 1
fi
echo -e "${GREEN}✓${NC}"

# 3. package（从远程下载）
echo -e "${YELLOW}[3/5] 下载 package/...${NC}"
if [ ! -f "../package/app.jar" ] || [ ! -d "../package/dist" ]; then
    echo -e "${YELLOW}从远程下载构建产物...${NC}"
    bash download.sh
fi
echo -e "${GREEN}✓${NC}"

# 4. 数据目录
echo -e "${YELLOW}[4/5] 创建数据目录...${NC}"
source .env 2>/dev/null || true
for d in \
    "${BOOK_PATH_EPUB_HOST:-../data/books/epub}" \
    "${BOOK_PATH_PDF_HOST:-../data/books/pdf}" \
    "${BOOK_PATH_TXT_HOST:-../data/books/txt}" \
    "${COVER_PATH_HOST:-../data/covers}" \
    "${AVATAR_DIR_HOST:-../data/avatars}" \
    "${CHAT_DIR_HOST:-../data/chat}" \
    "${TTS_CACHE_DIR_HOST:-../data/tts_cache}" \
    "${MYSQL_DATA_DIR:-../data/mysql}" \
    "${REDIS_DATA_DIR:-../data/redis}" \
    "${ES_DATA_DIR:-../data/es}" \
    "${QDRANT_DATA_DIR:-../data/qdrant}"; do
    mkdir -p "$d" 2>/dev/null || true
done
[ -d "${ES_DATA_DIR:-../data/es}" ] && chown -R 1000:1000 "${ES_DATA_DIR:-../data/es}" 2>/dev/null || true
echo -e "${GREEN}✓${NC}"

# 5. 启动
echo -e "${YELLOW}[5/5] 启动服务...${NC}"
docker compose up -d --build

echo -e "\n${GREEN}============================================${NC}"
echo -e "${GREEN}  KBook 已启动！${NC}"
echo -e "${GREEN}============================================${NC}"
echo -e "  访问：${BLUE}http://localhost:${APP_PORT_HOST:-80}${NC}"
echo -e "  管理员：${KBOOK_ADMIN_EMAIL:-admin@kbook.com}"
echo -e "\n  停止：${YELLOW}./stop.sh${NC}  |  日志：${YELLOW}docker compose logs -f${NC}"
