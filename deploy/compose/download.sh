#!/bin/bash
# ============================================================
# KBook 下载脚本（Linux / Mac）
# 从网站下载 app.jar 和 frontend.zip 到 deploy/package/
# 供 Docker Compose 构建时使用。
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

BASE_URL="https://book.keiskei.top/package"
DEPLOY_PACKAGE_DIR="$(cd "$(dirname "$0")/.." && pwd)/package"

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  下载 KBook 构建产物                      ${NC}"
echo -e "${BLUE}============================================${NC}"

# 创建目录
mkdir -p "$DEPLOY_PACKAGE_DIR"

# 优先用 curl，否则用 wget
if command -v curl >/dev/null 2>&1; then
    DL="curl -fSL -o"
elif command -v wget >/dev/null 2>&1; then
    DL="wget -q -O"
else
    echo -e "${RED}请安装 curl 或 wget${NC}"
    exit 1
fi

# 下载 app.jar
echo -e "${YELLOW}[1/2] 下载 app.jar...${NC}"
$DL "$DEPLOY_PACKAGE_DIR/app.jar" "$BASE_URL/app.jar"
echo -e "${GREEN}✓ app.jar${NC}"

# 下载 frontend.zip 并解压
echo -e "${YELLOW}[2/2] 下载并解压 frontend.zip...${NC}"
$DL "$DEPLOY_PACKAGE_DIR/frontend.zip" "$BASE_URL/frontend.zip"
rm -rf "$DEPLOY_PACKAGE_DIR/dist"
unzip -q "$DEPLOY_PACKAGE_DIR/frontend.zip" -d "$DEPLOY_PACKAGE_DIR/dist"
rm -f "$DEPLOY_PACKAGE_DIR/frontend.zip"
echo -e "${GREEN}✓ dist/${NC}"

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  下载完成！${NC}"
echo -e "${GREEN}============================================${NC}"
ls -lh "$DEPLOY_PACKAGE_DIR/"