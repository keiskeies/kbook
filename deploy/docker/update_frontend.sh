#!/bin/bash
# ============================================================
# KBook Frontend Hot Update
#   Copy dist/ into running container's nginx html dir.
#   nginx serves static files directly - no container restart needed.
#
# Usage:
#   cd deploy/docker && bash update_frontend.sh
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

cd "$(dirname "$0")"
VERSION=$(cat ../VERSION 2>/dev/null || echo unknown)
PKG_DIR="../package"
DIST_DIR="$PKG_DIR/dist"

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  KBook Frontend Update (Hot)${NC}"
echo -e "${BLUE}  Target Version: ${GREEN}${VERSION}${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""

# ---- 1. Prerequisites ----
echo -e "${YELLOW}[1/5] Checking prerequisites...${NC}"
command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker not installed${NC}"; exit 1; }
echo -e "  Docker: ${GREEN}OK${NC}"

if [ "$(docker inspect --format '{{.State.Running}}' kbook-app 2>/dev/null)" != "true" ]; then
    echo -e "${RED}Container kbook-app is not running${NC}"
    echo "  Start it first: ./start.sh"
    exit 1
fi
echo -e "  Container kbook-app: ${GREEN}running${NC}"

[ -f ".env" ] || { echo -e "${RED}.env not found in deploy/docker/${NC}"; exit 1; }
echo -e "  .env: ${GREEN}OK${NC}"

# ---- 2. Prepare frontend artifacts (local first, then remote) ----
echo -e "${YELLOW}[2/5] Preparing frontend artifacts...${NC}"
if [ -f "$DIST_DIR/index.html" ]; then
    echo -e "  Using local build: ${GREEN}$DIST_DIR${NC}"
else
    echo "  Local dist not found, downloading from remote..."
    BASE_URL=$(cat ../PACKAGE_URL)
    mkdir -p "$PKG_DIR"

    if command -v curl >/dev/null 2>&1; then
        DL="curl -fSL --connect-timeout 30 --max-time 600 -o"
    elif command -v wget >/dev/null 2>&1; then
        DL="wget -q --timeout=600 -O"
    else
        echo -e "${RED}curl or wget required${NC}"; exit 1
    fi

    echo "  Downloading frontend.zip..."
    rm -rf "$DIST_DIR"
    $DL "$PKG_DIR/frontend.zip" "$BASE_URL/frontend.zip"

    # Validate size (min 100KB)
    ZIP_SIZE=$(wc -c < "$PKG_DIR/frontend.zip" 2>/dev/null || echo 0)
    if [ "$ZIP_SIZE" -lt 102400 ]; then
        echo -e "${RED}ERROR: frontend.zip is too small (${ZIP_SIZE} bytes) - not a valid build!${NC}"
        exit 1
    fi

    unzip -q "$PKG_DIR/frontend.zip" -d "$DIST_DIR"
    rm -f "$PKG_DIR/frontend.zip"

    [ -f "$DIST_DIR/index.html" ] || { echo -e "${RED}frontend extraction failed - index.html not found${NC}"; exit 1; }
    echo -e "  dist: ${GREEN}OK${NC}"
fi

# ---- 3. Backup current frontend from container ----
echo -e "${YELLOW}[3/5] Backing up current frontend in container...${NC}"
TS=$(date +%Y%m%d_%H%M%S)
if docker cp kbook-app:/usr/share/nginx/html "$PKG_DIR/html.backup.$TS" >/dev/null 2>&1; then
    echo -e "  Backup: ${GREEN}$PKG_DIR/html.backup.$TS${NC}"
else
    echo -e "  ${YELLOW}Backup skipped (container may be fresh)${NC}"
fi

# ---- 4. Clear and copy new frontend ----
echo -e "${YELLOW}[4/5] Updating frontend in container...${NC}"
docker exec kbook-app sh -c 'rm -rf /usr/share/nginx/html/* /usr/share/nginx/html/.[!.]*'
docker cp "$DIST_DIR/." kbook-app:/usr/share/nginx/html/
echo -e "  Frontend copied: ${GREEN}OK${NC}"

# ---- 5. Reload nginx (safety net; static files don't require it) ----
echo -e "${YELLOW}[5/5] Reloading nginx...${NC}"
if docker exec kbook-app nginx -s reload >/dev/null 2>&1; then
    echo -e "  nginx: ${GREEN}reloaded${NC}"
else
    echo -e "  ${YELLOW}nginx reload skipped${NC}"
fi

# ---- Done ----
echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  Frontend update complete!  Version: ${VERSION}${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "  If UI not refreshed, hard-refresh browser (Ctrl+F5)"
echo ""
