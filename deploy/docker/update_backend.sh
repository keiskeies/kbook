#!/bin/bash
# ============================================================
# KBook Backend Hot Update
#   Copy app.jar into running container and restart it.
#   Java is PID 1 (started by entrypoint), so container must be
#   restarted to load the new jar.
#
# Usage:
#   cd deploy/docker && bash update_backend.sh
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

cd "$(dirname "$0")"
VERSION=$(cat ../VERSION 2>/dev/null || echo unknown)
PKG_DIR="../package"
JAR_PATH="$PKG_DIR/app.jar"

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  KBook Backend Update (Hot)${NC}"
echo -e "${BLUE}  Target Version: ${GREEN}${VERSION}${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""

# ---- 1. Prerequisites ----
echo -e "${YELLOW}[1/6] Checking prerequisites...${NC}"
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

# ---- 2. Prepare app.jar (remote first, then local) ----
echo -e "${YELLOW}[2/6] Preparing backend artifact...${NC}"

jar_ok=0

if [ -f "$JAR_PATH" ]; then
    JAR_SIZE=$(wc -c < "$JAR_PATH")
    if [ "$JAR_SIZE" -ge 1048576 ]; then
        echo -e "  Using local build: ${GREEN}$JAR_PATH${NC}"
        jar_ok=1
    else
        echo -e "  Local app.jar too small, will re-download..."
        rm -f "$JAR_PATH"
    fi
fi

if [ "$jar_ok" = "0" ]; then
    echo "  Downloading from remote..."
    BASE_URL=$(cat ../PACKAGE_URL)
    mkdir -p "$PKG_DIR"

    if command -v curl >/dev/null 2>&1; then
        DL="curl -fSL --connect-timeout 30 --max-time 600 -o"
    elif command -v wget >/dev/null 2>&1; then
        DL="wget -q --timeout=600 -O"
    else
        echo -e "${RED}curl or wget required${NC}"; exit 1
    fi

    $DL "$JAR_PATH" "$BASE_URL/app.jar"
fi

# ---- Validate downloaded jar ----
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${RED}ERROR: app.jar download failed!${NC}"
    exit 1
fi

JAR_SIZE=$(wc -c < "$JAR_PATH")
if [ "$JAR_SIZE" -lt 1048576 ]; then
    echo -e "${RED}ERROR: app.jar is too small (${JAR_SIZE} bytes) - not a valid build!${NC}"
    exit 1
fi

echo -e "  app.jar: ${GREEN}OK${NC}"

# ---- 3. Backup current jar from container ----
echo -e "${YELLOW}[3/6] Backing up current app.jar in container...${NC}"
TS=$(date +%Y%m%d_%H%M%S)
if docker cp kbook-app:/app/app.jar "$PKG_DIR/app.jar.in_container.$TS" >/dev/null 2>&1; then
    echo -e "  Backup: ${GREEN}$PKG_DIR/app.jar.in_container.$TS${NC}"
else
    echo -e "  ${YELLOW}Backup skipped (container may be fresh)${NC}"
fi

# ---- 4. Copy new jar into container ----
echo -e "${YELLOW}[4/6] Copying new app.jar to container...${NC}"
docker cp "$JAR_PATH" kbook-app:/app/app.jar
echo -e "  Copied: ${GREEN}OK${NC}"

# ---- 5. Restart container (Java is PID 1, must restart to load new jar) ----
echo -e "${YELLOW}[5/6] Restarting container kbook-app...${NC}"
docker restart kbook-app
echo -e "  Container: ${GREEN}restarted${NC}"

# ---- 6. Wait for health (up to 60s) ----
echo -e "${YELLOW}[6/6] Waiting for backend to start (up to 60s)...${NC}"
WAIT=0
while [ $WAIT -lt 60 ]; do
    sleep 3
    WAIT=$((WAIT + 3))
    if docker exec kbook-app wget -qO- http://127.0.0.1:8181/api/health >/dev/null 2>&1; then
        echo -e "  Backend healthy after ${GREEN}${WAIT}s${NC}"
        break
    fi
    echo "  Waiting... (${WAIT}s)"
done

if [ $WAIT -ge 60 ]; then
    echo -e "  ${YELLOW}WARNING: Backend not healthy after 60s${NC}"
    echo "  Check logs: docker logs -f kbook-app"
fi

# ---- Done ----
echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  Backend update complete!  Version: ${VERSION}${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "  Logs : ${YELLOW}docker logs -f kbook-app${NC}"
echo -e "  Stop : ${YELLOW}./stop.sh${NC}"
