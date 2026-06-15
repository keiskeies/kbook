# ============================================================
# KBook 单容器镜像 — 纯 COPY，无构建阶段
#
# 使用方式：
#   Docker Compose: cd deploy/compose && docker compose up -d --build
#   手动构建:       docker build -t kbook-app:1.0.8 .
#   K8s:            docker build -t kbook-app:1.0.8 -f Dockerfile .
#
# 构建前先运行 package.bat / package.sh 生成 package/ 目录
# ============================================================

FROM nginx:alpine

# JRE（后端运行环境）
RUN apk add --no-cache openjdk17-jre-headless && rm -rf /var/cache/apk/*

# kbook 用户
RUN addgroup -S kbook && adduser -S kbook -G kbook

# ---- COPY 构建产物 ----
COPY package/dist /usr/share/nginx/html
COPY package/app.jar /app/app.jar
COPY ai-config.json /app/config/ai-config.json

# ---- COPY 运行时配置 ----
COPY compose/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /app/start.sh
RUN chmod +x /app/start.sh

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:80/api/health || exit 1

EXPOSE 80

ENTRYPOINT ["/app/start.sh"]
