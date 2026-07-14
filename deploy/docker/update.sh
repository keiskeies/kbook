#!/bin/bash
# ============================================================
# KBook Update Script — Standalone Docker Mode
# 
# What it does:
#   1. git pull latest code
#   2. Download pre-built package from remote
#   3. Build new Docker image with version tag
#   4. Stop old container, start new one
#   5. Infra (MySQL/Redis/ES/Qdrant) untouched — must be running separately
#
# Usage:
#   cd deploy/docker && bash update.sh
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

cd "$(dirname "$0")"
SCRIPT_DIR="$(pwd)"
REPO_ROOT="$(cd ../.. && pwd)"
VERSION=$(cat ../VERSION)
ENV_FILE=".env"

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  KBook Update - Standalone Docker${NC}"
echo -e "${BLUE}  Target Version: ${GREEN}${VERSION}${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""

# ---- 1. Prerequisites ----
echo -e "${YELLOW}[1/7] Checking prerequisites...${NC}"
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker not installed${NC}"; exit 1; }
echo -e "  Docker: ${GREEN}OK${NC}"

if command -v git >/dev/null 2>&1; then
    GIT_OK=1
    echo -e "  git: ${GREEN}OK${NC}"
else
    GIT_OK=0
    echo -e "  git: ${YELLOW}NOT FOUND (will skip pull)${NC}"
fi

if [ ! -f "$ENV_FILE" ]; then
    echo -e "${RED}.env not found in deploy/docker/${NC}"
    echo "  Copy from .env.example and configure"
    exit 1
fi
echo -e "  .env: ${GREEN}OK${NC}"

# ---- 2. git pull ----
echo -e "${YELLOW}[2/7] Pulling latest code...${NC}"
if [ "$GIT_OK" = "1" ]; then
    cd "$REPO_ROOT"
    if ! git pull; then
        echo -e "${YELLOW}WARNING: git pull failed - continuing with existing code${NC}"
    fi
    cd "$SCRIPT_DIR"
else
    echo "  Skipped"
fi
echo -e "${GREEN}OK${NC}"

# ---- 3. Download artifacts ----
echo -e "${YELLOW}[3/7] Downloading latest build artifacts...${NC}"

BASE_URL=$(cat ../PACKAGE_URL)
PKG_DIR="../package"
mkdir -p "$PKG_DIR"

# Backup old artifacts with timestamp
TS=$(date +%Y%m%d_%H%M%S)
if [ -f "$PKG_DIR/app.jar" ]; then
    echo "  Backing up old app.jar -> app.jar.$TS"
    mv "$PKG_DIR/app.jar" "$PKG_DIR/app.jar.$TS"
fi
if [ -d "$PKG_DIR/dist" ]; then
    echo "  Backing up old dist/ -> dist.$TS/"
    mv "$PKG_DIR/dist" "$PKG_DIR/dist.$TS"
fi

if command -v curl >/dev/null 2>&1; then
    DL="curl -fSL --connect-timeout 30 --max-time 600 -o"
elif command -v wget >/dev/null 2>&1; then
    DL="wget -q --timeout=600 -O"
else
    echo -e "${RED}curl or wget required${NC}"
    exit 1
fi

echo "  Downloading app.jar..."
$DL "$PKG_DIR/app.jar" "$BASE_URL/app.jar"
echo -e "  app.jar: ${GREEN}OK${NC}"

# Validate app.jar (min 1MB)
JAR_SIZE=$(wc -c < "$PKG_DIR/app.jar" 2>/dev/null || echo 0)
if [ "$JAR_SIZE" -lt 1048576 ]; then
    echo -e "${RED}ERROR: app.jar is too small (${JAR_SIZE} bytes) - not a valid build!${NC}"
    echo "  Check: ${BASE_URL}/app.jar"
    exit 1
fi

echo "  Downloading frontend.zip..."
$DL "$PKG_DIR/frontend.zip" "$BASE_URL/frontend.zip"
rm -rf "$PKG_DIR/dist"
unzip -q "$PKG_DIR/frontend.zip" -d "$PKG_DIR/dist"
rm -f "$PKG_DIR/frontend.zip"
echo -e "  dist/: ${GREEN}OK${NC}"

# Validate frontend.zip was extracted (check dist/index.html exists)
if [ ! -f "$PKG_DIR/dist/index.html" ]; then
    echo -e "${RED}ERROR: frontend extraction failed - dist/index.html not found${NC}"
    echo "  The downloaded frontend.zip may not be a valid build."
    echo "  Check: ${BASE_URL}/frontend.zip"
    exit 1
fi

# ---- 4. Load .env ----
echo -e "${YELLOW}[4/7] Loading configuration...${NC}"
set -a; source "$ENV_FILE"; set +a
echo -e "${GREEN}OK${NC}"

# ---- 5. Build new image ----
echo -e "${YELLOW}[5/7] Building image kbook-app:${VERSION} ...${NC}"
if ! docker build -t "kbook-app:${VERSION}" -f ../Dockerfile ..; then
    echo -e "${RED}Build failed${NC}"
    echo "  Rollback: docker start kbook-app"
    exit 1
fi
echo -e "  Image: ${GREEN}kbook-app:${VERSION}${NC}"

# ---- 6. Stop old container ----
echo -e "${YELLOW}[6/7] Stopping old container...${NC}"
docker stop kbook-app 2>/dev/null || true
docker rm kbook-app 2>/dev/null || true
echo -e "  Old container: ${GREEN}removed${NC}"

# ---- 7. Start new container ----
echo -e "${YELLOW}[7/7] Starting new container kbook-app:${VERSION} ...${NC}"

# Docker container must use host.docker.internal for host services
MYSQL_HOST=host.docker.internal
REDIS_HOST=host.docker.internal
QDRANT_HOST=host.docker.internal
ES_URIS="${ES_URIS//localhost/host.docker.internal}"

# Defaults
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

# Resolve volume paths to absolute
resolve_path() {
    case "$1" in
        /*) echo "$1" ;;
        [A-Za-z]:*) echo "$1" ;;
        *) echo "$SCRIPT_DIR/$1" ;;
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

docker run -d \
    --name kbook-app \
    --restart unless-stopped \
    --add-host host.docker.internal:host-gateway \
    -p "${APP_PORT_HOST}:80" \
    -e TZ="${TZ}" \
    -e SPRING_PROFILES_ACTIVE="${SPRING_PROFILE}" \
    -e KBOOK_CORS_ORIGINS="${KBOOK_CORS_ORIGINS}" \
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

if [ $? -ne 0 ]; then
    echo -e "${RED}Container start failed${NC}"
    echo "  Rollback: docker start kbook-app (previous version)"
    exit 1
fi
echo -e "  Container: ${GREEN}started${NC}"

# ---- Done ----
echo ""
echo "Cleaning up old images..."
docker image prune -f >/dev/null 2>&1

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  Update complete!  Version: ${VERSION}${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "  Visit : ${BLUE}http://localhost:${APP_PORT_HOST}${NC}"
echo -e "  Logs  : ${YELLOW}docker logs -f kbook-app${NC}"
echo -e "  Stop  : ${YELLOW}./stop.sh${NC}"
echo ""
