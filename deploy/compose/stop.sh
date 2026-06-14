#!/bin/bash
cd "$(dirname "$0")"
echo "正在停止 KBook..."
docker compose down
echo "✓ 已停止"
