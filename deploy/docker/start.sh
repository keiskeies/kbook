#!/bin/bash
# ============================================================
# KBook 单容器一键构建+启动（Linux / Mac）
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
cd "$(dirname "$0")"
ENV_FILE=".env"
VERSION=$(cat ../VERSION)

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  KBook 单容器启动                          ${NC}"
echo -e "${BLUE}  Version: ${VERSION}                       ${NC}"
echo -e "${BLUE}============================================${NC}"

# 1. Docker
echo -e "${YELLOW}[1/6] 检查 Docker...${NC}"
command -v docker >/dev/null 2>&1 || { echo -e "${RED}请安装 Docker${NC}"; exit 1; }
echo -e "${GREEN}✓${NC}"

# 2. .env
echo -e "${YELLOW}[2/6] 检查 .env...${NC}"
if [ ! -f ".env" ]; then
    if [ -f ".env.example" ]; then
        cp .env.example .env
        echo -e "${RED}.env 不存在，已从 .env.example 创建，请编辑后重新运行${NC}"
        echo "  必改：JWT_SECRET / MYSQL_PASSWORD / REDIS_PASSWORD / KBOOK_ADMIN_PASSWORD"
    else
        echo -e "${RED}未找到 .env.example${NC}"
    fi
    exit 1
fi
echo -e "${GREEN}✓${NC}"

# 3. Check package (auto-download if missing)
echo -e "${YELLOW}[3/6] 检查构建产物...${NC}"
if [ ! -f "../package/app.jar" ] || [ ! -d "../package/dist" ]; then
    echo -e "${YELLOW}本地未找到构建产物，从远程下载...${NC}"
    bash ../download.sh
fi
echo -e "${GREEN}✓${NC}"

# 4. 创建数据目录
echo -e "${YELLOW}[4/6] 创建数据目录...${NC}"
for d in \
    "../data/books/epub" "../data/books/pdf" "../data/books/txt" \
    "../data/covers" "../data/avatars" "../data/chat" "../data/tts_cache"; do
    mkdir -p "$d" 2>/dev/null || true
done
echo -e "${GREEN}✓${NC}"

# 5. 构建镜像
echo -e "${YELLOW}[5/6] 构建镜像 kbook-app:${VERSION} ...${NC}"
docker build -t "kbook-app:${VERSION}" -f ../Dockerfile ..
echo -e "${GREEN}✓${NC}"

# 6. 启动
echo -e "${YELLOW}[6/6] 启动容器...${NC}"

# 停止旧容器
docker rm -f kbook-app 2>/dev/null || true

# ---- 加载 .env ----
set -a; source "$ENV_FILE"; set +a

# ---- Docker 容器必须用 host.docker.internal，替换 .env 中可能出现的 localhost ----
MYSQL_HOST=host.docker.internal
REDIS_HOST=host.docker.internal
QDRANT_HOST=host.docker.internal
# ES_URIS 是完整 URL，只替换 host 部分，保留端口
ES_URIS="${ES_URIS//localhost/host.docker.internal}"

# ---- 默认值 ----
: "${APP_PORT_HOST:=80}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_DB:=kbook-dev}"
: "${MYSQL_USER:=root}"
: "${REDIS_PORT:=6379}"
: "${REDIS_DB:=4}"
: "${QDRANT_PORT:=6334}"
: "${AI_VISION_TIMEOUT:=600s}"
: "${TZ:=Asia/Shanghai}"
: "${SPRING_PROFILE:=prod}"
: "${AI_VISION_MODEL:=}"
: "${AI_CONFIG_HOST:=../ai-config.json}"

# ---- Volume 路径转绝对路径 ----
resolve_path() {
    case "$1" in
        /*) echo "$1" ;;            # Unix absolute
        [A-Za-z]:*) echo "$1" ;;    # Windows absolute
        *) echo "$(pwd)/$1" ;;      # relative → script dir + path
    esac
}

BOOK_PATH_EPUB=$(resolve_path "${BOOK_PATH_EPUB:-../data/books/epub}")
BOOK_PATH_PDF=$(resolve_path "${BOOK_PATH_PDF:-../data/books/pdf}")
BOOK_PATH_TXT=$(resolve_path "${BOOK_PATH_TXT:-../data/books/txt}")
BOOK_COVER_PATH=$(resolve_path "${BOOK_COVER_PATH:-../data/covers}")
AVATAR_DIR=$(resolve_path "${AVATAR_DIR:-../data/avatars}")
CHAT_DIR=$(resolve_path "${CHAT_DIR:-../data/chat}")
TTS_CACHE_DIR=$(resolve_path "${TTS_CACHE_DIR:-../data/tts_cache}")
AI_CONFIG_HOST=$(resolve_path "${AI_CONFIG_HOST:-../ai-config.json}")

# ---- 启动 ----
docker run -d \
    --name kbook-app \
    --restart unless-stopped \
    --add-host host.docker.internal:host-gateway \
    -p "${APP_PORT_HOST}:80" \
    -e TZ="${TZ}" \
    -e SPRING_PROFILES_ACTIVE="${SPRING_PROFILE}" \
    -e MYSQL_HOST="${MYSQL_HOST}" \
    -e MYSQL_PORT="${MYSQL_PORT}" \
    -e MYSQL_DB="${MYSQL_DB}" \
    -e MYSQL_USER="${MYSQL_USER}" \
    -e MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
    -e REDIS_HOST="${REDIS_HOST}" \
    -e REDIS_PORT="${REDIS_PORT}" \
    -e REDIS_PASSWORD="${REDIS_PASSWORD}" \
    -e REDIS_DB="${REDIS_DB}" \
    -e ES_URIS="${ES_URIS}" \
    -e QDRANT_HOST="${QDRANT_HOST}" \
    -e QDRANT_PORT="${QDRANT_PORT}" \
    -e JWT_SECRET="${JWT_SECRET}" \
    -e KBOOK_ADMIN_EMAIL="${KBOOK_ADMIN_EMAIL}" \
    -e KBOOK_ADMIN_PASSWORD="${KBOOK_ADMIN_PASSWORD}" \
    -e MAIL_USERNAME="${MAIL_USERNAME}" \
    -e MAIL_PASSWORD="${MAIL_PASSWORD}" \
    -e KBOOK_AI_CONFIG_PATH=/app/config/ai-config.json \
    -e AI_VISION_MODEL="${AI_VISION_MODEL}" \
    -e AI_VISION_TIMEOUT="${AI_VISION_TIMEOUT}" \
    -v "${BOOK_PATH_EPUB}:/data/books/epub:ro" \
    -v "${BOOK_PATH_PDF}:/data/books/pdf:ro" \
    -v "${BOOK_PATH_TXT}:/data/books/txt:ro" \
    -v "${BOOK_COVER_PATH}:/data/covers" \
    -v "${AVATAR_DIR}:/data/avatars" \
    -v "${CHAT_DIR}:/data/chat" \
    -v "${TTS_CACHE_DIR}:/data/tts_cache" \
    -v "${AI_CONFIG_HOST}:/app/config/ai-config.json:ro" \
    "kbook-app:${VERSION}"

echo -e "${GREEN}✓${NC}"

echo -e "\n${GREEN}============================================${NC}"
echo -e "${GREEN}  KBook 已启动！${NC}"
echo -e "${GREEN}============================================${NC}"
echo -e "  访问：${BLUE}http://localhost:${APP_PORT_HOST}${NC}"
echo -e "\n  停止：${YELLOW}./stop.sh${NC}  |  日志：${YELLOW}docker logs -f kbook-app${NC}"
