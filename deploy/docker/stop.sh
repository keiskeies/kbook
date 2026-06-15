#!/bin/bash
cd "$(dirname "$0")"
echo "正在停止 KBook 容器..."
docker stop kbook-app 2>/dev/null
docker rm kbook-app 2>/dev/null
echo "✓ 已停止"
