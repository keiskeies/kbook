<p align="center">
  <img src="book-open.svg" alt="KBook" width="64" height="64" />
</p>

<h1 align="center">KBook</h1>

<p align="center">
  <strong>AI-Native Mobile Reading Platform</strong>
</p>

<p align="center">
  融合智能对话 · 向量检索 · 个性推荐的新一代阅读体验
</p>

<p align="center">
  <img src="https://img.shields.io/badge/React-19-blue" alt="React 19" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.3-green" alt="Spring Boot 3.3" />
  <img src="https://img.shields.io/badge/LangChain4j-AI-orange" alt="LangChain4j" />
  <img src="https://img.shields.io/badge/Qdrant-Vector-purple" alt="Qdrant" />
  <img src="https://img.shields.io/badge/PWA-Ready-blueviolet" alt="PWA" />
</p>

---

## Why KBook

传统阅读应用止步于「翻页」。KBook 将 AI 深度融入阅读全链路——从智能推荐你下一本爱书，到随时就书中内容展开对话问答，再到基于用户画像的千人千面推荐引擎，让每一次阅读都更懂你。

---

## Features

### Immersive Reader

- **Multi-Format Engine** — EPUB / PDF / TXT 三格式统一引擎，字体、字号、行距、主题色自由调节
- **Progress Sync** — 实时上报 + 离线批量同步，时间戳覆盖冲突解决，跨设备无缝衔接
- **TTS Read-Aloud** — 文本分片语音合成，播放/暂停/停止一键操控

### AI Intelligence

- **AI Companion Chat** — 基于 LangChain4j 的多轮流式对话，支持 @Tool 调用（搜索图书 / 查书架 / 查进度）
- **RAG Book Q&A** — 针对单本书全量内容的向量检索问答，自动生成推荐问题
- **AI Metadata** — 自动提取标签、评分、八维度相关度（年龄段 / 性别 / 婚姻 / 子女 / MBTI）
- **Vision OCR** — 扫描版 PDF 自动调用大模型视觉能力逐页识别
- **Multi-Model** — 管理后台可配置多个 AI 提供商（Ollama / OpenAI / DeepSeek 等），一键切换

### Personalized Recommendation

- **User Profiling** — 注册采集 + 运行时更新，构建多维阅读画像
- **Vector Recall** — Qdrant 存储图书元数据向量，基于用户画像余弦相似度召回
- **Collaborative Filtering** — 基于阅读 / 收藏 / 评分 / 完成行为加权，推荐相似用户喜爱的书籍
- **Fusion Ranking** — 向量召回 + 协同过滤 + 热门兜底，多路融合排序

### Social & Community

- **Book Reviews** — 书籍 / 章节级评论，嵌套回复、点赞、收藏
- **Follow System** — 关注 / 粉丝体系，用户主页展示在读 / 已读 / 书评
- **Notifications** — 评论回复、点赞里程碑等阶梯式推送

### Admin Console

- **Directory Scan** — SSE 流式扫描本地 EPUB / PDF / TXT 目录，自动解析入库
- **File Upload** — 手动上传图书文件，自动解析元数据 + AI 标签生成
- **Re-Parse** — 一键重新提取图书元数据（作者 / 简介 / 封面 / 目录）
- **AI Provider CRUD** — 多提供商管理、连接测试、一键启用 / 禁用
- **User Audit** — 邀请注册制，管理员审核 / 批量操作 / 封禁 / 解封

---

## Architecture

```
kbook/
├── frontend/                # React 19 + TypeScript + Vite
│   ├── src/
│   │   ├── components/      # UI 组件
│   │   │   ├── auth/        # 路由守卫（AuthGuard / AdminGuard / GuestGuard）
│   │   │   ├── book/        # 图书组件（BookChatSheet / AI 伴读面板）
│   │   │   ├── layout/      # 布局（TabBar / AppLayout / BlankLayout）
│   │   │   ├── reader/      # 阅读器组件（EpubRenderer / PdfRenderer / TxtRenderer / TtsFloatPlayer）
│   │   │   └── ui/          # shadcn/ui 基础组件
│   │   ├── pages/           # 页面
│   │   │   ├── home/        # 首页（统计 / 推荐 / 榜单 / 分类）
│   │   │   ├── rank/        # 排行榜（阅读 / 评分 / 新书）
│   │   │   ├── ai/          # AI 助理（多轮对话）
│   │   │   ├── bookshelf/   # 书架
│   │   │   ├── book/        # 图书详情
│   │   │   ├── reader/      # 阅读器（EPUB / PDF / TXT）
│   │   │   ├── search/      # 搜索（ES 全文检索）
│   │   │   ├── reviews/     # 书评
│   │   │   ├── follow/      # 关注 / 粉丝
│   │   │   ├── notifications/ # 消息通知
│   │   │   ├── profile/     # 个人中心 / 阅读历史
│   │   │   ├── user/        # 用户主页
│   │   │   ├── admin/       # 管理后台（审核 / 图书 / AI 配置）
│   │   │   └── auth/        # 登录 / 注册 / 重置密码
│   │   ├── router/          # 路由配置（懒加载 + 守卫）
│   │   ├── store/           # Zustand 状态管理
│   │   ├── api/             # API 接口层
│   │   ├── types/           # TS 类型定义
│   │   └── utils/           # 工具函数（Axios 封装）
│   └── vite.config.ts       # PWA + 代理配置
│
├── backend/                 # Spring Boot 3
│   └── src/main/java/com/kbook/
│       ├── common/          # 统一响应、分页、异常处理
│       ├── config/          # Security / JWT / CORS / Redis / Qdrant / LangChain4j
│       ├── controller/      # REST API 控制器
│       ├── document/        # Elasticsearch 文档定义
│       ├── entity/          # JPA 实体
│       ├── repository/      # 数据访问层
│       └── service/         # 业务逻辑层
│
└── deploy/                  # 部署配置
    ├── docker-compose.yml   # 全栈容器编排
    └── nginx/               # Nginx 反向代理配置
```

---

## Tech Stack

### Frontend

| Tech | Version | Purpose |
|------|---------|---------|
| React + TypeScript | 19.2 / 5.9 | UI Framework |
| Vite | 7.2 | Build Tool |
| Tailwind CSS + shadcn/ui | 3.4 | Styling & Components |
| React Router DOM | 7.14 | Routing |
| Zustand | 5.0 | State Management |
| Axios | 1.15 | HTTP Client (Interceptor + Token Refresh) |
| React Hook Form + Zod | 7.70 / 4.3 | Form Validation |
| epubjs | 0.3.93 | EPUB Renderer |
| pdfjs-dist | 5.6 | PDF Renderer |
| Recharts | 2.15 | Charts |
| Lucide React | 0.562 | Icon Library |
| vite-plugin-pwa | 1.2 | PWA Offline Support |

### Backend

| Tech | Version | Purpose |
|------|---------|---------|
| Spring Boot | 3.3.5 | Application Framework |
| Java | 17 | Runtime |
| Spring Security + JWT | — | Stateless Auth |
| Spring Data JPA + Hibernate | — | ORM |
| LangChain4j | — | AI Chat / RAG / Embedding |
| epublib-core | 3.1 | EPUB Parsing |
| Apache PDFBox | — | PDF Parsing / Rendering / OCR |
| Maven | — | Build Tool |

### Infrastructure

| Component | Tech | Purpose |
|-----------|------|---------|
| RDBMS | MySQL 8 + HikariCP | Primary Data Store |
| Cache | Redis 7 (Lettuce) | Captcha / Session / Recommendation Cache |
| Search | Elasticsearch 8.12 | Full-Text Search / Highlight / Suggestion |
| Vector DB | Qdrant v1.12.4 | Book Metadata Vectors + RAG Content Vectors |
| Mail | QQ SMTP (SSL 465) | Captcha / Invitation / Notification |
| Reverse Proxy | Nginx (Alpine) | SSL / Compression / Rate Limit / SSE / Static |
| Container | Docker Compose | Full-Stack Deployment |

---

## Security

- **JWT Stateless Auth** — Access Token 2h + Refresh Token 7d，401 Auto-Refresh
- **Captcha** — Click-to-verify image captcha + email code dual verification，60s rate limit + 10/day + 5min TTL
- **Password** — BCrypt hashing
- **Authorization** — `@PreAuthorize` role check + `SecurityConfig` path-level control
- **API Key Masking** — AI provider keys auto-masked in admin responses
- **Token Blacklist** — Logout tokens added to Redis blacklist

---

## Environment Variables

All sensitive configs are injected via environment variables (`.env` file or Docker Compose):

| Variable | Default | Description |
|----------|---------|-------------|
| `MYSQL_HOST/PORT/DB/USER/PASSWORD` | localhost:3306/kbook-dev | MySQL |
| `REDIS_HOST/PORT/PASSWORD/DB` | localhost:6379/123456/4 | Redis |
| `ES_URIS/USERNAME/PASSWORD` | http://localhost:9201 | Elasticsearch |
| `QDRANT_HOST/PORT/API_KEY` | localhost:6334 | Qdrant Vector DB |
| `JWT_SECRET` | test | JWT Signing Key (**change in production**) |
| `AI_BASE_URL/AI_MODEL` | http://localhost:11434 / gemma4:e2b | Default AI Model |
| `AI_EMBEDDING_MODEL` | qwen3-embedding:0.6b | Embedding Model |
| `AI_VISION_MODEL/AI_VISION_TIMEOUT` | — / 600s | PDF OCR Vision Model |
| `MAIL_USERNAME/PASSWORD` | — | QQ Mail SMTP |
| `BOOK_PATH_EPUB/PDF/TXT` | G:/图书/epub\|pdf\|txt | Book File Paths |
| `BOOK_COVER_PATH` | G:/图书/covers | Cover Image Path |
| `KBOOK_ADMIN_EMAIL/PASSWORD` | admin@kbook.com / admin123456 | Initial Admin |
| `NOTIFICATION_BASE_URL` | https://book.keiskei.top | Site Domain |

---

## Quick Start

### Prerequisites

- Node.js 18+, Java 17, Maven 3.8+
- MySQL 8, Redis 7, Elasticsearch 8.12, Qdrant v1.12+

### Local Development

```bash
# Frontend
cd frontend && npm install && npm run dev

# Backend
cd backend && mvn spring-boot:run
```

### Docker Deployment

```bash
# Build frontend
cd frontend && npm run build

# Launch all services
cd deploy && docker compose up -d
```

Docker Compose includes: MySQL, Redis, Elasticsearch, Qdrant, Spring Boot Backend, Nginx (static assets + reverse proxy).

---

## API Overview

| Module | Prefix | Auth | Key Endpoints |
|--------|--------|------|---------------|
| Auth | `/api/auth` | Public / Auth | Login, Register, Captcha, Token Refresh, Password Reset |
| Home | `/api/home` | Auth | Aggregated Data (Stats / Recommendations / Ranks / Categories) |
| Books | `/api/books` | Public / Auth | Search, Detail, Rank, Cover, Rating, File Stream |
| Book Q&A | `/api/books/{id}/chat` | Auth | RAG Streaming Q&A, Suggested Questions, History |
| AI Assistant | `/api/ai` | Auth | Multi-turn Chat (SSE), Session Management |
| Bookshelf | `/api/bookshelf` | Auth | CRUD, Check, Count |
| Progress | `/api/progress` | Auth | Report, Batch Sync, Stats |
| Recommend | `/api/recommend` | Auth / Admin | Personalized Recommendations, Cache Clear, Vector Rebuild |
| Comments | `/api/comments` | Public / Auth | Book/Chapter Reviews, Replies, Likes, Bookmarks |
| Notifications | `/api/notifications` | Auth | List, Unread Count, Mark Read |
| User | `/api/user` | Auth | Profile, Avatar, User Profile Update |
| User Profile | `/api/user-profile` | Public | User Page, Reading / Read, Reviews |
| Follow | `/api/follow` | Public / Auth | Follow / Unfollow, Followers / Following |
| Admin | `/api/admin` | ADMIN | User Audit, Invite Registration, Email Bind |
| Book Admin | `/api/books/admin` | ADMIN | Scan, Upload, Re-Parse |
| AI Config | `/api/admin/ai-provider` | ADMIN | Multi-Model CRUD, Enable / Disable, Connection Test |
| Captcha | `/api/captcha` | Public | Click-to-Verify Generation / Validation |
| Health | `/api/health` | Public | Service Status |

---

## License

Private — All Rights Reserved
