#!/bin/bash
# ============================================================
# KBook 单容器镜像构建（Linux / Mac）
# 使用：
#   1. 运行项目根目录 package.sh 生成 package/
#   2. ./build.sh
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
cd "$(dirname "$0")"
VERSION=$(cat ../VERSION)

echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  KBook Docker 镜像构建                     ${NC}"
echo -e "${BLUE}  Version: ${VERSION}                       ${NC}"
echo -e "${BLUE}============================================${NC}"

# 1. Docker
echo -e "${YELLOW}[1/3] 检查 Docker...${NC}"
command -v docker >/dev/null 2>&1 || { echo -e "${RED}请安装 Docker${NC}"; exit 1; }
echo -e "${GREEN}✓${NC}"

# 2. Check package artifacts
echo -e "${YELLOW}[2/3] 检查构建产物...${NC}"
if [ ! -f "../package/app.jar" ]; then
    echo -e "${RED}未找到 ../package/app.jar${NC}"
    echo "请先运行项目根目录的 package.sh 打包后端"
    exit 1
fi
if [ ! -d "../package/dist" ]; then
    echo -e "${RED}未找到 ../package/dist/${NC}"
    echo "请先运行项目根目录的 package.sh 打包前端"
    exit 1
fi
echo -e "${GREEN}✓${NC}"

# 3. Build
echo -e "${YELLOW}[3/3] 构建镜像 kbook-app:${VERSION} ...${NC}"
docker build -t "kbook-app:${VERSION}" -f ../Dockerfile ..
echo -e "${GREEN}✓${NC}"

echo -e "\n${GREEN}============================================${NC}"
echo -e "${GREEN}  构建成功！kbook-app:${VERSION}${NC}"
echo -e "${GREEN}============================================${NC}"
echo -e "  运行：${YELLOW}./start.sh${NC}"
