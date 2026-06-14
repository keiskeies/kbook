#!/bin/bash
# KBook K8s 清理脚本
# 使用：./k8s/destroy.sh
# ⚠️ 会删除所有资源（包括 PVC 中的数据）！

cd "$(dirname "$0")"
echo "⚠️  正在清理 KBook K8s 资源..."
echo "    这将删除所有 Pod、Service、PVC 及其数据！"
echo -n "确认删除？(y/N): "
read -r confirm
[ "$confirm" != "y" ] && [ "$confirm" != "Y" ] && echo "已取消" && exit 0

kubectl delete -f 05-ingress.yaml --ignore-not-found
kubectl delete -f 04-app.yaml --ignore-not-found
kubectl delete -f 06-nginx-configmap.yaml --ignore-not-found
kubectl delete -f 03-infra.yaml --ignore-not-found
kubectl delete -f 02-secrets.yaml --ignore-not-found
kubectl delete -f 01-configmap.yaml --ignore-not-found
kubectl delete -f 07-namespace.yaml --ignore-not-found

echo "✓ KBook K8s 资源已清理"
