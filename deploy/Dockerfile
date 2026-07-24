# ============================================================
# KBook 单容器镜像 — 纯 COPY，无构建阶段
#
# 使用方式：
#   Docker Compose: cd deploy/compose && bash update.sh    (一键更新)
#   手动构建:       docker build -t kbook-app:$(cat VERSION) .
#   K8s:            cd deploy/k8s && bash update.sh         (一键更新)
#
# 构建前先运行 package.bat / package.sh 生成 package/ 目录
# 或使用 update 脚本自动从网络下载预构建包
# ============================================================

FROM nginx:alpine

# JRE（后端运行环境）
# 注意：必须用完整 JRE 而非 headless，因为 BookParserService 用 ImageIO 写 JPEG
# 需要 libjavajpeg.so（headless 版不含此本地库，会导致 PDF OCR 时 UnsatisfiedLinkError）
RUN apk add --no-cache openjdk17-jre && rm -rf /var/cache/apk/*

# kbook 用户
RUN addgroup -S kbook && adduser -S kbook -G kbook

# ---- COPY 构建产物 ----
COPY package/dist /usr/share/nginx/html
COPY package/app.jar /app/app.jar
COPY ai-config.json /app/config/ai-config.json

# ---- COPY 运行时配置 ----
COPY compose/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /app/start.sh
RUN sed -i 's/\r$//' /app/start.sh && chmod +x /app/start.sh

# 健康检查
# 用 127.0.0.1 避免 IPv6 解析问题（localhost 可能解析到 ::1，nginx 只监听 IPv4）
# 直接检查 Spring Boot 8181 端口，绕过 nginx 反代，更准确反映应用健康
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8181/api/health || exit 1

EXPOSE 80

ENTRYPOINT ["/app/start.sh"]
