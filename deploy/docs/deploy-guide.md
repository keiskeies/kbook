# KBook 部署指南

---

## 目录

1. [部署方式选择](#1-部署方式选择)
2. [方式一：Docker Compose（推荐新手）](#2-方式一docker-compose推荐新手)
3. [方式二：Kubernetes](#3-方式二kubernetes)
4. [方式三：本地开发（无 Docker）](#4-方式三本地开发无-docker)
5. [AI 配置管理](#5-ai-配置管理)
6. [常见问题](#6-常见问题)
7. [安全清单](#7-安全清单)

---

## 1. 部署方式选择

| 方式 | 难度 | 适用场景 |
|------|------|----------|
| **Docker Compose** | ⭐ 低 | 新手、单机部署 — 前后端合一容器 + 基础设施，一键启动 |
| **Kubernetes** | ⭐⭐⭐ 中高 | 生产集群、高可用 — 多副本、自动扩缩容 |
| **本地开发** | ⭐⭐ 中 | 开发者调试 — 不依赖 Docker，直接运行前后端 |

---

## 2. 方式一：Docker Compose（推荐新手）

### 文件结构

```
deploy/
├── .dockerignore          ← 构建上下文过滤（排除源码/数据）
├── Dockerfile             ← 单容器镜像：COPY 前构建产物 + entrypoint 启动
├── docker-entrypoint.sh   ← 容器入口：启动 nginx + java 后端
├── .env.example           ← 配置模板（复制到 compose/.env 后修改）
├── ai-config.json         ← AI 外部配置（可直接编辑，点"重载"生效）
│
├── package.sh             ← Linux/Mac：构建前端 dist + 后端 JAR → package/
├── package.bat            ← Windows：同上
├── package/               ← 构建产物（由 package.sh/bat 生成）
│   ├── dist/              ←   前端静态文件
│   ├── app.jar            ←   后端 JAR
│   └── ai-config.json     ←   复制的 AI 配置
│
├── compose/               ← Docker Compose 部署（推荐新手）
│   ├── .env               ←   你的配置（由 ../.env.example 复制）
│   ├── docker-compose.yml ←   容器编排：1 个应用容器 + 4 个基础设施
│   ├── nginx.conf         ←   Nginx 反向代理（/api/ → 内部 8181）
│   ├── start.bat          ←   Windows 一键启动
│   ├── start.sh           ←   Linux/Mac 一键启动
│   ├── stop.bat / stop.sh
│   └── restart.bat / restart.sh
│
├── k8s/                   ← Kubernetes 部署
├── standalone/            ← 本地开发（无 Docker）
└── docs/deploy-guide.md   ← 本文档
```

### 环境要求

- **Docker Desktop**（Windows/Mac）或 **Docker Engine**（Linux）+ `docker compose` 插件
- **内存**：最低 4GB，推荐 8GB+（本地跑 AI 模型建议 16GB+）
- **Node.js 18+** 和 **Maven 3.8+**（仅打包阶段需要，Docker 内无需）
- 网络畅通（需拉取 Docker 镜像和 npm 包）

### 一键部署步骤

```bash
# 1. 进入部署目录
cd deploy

# 2. 打包前端 + 后端（生成 package/ 目录）
./package.sh              # Linux/Mac
package.bat               # Windows（双击）
# 此步骤会自动 npm install → npm run build → mvn package → 复制到 package/

# 3. 进入 compose 目录，复制配置模板
cd compose
cp ../.env.example .env          # Linux/Mac
copy ..\.env.example .env        # Windows

# 4. 编辑 .env ⚠️ 必须修改以下配置：
#    JWT_SECRET → 随机字符串（Linux: openssl rand -base64 32）
#    MYSQL_PASSWORD / REDIS_PASSWORD / KBOOK_ADMIN_PASSWORD → 强密码
#    AI_PROVIDER / AI_BASE_URL / AI_MODEL → 你的 AI 服务地址

# 5. 一键启动
./start.sh                # Linux/Mac
start.bat                 # Windows（双击）

# 6. 浏览器访问 http://localhost
#    管理员登录：admin@kbook.com / 你在 .env 中设置的密码
```

> ⏱ 首次启动耗时 3-10 分钟（下载镜像），打包阶段另外耗时。
> ⚠️ 后续更新代码只需要重新运行 package.sh/bat，然后 `docker compose restart app`。

### 核心配置说明

> AI 模型供应商（对话模型 / 嵌入模型）现已迁移至数据库管理，部署启动后请登录管理后台 → AI 配置管理 添加配置。首次启动会自动创建默认占位配置，请务必修改为实际值。

| 参数 | 必须改 | 说明 |
|------|--------|------|
| `JWT_SECRET` | ✅ | 至少 32 字符随机字符串，用于令牌签名 |
| `MYSQL_PASSWORD` | ✅ | MySQL root 密码，16 位以上 |
| `REDIS_PASSWORD` | ✅ | Redis 密码，16 位以上 |
| `KBOOK_ADMIN_PASSWORD` | ✅ | 管理员密码，12 位以上 |
| `AI_VISION_MODEL` | 按需 | PDF OCR 视觉模型（留空则自动使用对话模型） |
| `AI_VISION_TIMEOUT` | 按需 | 视觉模型超时（默认 `600s`） |
| `BOOK_PATH_EPUB_HOST` | 按需 | 宿主机上的 EPUB 图书目录 |
| `APP_PORT_HOST` | 按需 | 前端端口（默认 80，被占则改 8080） |

### 常用命令

```bash
# 启动
docker compose up -d

# 停止（保留数据）
docker compose down

# 查看日志
docker compose logs -f          # 所有服务
docker compose logs -f app      # 只看应用

# 重启应用
docker compose restart app

# 完全清理（⚠️ 删除所有数据）
docker compose down -v
```

### 架构说明

```
用户 → :80(Nginx) → /api/ → :8181(Spring Boot)
                  → /*    → 前端静态文件
```

- **只暴露 80 端口**：Nginx 监听 80，后端 8181 只在容器内部可访问
- **AI 配置热加载**：修改 `ai-config.json` 后登录管理后台 → 点「重载」即可，无需重启
- **图书自动扫描**：后端启动时自动扫描图书目录中的 EPUB/PDF/TXT 并入库

---

## 3. 方式二：Kubernetes

### 前置条件

- kubectl 可访问目标集群
- Ingress Controller（如 nginx-ingress）

### 部署

```bash
# 1. 编辑 k8s/02-secrets.yaml 填入密码和密钥
# 2. 一键部署
./k8s/deploy.sh          # Linux/Mac
k8s\deploy.bat           # Windows

# 3. 查看状态
kubectl get pods -n kbook -w
```

### 清理

```bash
./k8s/destroy.sh         # Linux/Mac
k8s\destroy.bat          # Windows
```

### AI 配置更新

```bash
kubectl create configmap kbook-ai-config \
  --from-file=ai-config.json=../ai-config.json -n kbook \
  -o yaml --dry-run=client | kubectl apply -f -

kubectl exec deploy/backend -n kbook -- \
  curl -X POST http://localhost:8181/api/admin/ai-config/reload
```

---

## 4. 方式三：本地开发（无 Docker）

详见 `deploy/standalone/README.md`。

```bash
bash deploy/standalone/start.sh     # Linux/Mac
deploy\standalone\start.bat         # Windows
```

前端：http://localhost:15173  
后端：http://localhost:8181

---

## 5. AI 配置管理

### 文件位置

| 部署方式 | 配置文件路径 |
|----------|-------------|
| Docker Compose | `deploy/ai-config.json`（宿主机上直接编辑） |
| Kubernetes | 通过 ConfigMap 注入，更新方式见上方 |
| 本地开发 | `backend/src/main/resources/config/ai-config.json` |

### 修改流程

1. 编辑 `ai-config.json`（修改对话风格、角色 prompt 等）
2. 以管理员登录 KBook
3. 进入管理后台，点击「重载 AI 配置」
4. 立即生效，无需重启服务

### 配置结构

```json
{
  "bookChat": {
    "defaultStyle": "DEEP",
    "styles": [
      { "key": "EASY", "name": "随和", "title": "轻松随意" },
      { "key": "DEEP", "name": "深度", "title": "深刻严谨" },
      { "key": "CONCISE", "name": "简洁", "title": "言简意赅" },
      { "key": "HUMOR", "name": "幽默", "title": "风趣幽默" }
    ]
  },
  "roundTable": {
    "host": { "key": "HOST", "name": "主持人", ... },
    "settings": { "maxRolesPerSession": 12, ... },
    "roles": [ ... ]  // 41 个角色
  },
  "debate": {
    "host": { "key": "HOST", "name": "主持人", ... },
    "personalities": [ ... ]  // 16 个辩手性格
  }
}
```

---

## 6. 常见问题

### Q1：启动后 http://localhost 显示 404

```bash
cd frontend && npm install && npm run build
cd ../deploy && docker compose restart app
```

### Q2：后端一直重启

```bash
docker compose logs -f app
# 常见原因：MySQL 密码不匹配 / ES 内存不足 / 端口冲突
```

### Q3：AI 对话没反应

```bash
# 检查 AI 服务
curl http://host.docker.internal:11434/api/tags
# 确认 .env 中 AI_PROVIDER / AI_BASE_URL / AI_MODEL 正确
# 修改后：docker compose restart app
```

### Q4：Windows Docker 报磁盘共享错误

Docker Desktop → Settings → Resources → File Sharing → 添加项目所在盘符

### Q5：80 端口被占用

```ini
# .env 中修改
APP_PORT_HOST=8080
# 然后重新启动
docker compose up -d
```

---

## 7. 安全清单

### 🚨 部署前必做

- [ ] JWT_SECRET 已改为随机字符串
- [ ] MYSQL_PASSWORD 已改为强密码（16 位+）
- [ ] REDIS_PASSWORD 已改为强密码（16 位+）
- [ ] KBOOK_ADMIN_PASSWORD 已改为强密码（12 位+）
- [ ] .env 未提交到 Git
- [ ] Linux: `chmod 600 .env`

### ⚠️ 生产环境建议

- [ ] 配置 HTTPS
- [ ] 启用 ES 认证
- [ ] 云服务 API Key 妥善保管
- [ ] 定期备份 MySQL 数据
