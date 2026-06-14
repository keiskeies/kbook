#!/bin/bash
# ============================================================
# KBook Kubernetes 部署脚本（Linux / Mac）
# 使用：./k8s/deploy.sh
# 前置条件：已配置 kubectl 可访问目标集群
# ============================================================

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

cd "$(dirname "$0")"

echo -e "${YELLOW}[1/6] 检查 kubectl...${NC}"
command -v kubectl >/dev/null 2>&1 || { echo -e "${RED}请先安装 kubectl${NC}"; exit 1; }
echo -e "${GREEN}✓ kubectl 已就绪${NC}"

echo -e "${YELLOW}[2/6] 创建命名空间...${NC}"
kubectl apply -f 07-namespace.yaml
echo -e "${GREEN}✓ 命名空间已创建${NC}"

echo -e "${YELLOW}[3/6] 部署 ConfigMap 和 Secret...${NC}"
kubectl apply -f 01-configmap.yaml
echo -e "${YELLOW}⚠ 请先编辑 02-secrets.yaml 中的密码和密钥，然后执行：${NC}"
echo "  kubectl apply -f 02-secrets.yaml"
echo -e "${YELLOW}按回车继续部署基础设施...${NC}"
read -r

echo -e "${YELLOW}[4/6] 部署基础设施（MySQL、Redis、ES、Qdrant）...${NC}"
kubectl apply -f 03-infra.yaml
echo -e "${GREEN}✓ 基础设施部署中（创建 PVC 和拉取镜像需要几分钟）${NC}"

echo -e "${YELLOW}[5/6] 部署应用...${NC}"
kubectl apply -f 06-nginx-configmap.yaml
kubectl apply -f 04-app.yaml
echo -e "${GREEN}✓ 应用已部署${NC}"

echo -e "${YELLOW}[6/6] 部署 Ingress...${NC}"
kubectl apply -f 05-ingress.yaml
echo -e "${GREEN}✓ Ingress 已部署${NC}"

echo -e "\n${GREEN}============================================${NC}"
echo -e "${GREEN}  KBook K8s 部署完成！${NC}"
echo -e "${GREEN}============================================${NC}"
echo -e "  查看状态：${YELLOW}kubectl get pods -n kbook${NC}"
echo -e "  查看日志：${YELLOW}kubectl logs -n kbook deploy/backend${NC}"
echo -e "  清理部署：${YELLOW}./k8s/destroy.sh${NC}"
