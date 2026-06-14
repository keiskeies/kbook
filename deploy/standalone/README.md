# KBook 本地开发（无 Docker）

适用于开发者在本地直接启动前后端进行调试，不依赖 Docker。

## 前置条件

确保已安装并启动以下服务：

| 服务 | 版本 | 默认端口 | 备注 |
|------|------|----------|------|
| MySQL | 8.4 | 3306 | 需创建数据库 `kbook-dev` |
| Redis | 7+ | 6379 | 需设置密码 `123456` |
| Elasticsearch | 8.13 | 9200 | 单节点模式 |
| Qdrant | v1.12 | 6334 | gRPC 端口 |
| Java | 17 | — | JDK 17+ |
| Node.js | 18+ | — | 含 npm |
| Maven | 3.8+ | — | — |

## 快速启动

```bash
# Windows：双击 standalone\start.bat
# Linux/Mac：
bash deploy/standalone/start.sh
```

## 分别启动

### 后端

```bash
cd backend
mvn spring-boot:run
# 或先构建再运行
mvn package -DskipTests
java -jar target/kbook-server-1.0.0.jar
```

后端默认端口 **8181**，API 文档见各 Controller。

### 前端

```bash
cd frontend
npm install
npm run dev
```

前端默认端口 **15173**，自动代理 `/api` 到 `localhost:8181`。

## 配置管理

AI 配置文件位置：`backend/src/main/resources/config/ai-config.json`

开发环境下直接修改这个文件，然后在管理后台页面点击「重载」即可生效，
无需重启服务。
