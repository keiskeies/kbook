#!/bin/bash
# ============================================================
# KBook Update Script — Kubernetes Mode
# 
# What it does:
#   1. git pull latest code
#   2. Download pre-built package from remote
#   3. Build & push backend image
#   4. Rolling update backend deployment (zero-downtime)
#   5. Update frontend static files via PVC
#   6. MySQL/Redis/ES/Qdrant are NOT touched
#
# Usage:
#   cd deploy/k8s && bash update.sh
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

cd "$(dirname "$0")"
SCRIPT_DIR="$(pwd)"
REPO_ROOT="$(cd ../.. && pwd)"
VERSION=$(cat ../VERSION)
K8S_NAMESPACE="kbook"

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  KBook Update - Kubernetes${NC}"
echo -e "${BLUE}  Target Version: ${GREEN}${VERSION}${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""
echo -e "  ${YELLOW}This updates ONLY backend + frontend.${NC}"
echo -e "  ${YELLOW}MySQL / Redis / ES / Qdrant are NOT touched.${NC}"
echo ""

# ---- 1. Prerequisites ----
echo -e "${YELLOW}[1/6] Checking prerequisites...${NC}"
command -v kubectl >/dev/null 2>&1 || { echo -e "${RED}kubectl not installed${NC}"; exit 1; }
echo -e "  kubectl: ${GREEN}OK${NC}"

command -v docker >/dev/null 2>&1 || { echo -e "${RED}Docker not installed${NC}"; exit 1; }
echo -e "  Docker: ${GREEN}OK${NC}"

if command -v git >/dev/null 2>&1; then
    GIT_OK=1
    echo -e "  git: ${GREEN}OK${NC}"
else
    GIT_OK=0
    echo -e "  git: ${YELLOW}NOT FOUND (will skip pull)${NC}"
fi

kubectl get namespace "$K8S_NAMESPACE" >/dev/null 2>&1 || {
    echo -e "${RED}kbook namespace not found - run deploy.sh first${NC}"
    exit 1
}
echo -e "  namespace kbook: ${GREEN}OK${NC}"

# ---- 2. git pull ----
echo -e "${YELLOW}[2/6] Pulling latest code...${NC}"
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

# ---- 4. Build & push backend image ----
echo -e "${YELLOW}[4/6] Building backend image kbook-backend:${VERSION} ...${NC}"
docker build -t "kbook-backend:${VERSION}" -f ../Dockerfile ..
echo -e "  Image: ${GREEN}kbook-backend:${VERSION}${NC}"

echo ""
echo -e "  ${YELLOW}If using remote registry, push the image now:${NC}"
echo "    docker tag kbook-backend:${VERSION} your-registry/kbook-backend:${VERSION}"
echo "    docker push your-registry/kbook-backend:${VERSION}"
echo "    Then update 04-app.yaml image field."
echo ""
echo -e "  ${YELLOW}For local cluster (minikube/kind/docker-desktop), continuing...${NC}"

# ---- 5. Update backend deployment (ROLLING UPDATE) ----
echo -e "${YELLOW}[5/6] Updating backend deployment (rolling update)...${NC}"
kubectl set image deployment/backend "backend=kbook-backend:${VERSION}" -n "$K8S_NAMESPACE"

echo "  Waiting for backend rollout..."
if ! kubectl rollout status deployment/backend -n "$K8S_NAMESPACE" --timeout=120s; then
    echo ""
    echo -e "${YELLOW}WARNING: Backend rollout timed out - check status:${NC}"
    echo "  kubectl get pods -n $K8S_NAMESPACE"
    echo "  kubectl describe deployment backend -n $K8S_NAMESPACE"
fi
echo -e "  Backend: ${GREEN}updated${NC}"

# ---- 6. Update frontend static files ----
echo -e "${YELLOW}[6/6] Updating frontend static files...${NC}"

FRONTEND_POD=$(kubectl get pods -n "$K8S_NAMESPACE" -l app=frontend -o jsonpath="{.items[0].metadata.name}" 2>/dev/null || echo "")

if [ -z "$FRONTEND_POD" ]; then
    echo -e "${YELLOW}WARNING: No frontend pod found - skipping frontend update${NC}"
    echo "  Deploy with: kubectl apply -f 04-app.yaml"
else
    echo "  Pod: $FRONTEND_POD"
    # Remove old static files and copy new ones
    kubectl exec -n "$K8S_NAMESPACE" "$FRONTEND_POD" -- sh -c "rm -rf /usr/share/nginx/html/*" 2>/dev/null || true
    kubectl cp "$PKG_DIR/dist/." "$K8S_NAMESPACE/$FRONTEND_POD:/usr/share/nginx/html/" 2>/dev/null || {
        echo -e "${YELLOW}WARNING: Frontend file copy had issues${NC}"
        echo "  You may need to restart: kubectl rollout restart deployment/frontend -n $K8S_NAMESPACE"
    }
    # Reload nginx
    kubectl exec -n "$K8S_NAMESPACE" "$FRONTEND_POD" -- nginx -s reload 2>/dev/null || true
    echo -e "  Frontend: ${GREEN}updated${NC}"
fi

# ---- Done ----
echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  Update complete!  Version: ${VERSION}${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo -e "  Check status : ${YELLOW}kubectl get pods -n $K8S_NAMESPACE${NC}"
echo -e "  View logs    : ${YELLOW}kubectl logs -n $K8S_NAMESPACE deploy/backend${NC}"
echo -e "  Rollback     : ${YELLOW}kubectl rollout undo deployment/backend -n $K8S_NAMESPACE${NC}"
echo ""
