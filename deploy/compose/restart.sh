#!/bin/bash
cd "$(dirname "$0")"
docker compose restart && echo "✓ 已重启" || echo "✗ 失败"
