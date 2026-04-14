# KBook - 智能移动阅读平台

前后端分离的移动端阅读应用，支持 TXT/EPUB/PDF 多格式阅读，集成 AI 阅读助手、RAG 图书问答与个性化推荐。

## 功能概览

### 阅读核心
- **多格式阅读器** — EPUB（epubjs）、PDF（pdfjs-dist）、TXT 三种格式，支持阅读主题/字号/行距调节
- **阅读进度同步** — 实时上报 + 离线批量同步，时间戳覆盖冲突解决，跨设备无缝衔接
- **书架管理** — 加入/移除书架，展示阅读进度与最近阅读时间

### AI 智能
- **AI 通用助理** — 基于 LangChain4j 的多轮对话（SSE 流式），支持 @Tool 调用（搜索图书/查书架/查进度等）
- **AI 伴读（RAG）** — 针对单本书的全量内容 RAG 问答，自动生成推荐问题
- **AI 元数据生成** — 自动提取图书标签、评分、8维度相关度得分（年龄段/性别/婚姻/子女/MBTI）
- **AI 视觉 OCR** — 扫描版 PDF 自动调用大模型视觉能力逐页识别文字
- **多模型配置** — 管理后台可配置多个 AI 提供商（Ollama/OpenAI/DeepSeek 等），一键切换

### 个性化推荐
- **用户画像** — 注册时采集年龄/性别/婚姻/子女/MBTI，运行时可更新
- **向量检索召回** — Qdrant 存储图书元数据向量，基于用户画像余弦相似度召回
- **协同过滤** — 基于阅读/收藏/评分/完成行为加权评分，推荐相似用户喜爱的书籍
- **推荐融合** — 向量召回 + 协同过滤 + 热门兜底，多路融合排序

### 社交互动
- **书评系统** — 书籍/章节级评论，支持嵌套回复、点赞、收藏
- **用户关注** — 关注/粉丝体系，用户主页展示在读/已读/书评
- **消息通知** — 评论回复、点赞里程碑等通知，阶梯式推送

### 图书管理（管理员）
- **目录扫描** — SSE 流式扫描本地 EPUB/PDF/TXT 目录，自动解析入库（支持多级目录提取）
- **文件上传** — 手动上传图书文件，自动解析元数据 + AI 标签生成
- **重新解析** — 一键重新提取图书元数据（作者/简介/封面/目录）
- **AI 模型配置** — 多提供商 CRUD、连接测试、一键启用/禁用
- **用户审核** — 邀请注册制，管理员审核/批量操作/封禁/解封

## 项目架构

```
kbook/
├── frontend/                # 前端 - React + TypeScript + Vite
│   ├── src/
│   │   ├── components/      # 通用组件
│   │   │   ├── auth/        # 路由守卫（AuthGuard/AdminGuard/GuestGuard）
│   │   │   ├── book/        # 图书组件（BookChatSheet/AI伴读面板）
│   │   │   ├── layout/      # 布局（TabBar/AppLayout/BlankLayout）
│   │   │   └── ui/          # shadcn/ui 基础组件
│   │   ├── pages/           # 页面
│   │   │   ├── home/        # 首页（统计/推荐/榜单/分类）
│   │   │   ├── rank/        # 排行榜（阅读/评分/新书）
│   │   │   ├── ai/          # AI 助理（多轮对话）
│   │   │   ├── bookshelf/   # 书架
│   │   │   ├── book/        # 图书详情
│   │   │   ├── reader/      # 阅读器（EPUB/PDF/TXT）
│   │   │   ├── search/      # 搜索（ES 全文检索）
│   │   │   ├── reviews/     # 书评
│   │   │   ├── follow/      # 关注/粉丝
│   │   │   ├── notifications/ # 消息通知
│   │   │   ├── profile/     # 个人中心/阅读历史
│   │   │   ├── user/        # 用户主页
│   │   │   ├── admin/       # 管理后台（审核/图书/AI配置）
│   │   │   └── auth/        # 登录/注册/重置密码
│   │   ├── router/          # 路由配置（懒加载+守卫）
│   │   ├── store/           # Zustand 状态管理
│   │   ├── api/             # API 接口层
│   │   ├── types/           # TS 类型定义
│   │   └── utils/           # 工具函数（Axios封装）
│   └── vite.config.ts       # PWA + 代理配置
│
├── backend/                 # 后端 - Spring Boot 3
│   └── src/main/java/com/kbook/
│       ├── common/          # 统一响应、分页、异常处理
│       ├── config/          # Security、JWT、CORS、Redis、Qdrant、LangChain4j
│       ├── controller/      # REST API 控制器（19个）
│       ├── document/        # Elasticsearch 文档定义
│       ├── entity/          # JPA 实体
│       ├── repository/      # 数据访问层
│       └── service/         # 业务逻辑层
│
└── deploy/                  # 部署配置
    ├── docker-compose.yml   # 全栈容器编排
    └── nginx/               # Nginx 反向代理配置
```

## 技术选型

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| React + TypeScript | 19.2 / 5.9 | UI 框架 |
| Vite | 7.2 | 构建工具 |
| Tailwind CSS + shadcn/ui | 3.4 | 样式组件库 |
| React Router DOM | 7.14 | 路由 |
| Zustand | 5.0 | 状态管理 |
| Axios | 1.15 | HTTP 客户端（拦截器 + Token 刷新） |
| React Hook Form + Zod | 7.70 / 4.3 | 表单校验 |
| epubjs | 0.3.93 | EPUB 阅读器 |
| pdfjs-dist | 5.6 | PDF 阅读器 |
| Recharts | 2.15 | 图表 |
| Lucide React | 0.562 | 图标库 |
| vite-plugin-pwa | 1.2 | PWA 离线支持 |

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.3.5 | 应用框架 |
| Java | 17 | 运行时 |
| Spring Security + JWT | — | 无状态认证 |
| Spring Data JPA + Hibernate | — | ORM |
| LangChain4j | — | AI 对话/RAG/Embedding |
| epublib-core | 3.1 | EPUB 解析 |
| Apache PDFBox | — | PDF 解析/渲染/OCR |
| Maven | — | 构建工具 |

### 基础设施

| 组件 | 技术 | 用途 |
|------|------|------|
| 关系数据库 | MySQL 8 + HikariCP | 主数据存储 |
| 缓存 | Redis 7 (Lettuce) | 验证码/会话/推荐缓存 |
| 全文检索 | Elasticsearch 8.12 | 图书搜索/高亮/建议 |
| 向量数据库 | Qdrant v1.12.4 | 图书元数据向量 + RAG 内容向量 |
| 邮件 | QQ SMTP (SSL 465) | 验证码/邀请函/通知 |
| 反向代理 | Nginx (Alpine) | SSL/压缩/限流/SSE/静态资源 |
| 容器化 | Docker Compose | 全栈部署 |

## 核心模块说明

### 后端服务层

| 服务 | 职责 |
|------|------|
| `AuthService` | 认证：验证码发送/校验、双模式登录（密码+验证码）、注册、Token 刷新、密码管理 |
| `BookParserService` | 解析：EPUB/PDF/TXT 元数据提取、封面生成、AI 标签/评分/相关度生成、RAG 内容提取 |
| `BookChatService` | 图书问答：基于 RAG 向量检索的书籍深度问答、推荐问题生成 |
| `AiChatService` | AI 对话：通用多轮对话、@Tool 调用、会话管理 |
| `AiProviderConfigService` | AI 配置：多提供商动态构建 ChatModel/Assistant、连接测试 |
| `EmbeddingService` | 向量：图书元数据 Embedding + RAG 内容分块 Embedding → Qdrant |
| `RecommendService` | 推荐：用户画像向量召回 + 协同过滤 + 热门兜底融合 |
| `BookScanService` | 扫描：SSE 流式扫描本地目录、自动解析入库（幂等）、补生成缺失数据 |
| `ReadingProgressService` | 进度：上报/批量同步/统计，时间戳覆盖冲突解决 |
| `CommentService` | 评论：书籍/章节评论、嵌套回复、点赞/收藏、通知触发 |
| `NotificationService` | 通知：评论回复、点赞里程碑等阶梯式通知 |
| `UserFollowService` | 关注：关注/粉丝体系 |
| `EmailNotificationService` | 邮件：验证码、邀请函、审核通知 |

### 后端配置层

| 配置 | 职责 |
|------|------|
| `SecurityConfig` | JWT 无状态认证、接口权限、CSRF 禁用 |
| `JwtAuthenticationFilter` | Token 提取/校验/SecurityContext 注入 |
| `ChatModelFactory` | 动态构建 ChatModel（Ollama/OpenAI 兼容） |
| `LangChain4jConfig` | 默认 AiAssistant Bean 构建 |
| `QdrantConfig` | Qdrant gRPC 客户端初始化 |
| `RedisConfig` | JSON + String 双序列化策略 |
| `CorsConfig` | 跨域白名单 |
| `DataInitializer` | 首次启动自动创建管理员 |

### 安全策略

- **JWT 无状态认证** — Access Token 2h + Refresh Token 7d，401 自动刷新
- **验证码安全** — 点击图形验证码 + 邮箱验证码双重校验，60s 限频 + 每日 10 次 + 5 分钟过期
- **密码加密** — BCrypt
- **接口权限** — @PreAuthorize 角色校验，SecurityConfig 路径级别控制
- **API Key 脱敏** — 管理端 AI 配置返回时自动脱敏
- **Token 黑名单** — 登出时加入 Redis 黑名单

## 环境变量

所有敏感配置通过环境变量注入，支持 `.env` 文件或 Docker Compose 环境变量：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_HOST/PORT/DB/USER/PASSWORD` | localhost:3306/kbook-dev | MySQL 数据库 |
| `REDIS_HOST/PORT/PASSWORD/DB` | localhost:6379/123456/4 | Redis 缓存 |
| `ES_URIS/USERNAME/PASSWORD` | http://localhost:9201 | Elasticsearch |
| `QDRANT_HOST/PORT/API_KEY` | localhost:6334 | Qdrant 向量库 |
| `JWT_SECRET` | test | JWT 签名密钥（生产必须更换） |
| `AI_BASE_URL/AI_MODEL` | http://localhost:11434 / gemma4:e2b | 兜底 AI 模型 |
| `AI_EMBEDDING_MODEL` | qwen3-embedding:0.6b | Embedding 模型 |
| `AI_VISION_MODEL/AI_VISION_TIMEOUT` | — / 600s | PDF OCR 视觉模型 |
| `MAIL_USERNAME/PASSWORD` | — | QQ 邮箱 SMTP |
| `BOOK_PATH_EPUB/PDF/TXT` | G:/图书/epub|pdf|txt | 图书文件存储路径 |
| `BOOK_COVER_PATH` | G:/图书/covers | 封面图片存储路径 |
| `KBOOK_ADMIN_EMAIL/PASSWORD` | admin@kbook.com / admin123456 | 初始管理员 |
| `NOTIFICATION_BASE_URL` | https://book.keiskei.top | 站点域名 |

## 开发启动

### 前置依赖

- Node.js 18+、Java 17、Maven 3.8+
- MySQL 8、Redis 7、Elasticsearch 8.12、Qdrant v1.12+

### 本地开发

```bash
# 前端
cd frontend && npm install && npm run dev

# 后端
cd backend && mvn spring-boot:run
```

### Docker 部署

```bash
# 构建前端
cd frontend && npm run build

# 启动全部服务
cd deploy && docker compose up -d
```

Docker Compose 包含：MySQL、Redis、Elasticsearch、Qdrant、后端 Spring Boot、Nginx（前端静态资源 + 反向代理）。

## API 概览

| 模块 | 路径前缀 | 权限 | 核心接口 |
|------|----------|------|----------|
| 认证 | `/api/auth` | 公开/认证 | 登录、注册、验证码、Token 刷新、密码重置 |
| 首页 | `/api/home` | 认证 | 聚合数据（统计/推荐/榜单/分类） |
| 图书 | `/api/books` | 公开/认证 | 搜索、详情、排行、封面、评分、文件流式读取 |
| 图书问答 | `/api/books/{id}/chat` | 认证 | RAG 流式问答、推荐问题、历史 |
| AI 助理 | `/api/ai` | 认证 | 多轮对话（SSE）、会话管理 |
| 书架 | `/api/bookshelf` | 认证 | CRUD、检查、计数 |
| 进度 | `/api/progress` | 认证 | 上报、批量同步、统计 |
| 推荐 | `/api/recommend` | 认证/管理 | 个性化推荐、缓存清除、向量重建 |
| 评论 | `/api/comments` | 公开/认证 | 书评/章节评、回复、点赞、收藏 |
| 通知 | `/api/notifications` | 认证 | 列表、未读数、标记已读 |
| 用户 | `/api/user` | 认证 | 个人信息、头像、画像更新 |
| 用户主页 | `/api/user-profile` | 公开 | 用户主页、在读/已读、书评 |
| 关注 | `/api/follow` | 公开/认证 | 关注/取关、关注列表、粉丝列表 |
| 管理员 | `/api/admin` | ADMIN | 用户审核、邀请注册、邮箱绑定 |
| 图书管理 | `/api/books/admin` | ADMIN | 扫描、上传、重新解析 |
| AI 配置 | `/api/admin/ai-provider` | ADMIN | 多模型 CRUD、启用/禁用、连接测试 |
| 验证码 | `/api/captcha` | 公开 | 点击图形验证码生成/验证 |
| 健康检查 | `/api/health` | 公开 | 服务状态 |

## License

Private - All Rights Reserved
