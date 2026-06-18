#!/bin/bash
# ============================================================
# KBook 下载构建产物（Linux / Mac）
# 从 PACKAGE_URL 下载 app.jar 和 frontend.zip 到 deploy/package/
# 被 start.sh / build.sh 在 package 缺失时自动调用
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

cd "$(dirname "$0")"
BASE_URL=$(cat PACKAGE_URL)
PKG_DIR="package"

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  下载 KBook 构建产物                      ${NC}"
echo -e "${BLUE}============================================${NC}"

mkdir -p "$PKG_DIR"

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
$DL "$PKG_DIR/app.jar" "$BASE_URL/app.jar"
echo -e "${GREEN}✓ app.jar${NC}"

# 下载 frontend.zip 并解压
echo -e "${YELLOW}[2/2] 下载并解压 frontend.zip...${NC}"
$DL "$PKG_DIR/frontend.zip" "$BASE_URL/frontend.zip"
rm -rf "$PKG_DIR/dist"
unzip -q "$PKG_DIR/frontend.zip" -d "$PKG_DIR/dist"
rm -f "$PKG_DIR/frontend.zip"
echo -e "${GREEN}✓ dist/${NC}"

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  下载完成！${NC}"
echo -e "${GREEN}============================================${NC}"
ls -lh "$PKG_DIR/"
