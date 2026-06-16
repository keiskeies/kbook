#!/bin/bash
# ============================================================
# KBook Update Script — Docker Compose Mode
# 
# What it does:
#   1. git pull latest code
#   2. Download pre-built package from remote
#   3. Rebuild app image ONLY (MySQL/Redis/ES/Qdrant untouched)
#   4. Redeploy app container
#
# Usage:
#   cd deploy/compose && bash update.sh
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

cd "$(dirname "$0")"
REPO_ROOT="$(cd ../.. && pwd)"
VERSION=$(cat ../VERSION)

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  KBook Update - Docker Compose${NC}"
echo -e "${BLUE}  Target Version: ${GREEN}${VERSION}${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""

# ---- 1. Prerequisites ----
echo -e "${YELLOW}[1/6] Checking prerequisites...${NC}"
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker not installed${NC}"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo -e "${RED}docker compose not installed${NC}"; exit 1; }
echo -e "  Docker: ${GREEN}OK${NC}"

if command -v git >/dev/null 2>&1; then
    GIT_OK=1
    echo -e "  git: ${GREEN}OK${NC}"
else
    GIT_OK=0
    echo -e "  git: ${YELLOW}NOT FOUND (will skip pull)${NC}"
fi

# ---- 2. git pull ----
echo -e "${YELLOW}[2/6] Pulling latest code...${NC}"
if [ "$GIT_OK" = "1" ]; then
    cd "$REPO_ROOT"
    if ! git pull; then
        echo -e "${YELLOW}WARNING: git pull failed - check network or resolve conflicts${NC}"
        echo "Continuing with existing code..."
    fi
    cd "$(dirname "$0")"
else
    echo "  Skipped (git not available)"
fi
echo -e "${GREEN}OK${NC}"

# ---- 3. Download artifacts ----
echo -e "${YELLOW}[3/6] Downloading latest build artifacts...${NC}"

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

# Validate frontend extraction
if [ ! -f "$PKG_DIR/dist/index.html" ]; then
    echo -e "${RED}ERROR: frontend extraction failed - dist/index.html not found${NC}"
    echo "  Check: ${BASE_URL}/frontend.zip"
    exit 1
fi

# ---- 4. Backup current image ----
echo -e "${YELLOW}[4/6] Tagging current image as backup...${NC}"
docker tag kbook-app:latest kbook-app:backup 2>/dev/null || true
echo -e "  Backup: ${GREEN}kbook-app:backup${NC}"

# ---- 5. Build app ONLY ----
echo -e "${YELLOW}[5/6] Building app image kbook-app:${VERSION} ...${NC}"
if ! docker compose build app; then
    echo ""
    echo -e "${RED}============================================${NC}"
    echo -e "${RED}  BUILD FAILED!${NC}"
    echo -e "${RED}  Rollback: docker compose up -d app${NC}"
    echo -e "${RED}============================================${NC}"
    exit 1
fi
docker tag kbook-app:latest "kbook-app:${VERSION}"
echo -e "  Image: ${GREEN}kbook-app:${VERSION}${NC}"

# ---- 6. Redeploy app ONLY (--no-deps protects infra) ----
echo -e "${YELLOW}[6/6] Redeploying app container (infra untouched)...${NC}"
docker compose up -d --no-deps --force-recreate app
echo -e "  Container: ${GREEN}restarted${NC}"

# ---- Cleanup ----
echo ""
echo "Cleaning up old images..."
docker image prune -f >/dev/null 2>&1

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  Update complete!  Version: ${VERSION}${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "  Check status : ${YELLOW}docker compose ps${NC}"
echo -e "  View logs    : ${YELLOW}docker compose logs -f app${NC}"
echo -e "  Rollback     : ${YELLOW}docker compose up -d app${NC}"
echo ""
