#!/bin/sh
# ============================================================
# KBook 容器入口
# 1. 启动 nginx（后台 daemon）
# 2. 启动 Java 后端（前台，PID 1）
# ============================================================
set -e

echo "Starting nginx..."
nginx

echo "Starting KBook backend..."
exec java \
    -Xms256m -Xmx512m \
    -XX:+UseG1GC \
    -XX:+HeapDumpOnOutOfMemoryError \
    -Dfile.encoding=UTF-8 \
    -Dsun.jnu.encoding=UTF-8 \
    -Djava.security.egd=file:/dev/./urandom \
    -Dkbook.ai-config.path=/app/config/ai-config.json \
    -jar /app/app.jar
