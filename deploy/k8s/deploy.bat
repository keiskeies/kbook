@echo off
chcp 65001 >nul
title KBook K8s 部署
cd /d "%~dp0"

echo [1/6] 检查 kubectl...
kubectl version --client >nul 2>&1
if errorlevel 1 (
    echo ✗ 请先安装 kubectl
    pause
    exit /b 1
)
echo ✓ kubectl 已就绪

echo [2/6] 创建命名空间...
kubectl apply -f 07-namespace.yaml
echo ✓ 命名空间已创建

echo [3/6] 部署 ConfigMap...
kubectl apply -f 01-configmap.yaml
echo.
echo ⚠ 请先编辑 02-secrets.yaml 中的密码和密钥，然后手动执行：
echo   kubectl apply -f 02-secrets.yaml
pause

echo [4/6] 部署基础设施...
kubectl apply -f 03-infra.yaml
echo ✓ 基础设施部署中（创建 PVC 和拉取镜像需要几分钟）

echo [5/6] 部署应用...
kubectl apply -f 06-nginx-configmap.yaml
kubectl apply -f 04-app.yaml
echo ✓ 应用已部署

echo [6/6] 部署 Ingress...
kubectl apply -f 05-ingress.yaml
echo ✓ Ingress 已部署

echo.
echo ============================================
echo   KBook K8s 部署完成！
echo ============================================
echo   查看状态：kubectl get pods -n kbook
pause
