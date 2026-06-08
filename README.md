<p align="center">
  <img src="book-open.svg" alt="KBook" width="80" height="80" />
</p>

<h1 align="center">KBook</h1>

<p align="center">
  <strong>为「此刻的你」找到那本书</strong>
</p>

<p align="center">
  AI 原生问答平台 · 让 AI 真正「读过」每一本书，再陪你聊透每一个细节
</p>

<p align="center">
  <img src="https://img.shields.io/badge/React-19-blue" alt="React 19" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4-green" alt="Spring Boot 3.4" />
  <img src="https://img.shields.io/badge/LangChain4j-AI-orange" alt="LangChain4j" />
  <img src="https://img.shields.io/badge/Qdrant-Vector-purple" alt="Qdrant" />
  <img src="https://img.shields.io/badge/PWA-Ready-blueviolet" alt="PWA" />
  <a href="https://github.com/keiskeies/kbook/stargazers">
    <img src="https://img.shields.io/github/stars/keiskeies/kbook?style=social" alt="Stars" />
  </a>
</p>

<p align="center">
  <a href="./README.md">中文</a> · <a href="./README_EN.md">English</a>
</p>

<p align="center">
  <a href="#-quick-start"><strong>快速开始</strong></a> · <a href="#-preview">预览</a> · <a href="#-features">功能</a> · <a href="#how-ai-book-qa-works">AI 问答原理</a>
</p>

---

## 为什么选择 KBook？

> **传统阅读应用的尽头是「翻完最后一页」。**
> **KBook 的起点，是你合上书后想说的第一句话。**

你是否也经历过这些时刻——

- 读完一本好书，满脑子想法却无处讨论？
- 书中某个观点让你困惑，翻遍搜索引擎也找不到针对这本书的深度解读？
- 被推荐了一本「人生必读」，却发现和你的生活阶段毫无共鸣？

KBook 重新定义了人与书的关系：**不是读完即走，而是聊到通透。**

---

## ✨ 为「此刻的你」找到那本书

30 岁未婚的 INTJ 与 35 岁二孩的 ESFJ，注定不该读到同一本「人生指南」。

KBook 基于真实人生状态（年龄 / 婚姻 / 育儿 / MBTI）构建推荐引擎，让每本书都精准对应当下的人生课题。更关键的是，我们不做「读完即走」的浅层阅读——KBook 将整本书籍内容完整注入 RAG 向量知识库，让 AI 真正「读过」这本书，再陪你聊透每一个细节：

- 📖 **穿透式追问** — 「作者第三章的论据在第五章被自我推翻了，你怎么看？」AI 能跨章节检索，带你看清论证脉络
- 🧠 **苏格拉底式对谈** — 不直接给答案，而是用书中的思想框架向你层层提问，逼出你自己的独立思考
- 🔗 **知识图谱编织** — 让 AI 对比你书架里的其他书籍，发现跨书的隐藏关联，构建属于你的认知网络
- ✍️ **观点压力测试** — 抛出与作者相左的学界观点，看 AI 如何替你拆解辩论双方的逻辑漏洞

**在 KBook，书不是被读完的，是被聊透的。**

---

### 🆚 与传统阅读应用的区别

| | 传统阅读 App | KBook |
|---|---|---|
| **阅读后** | 合上书，阅读结束 | 打开 AI 对话，阅读才刚开始 |
| **搜索** | 只能搜书名、作者 | 全书内容向量检索，一句话定位段落 |
| **推荐** | 热门榜单 + 标签筛选 | 基于人生状态（年龄/婚姻/MBTI）的精准推荐 |
| **AI** | 摘要生成、语音朗读 | RAG 问答、苏格拉底式对谈、跨书知识关联 |
| **理解深度** | 依赖读者自身 | AI 陪你追问，层层深入直到真正理解 |

---

## 📸 预览

<table>
  <tr>
    <td align="center"><b>📚 图书详情</b></td>
    <td align="center"><b>🤖 AI 书籍问答</b></td>
    <td align="center"><b>🤖 AI 深入追问</b></td>
  </tr>
  <tr>
    <td><img src="./doc/book_detail.jpg" alt="图书详情" width="240" /></td>
    <td><img src="./doc/book_ask_1.jpg" alt="AI 书籍问答" width="240" /></td>
    <td><img src="./doc/book_ask_2.jpg" alt="AI 深入追问" width="240" /></td>
  </tr>
  <tr>
    <td align="center"><b>🤖 AI 多轮对话</b></td>
    <td align="center"><b>🎯 个性推荐</b></td>
    <td align="center"><b>👤 个人画像</b></td>
  </tr>
  <tr>
    <td><img src="./doc/book_ask_3.jpg" alt="AI 多轮对话" width="240" /></td>
    <td><img src="./doc/ai_recomend.png" alt="个性推荐" width="240" /></td>
    <td><img src="./doc/self_info.jpg" alt="个人画像" width="240" /></td>
  </tr>
</table>

---

## 🎯 Features

### 🤖 AI 智能引擎

- **AI 图书问答** — 针对单本书全量内容的 RAG 向量检索问答，自动生成推荐问题，基于原著片段精准回答，支持深入追问
- **AI 伴读助手** — 基于 LangChain4j 的多轮流式对话，支持 @Tool 调用（搜索图书 / 查书架 / 查进度 / 查阅读统计）
- **AI 元数据生成** — 自动提取标签、评分、八维度相关度（年龄段 / 性别 / 婚姻 / 子女 / MBTI）
- **AI 速读卡片** — 一键生成书籍核心观点摘要，快速了解一本书值不值得深读
- **Vision OCR** — 扫描版 PDF 自动调用大模型视觉能力逐页识别
- **多模型支持** — 管理后台可配置多个 AI 提供商（Ollama / OpenAI / DeepSeek 等），一键切换
- **对话风格** — 支持随和 / 深度 / 简洁 / 幽默四种对话风格，满足不同阅读场景

### 📖 沉浸阅读

- **多格式引擎** — EPUB / PDF / TXT 三格式统一引擎，字体、字号、行距、主题色自由调节
- **进度同步** — 实时上报 + 离线批量同步，时间戳覆盖冲突解决，跨设备无缝衔接
- **TTS 朗读** — 支持小米 TTS、讯飞 TTS 等多种语音引擎，文本分片语音合成，播放/暂停/停止一键操控
- **目录导航** — EPUB / PDF 自动提取目录，章节级快速跳转
- **阅读主题** — 多种主题色切换，支持自定义阅读背景

### 🎯 个性推荐

- **用户画像** — 注册采集 + 运行时更新，构建多维阅读画像（年龄段 / 性别 / 婚姻 / 子女 / MBTI / 兴趣标签）
- **向量召回** — Qdrant 存储图书元数据向量，基于用户画像余弦相似度召回
- **协同过滤** — 基于阅读 / 收藏 / 评分 / 完成行为加权，推荐相似用户喜爱的书籍
- **融合排序** — 规则匹配 + 向量召回 + 协同过滤 + 探索发现 + 热门兜底，多路融合排序
- **匹配度评分** — 每本书展示与你的八维度匹配度，量化推荐理由

### 📚 书架与发现

- **书架管理** — 收藏/移除、筛选排序、阅读状态追踪
- **全文搜索** — Elasticsearch 驱动的全文检索，搜索建议、标签筛选、高亮匹配
- **排行榜** — 热门阅读 / 高分推荐 / 新书速递多维度榜单
- **热门标签** — 基于全局阅读数据的标签云，快速发现感兴趣的方向

### 💬 社区互动

- **书评系统** — 书籍 / 章节级评论，嵌套回复、点赞、收藏
- **关注体系** — 关注 / 粉丝体系，用户主页展示在读 / 已读 / 书评
- **消息通知** — 评论回复、点赞里程碑等阶梯式推送，站内通知 + 邮件通知

### ⚙️ 管理后台

- **目录扫描** — SSE 流式扫描本地 EPUB / PDF / TXT 目录，自动解析入库，断点续扫
- **文件上传** — 手动上传图书文件，自动解析元数据 + AI 标签生成
- **重新解析** — 一键重新提取图书元数据（作者 / 简介 / 封面 / 目录）
- **AI 模型管理** — 多提供商 CRUD、连接测试、一键启用 / 禁用
- **TTS 配置** — 管理多种 TTS 引擎（小米 / 讯飞），配置音色和参数
- **用户审核** — 邀请注册制，管理员审核 / 批量操作 / 封禁 / 解封

---

## 🏗️ Architecture

```
kbook/
├── frontend/                # React 19 + TypeScript + Vite
│   ├── src/
│   │   ├── components/      # UI 组件
│   │   │   ├── ai/          # AI 相关组件（InlineBookCard）
│   │   │   ├── auth/        # 路由守卫（AuthGuard / AdminGuard / GuestGuard）
│   │   │   ├── book/        # 图书组件（BookChatSheet / AI 问答面板 / 匹配度 / 速读卡片）
│   │   │   ├── comment/     # 评论组件
│   │   │   ├── common/      # 通用组件（AuthImage / AvatarCrop / ImageViewer / PinchZoom）
│   │   │   ├── home/        # 首页组件（MoodQuickSwitch）
│   │   │   ├── layout/      # 布局（TabBar / AppLayout / DesktopSidebar / BlankLayout）
│   │   │   ├── reader/      # 阅读器组件（EpubRenderer / PdfRenderer / TxtRenderer / TtsFloatPlayer）
│   │   │   └── ui/          # shadcn/ui 基础组件
│   │   ├── pages/           # 页面
│   │   │   ├── home/        # 首页（继续阅读 / 推荐 / 榜单 / 分类 / 统计）
│   │   │   ├── rank/        # 排行榜（热门阅读 / 高分推荐 / 新书速递）
│   │   │   ├── ai/          # AI 助理（多轮对话 / 历史会话 / 对话风格）
│   │   │   ├── bookshelf/   # 书架（筛选 / 排序 / 管理）
│   │   │   ├── book/        # 图书详情 + AI 问答入口 + 速读卡片
│   │   │   ├── reader/      # 阅读器（EPUB / PDF / TXT / TTS）
│   │   │   ├── search/      # 搜索（ES 全文检索 / 标签筛选 / 搜索建议）
│   │   │   ├── reviews/     # 书评
│   │   │   ├── follow/      # 关注 / 粉丝
│   │   │   ├── notifications/ # 消息通知
│   │   │   ├── profile/     # 个人中心 / 阅读历史 / 阅读统计 / 偏好设置
│   │   │   ├── user/        # 用户主页
│   │   │   ├── admin/       # 管理后台（审核 / 图书 / AI 配置 / TTS 配置）
│   │   │   └── auth/        # 登录 / 注册 / 重置密码 / 邮箱绑定
│   │   ├── router/          # 路由配置（懒加载 + 守卫）
│   │   ├── store/           # Zustand 状态管理（auth / reader / progress / tts / ui / chat）
│   │   ├── api/             # API 接口层（与后端 Controller 一一对应）
│   │   ├── hooks/           # Custom Hooks（useEpubReader / usePdfReader / useTtsReader / useMatchScores）
│   │   ├── types/           # TS 类型定义
│   │   └── utils/           # 工具函数（Axios 封装 / SSE 请求 / Token 刷新 / TTS）
│   └── vite.config.ts       # PWA + 代理配置
│
├── backend/                 # Spring Boot 3.4
│   └── src/main/java/com/kbook/
│       ├── common/          # 统一响应、分页、异常处理、基础服务
│       ├── config/          # Security / JWT / CORS / Redis / Qdrant / LangChain4j / ChatModelFactory
│       ├── constants/       # AI 提示词常量
│       ├── controller/      # REST API 控制器
│       ├── document/        # Elasticsearch 文档定义
│       ├── dto/             # 请求/响应 DTO（按业务域分包）
│       ├── entity/          # JPA 实体
│       ├── repository/      # 数据访问层
│       └── service/         # 业务逻辑层
│           ├── ai/          # AI 服务（BookChat / AiChat / BookAdminChat / AiProviderConfig）
│           ├── auth/        # 认证服务（AuthService / ClickCaptchaService）
│           ├── book/        # 图书服务（BookService / BookScanService / BookParserService / BookSearchService）
│           ├── comment/     # 评论服务
│           ├── embedding/   # 向量嵌入服务（EmbeddingService / RagHitStatisticsService）
│           ├── notification/ # 通知服务（站内 + 邮件）
│           ├── progress/    # 阅读进度服务
│           ├── rank/        # 排行服务
│           ├── recommend/   # 推荐服务（多路召回 + 融合排序）
│           ├── storage/     # 文件存储服务
│           ├── tools/       # AI 工具支持（维度统计 / 动态查询）
│           ├── tts/         # TTS 服务（小米 / 讯飞引擎 + 缓存）
│           ├── user/        # 用户服务（UserService / UserFollowService / UserBookPreferenceService）
│           └── video/       # 视频处理服务（FFmpeg 缩略图 / 转码）
│
└── deploy/                  # 部署配置
    ├── docker-compose.yml   # 全栈容器编排
    └── nginx/               # Nginx 反向代理配置
```

---

## 🛠️ Tech Stack

### Frontend

| Tech | Version | Purpose |
|------|---------|---------|
| React + TypeScript | 19 / 5 | UI Framework |
| Vite | 7 | Build Tool |
| Tailwind CSS + shadcn/ui | 3.4 | Styling & Components |
| React Router DOM | 7 | Routing |
| Zustand | 5 | State Management |
| Axios | 1.x | HTTP Client (Interceptor + Token Refresh) |
| epubjs | 0.3.93 | EPUB Renderer |
| pdfjs-dist | 5.x | PDF Renderer |
| Lucide React | — | Icon Library |
| vite-plugin-pwa | — | PWA Offline Support |

### Backend

| Tech | Version | Purpose |
|------|---------|---------|
| Spring Boot | 3.4 | Application Framework |
| Java | 17 | Runtime |
| Spring Security + JWT | — | Stateless Auth |
| Spring Data JPA + Hibernate | — | ORM |
| LangChain4j | 1.13 | AI Chat / RAG / Embedding / Tools |
| epublib-core | 3.1 | EPUB Parsing |
| Apache PDFBox | — | PDF Parsing / Rendering / OCR |
| FFmpeg | — | Video Thumbnail & Transcode |
| Maven | — | Build Tool |

### Infrastructure

| Component | Tech | Purpose |
|-----------|------|---------|
| RDBMS | MySQL 8 + HikariCP | Primary Data Store |
| Cache | Redis 7 (Lettuce) | Captcha / Session / Recommendation Cache / Rate Limit |
| Search | Elasticsearch 8.12 | Full-Text Search / Highlight / Suggestion |
| Vector DB | Qdrant v1.12 | Book Metadata Vectors + RAG Content Vectors |
| Mail | QQ SMTP (SSL 465) | Captcha / Invitation / Notification |
| Reverse Proxy | Nginx (Alpine) | SSL / Compression / Rate Limit / SSE / Static |
| Container | Docker Compose | Full-Stack Deployment |

---

## How AI Book Q&A Works

```
┌──────────┐     ┌──────────────┐     ┌─────────────────┐     ┌─────────────┐
│  用户提问  │ ──▶ │ Embedding 向量化 │ ──▶ │ Qdrant 相似度检索 │ ──▶ │ LLM 生成回答  │
└──────────┘     └──────────────┘     └─────────────────┘     └─────────────┘
                                            │
                      ┌─────────────────────┘
                      ▼
              ┌───────────────┐
              │  kbook_content │  ← 全书按 800 字分块
              │  Qdrant 集合    │  ← 每块独立向量化存储
              │  1024 维向量    │  ← int8 标量量化
              └───────────────┘
```

1. **入库阶段** — 图书解析后，全文按 800 字分块（200 字重叠），批量 Embedding 后存入 Qdrant `kbook_content` 集合
2. **提问阶段** — 用户问题 → Embedding 向量化 → Qdrant 余弦相似度检索 Top-8 片段
3. **回答阶段** — 检索到的原文片段 + 书籍元数据 + 用户画像 + 问题 → LLM 生成有据可依的回答
4. **追问阶段** — AI 自动生成深入追问建议，引导用户从不同角度探索书籍内容
5. **质量保障** — 仅评分达标的图书生成内容向量；`contentEmbedded` 标记控制前端问答入口

---

## 🔒 Security

- **JWT 无状态认证** — Access Token 2h + Refresh Token 7d，401 自动刷新 + 请求排队
- **验证码** — 点击式图片验证码 + 邮箱验证码双校验，60s 频率限制 + 10次/天上限 + 5min TTL
- **密码安全** — BCrypt 哈希加密
- **权限控制** — `@PreAuthorize` 角色校验 + `SecurityConfig` 路径级控制
- **API Key 脱敏** — AI 提供商密钥在管理端响应中自动掩码
- **Token 黑名单** — 登出 Token 加入 Redis 黑名单
- **速率限制** — Redis 令牌桶限流，防止 API 滥用
- **安全头** — SecurityHeaderFilter 注入 X-Content-Type-Options / X-Frame-Options 等安全响应头

---

## 🚀 Quick Start

### Prerequisites

- Node.js 18+, Java 17, Maven 3.8+
- MySQL 8, Redis 7, Elasticsearch 8.12, Qdrant v1.12+

### Local Development

```bash
# Frontend
cd frontend && npm install && npm run dev    # http://localhost:15173

# Backend
cd backend && mvn spring-boot:run            # http://localhost:8181
```

### Docker Deployment

```bash
# Build frontend
cd frontend && npm run build

# Launch all services
cd deploy && docker compose up -d
```

Docker Compose 包含：MySQL, Redis, Elasticsearch, Qdrant, Spring Boot 后端, Nginx（静态资源 + 反向代理）。

### Default Admin Account

- Email: `admin@kbook`
- Password: `admin123456`

---

## 📡 API Overview

| 模块 | 前缀 | 认证 | 关键端点 |
|------|------|------|----------|
| 认证 | `/api/auth` | Public / Auth | 登录、注册、验证码、Token 刷新、密码重置 |
| 首页 | `/api/home` | Auth | 聚合数据（统计 / 推荐 / 榜单 / 分类） |
| 图书 | `/api/books` | Public / Auth | 搜索、详情、排行、封面、评分、文件流 |
| **图书问答** | `/api/books/{id}/chat` | Auth | **RAG 流式问答、推荐问题、历史记录、深入追问** |
| AI 助手 | `/api/ai` | Auth | 多轮对话 (SSE)、会话管理、@Tool 调用 |
| 书架 | `/api/bookshelf` | Auth | 增删查、检查、计数 |
| 进度 | `/api/progress` | Auth | 上报、批量同步、统计 |
| 推荐 | `/api/recommend` | Auth / Admin | 个性推荐、缓存清理、向量重建 |
| 评论 | `/api/comments` | Public / Auth | 书籍/章节评论、回复、点赞、收藏 |
| 通知 | `/api/notifications` | Auth | 列表、未读数、标记已读 |
| 用户 | `/api/user` | Auth | 个人信息、头像、画像更新、对话风格 |
| 用户主页 | `/api/user-profile` | Public | 用户页面、在读 / 已读 / 书评 |
| 关注 | `/api/follow` | Public / Auth | 关注 / 取关、粉丝 / 关注列表 |
| 管理 | `/api/admin` | ADMIN | 用户审核、邀请注册、邮箱绑定 |
| 图书管理 | `/api/books/admin` | ADMIN | 扫描、上传、重新解析 |
| AI 配置 | `/api/admin/ai-provider` | ADMIN | 多模型 CRUD、启用 / 禁用、连接测试 |
| TTS 配置 | `/api/admin/tts` | ADMIN | TTS 引擎 CRUD、参数配置 |
| 验证码 | `/api/captcha` | Public | 点击式验证码生成 / 校验 |
| 健康检查 | `/api/health` | Public | 服务状态 |

---

## 🗺️ Roadmap

- [ ] AI 书评生成 — 读完一本书，AI 基于你的阅读进度和标注自动起草书评
- [ ] 跨书对话 — 同时打开书架上的多本书，让 AI 发现跨书的隐藏关联
- [ ] 阅读数据可视化 — 阅读时长热力图、标签云、知识图谱
- [ ] 多语言支持 — 界面与 AI 问答的多语言适配
- [ ] 插件系统 — 自定义 AI 工具、阅读器主题、推荐策略

---

## 🤝 Contributing

欢迎贡献！如果你有想法或发现问题：

1. Fork 本仓库
2. 创建 Feature 分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送分支 (`git push origin feature/amazing-feature`)
5. 提交 Pull Request

---

## ⭐ Star History

如果这个项目对你有帮助，欢迎点个 Star ⭐

[![Star History Chart](https://api.star-history.com/svg?repos=keiskeies/kbook&type=Date)](https://star-history.com/#keiskeies/kbook&Date)

---

## License

Private — All Rights Reserved
