@echo off
chcp 65001 >nul
title KBook K8s 清理
cd /d "%~dp0"

echo ⚠ 正在清理 KBook K8s 资源...
echo   这将删除所有 Pod、Service、PVC 及其数据！
set /p confirm="确认删除？(y/N): "
if /i not "%confirm%"=="y" (
    echo 已取消
    pause
    exit /b 0
)

kubectl delete -f 05-ingress.yaml --ignore-not-found
kubectl delete -f 04-app.yaml --ignore-not-found
kubectl delete -f 06-nginx-configmap.yaml --ignore-not-found
kubectl delete -f 03-infra.yaml --ignore-not-found
kubectl delete -f 02-secrets.yaml --ignore-not-found
kubectl delete -f 01-configmap.yaml --ignore-not-found
kubectl delete -f 07-namespace.yaml --ignore-not-found

echo ✓ KBook K8s 资源已清理
pause
