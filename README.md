<p align="center">
  <img src="book-open.svg" alt="KBook" width="80" height="80" />
</p>

<h1 align="center">KBook</h1>

<p align="center">
  <strong>让 16 个 AI 替你把书辩透</strong>
</p>

<p align="center">
  赛博斗蛐蛐，读书也疯狂<br/>
  开源 AI 阅读思辨引擎 —— 不替你读书，替你思考
</p>

<p align="center">
  <img src="https://img.shields.io/badge/React-19-blue" alt="React 19" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4-green" alt="Spring Boot 3.4" />
  <img src="https://img.shields.io/badge/LangChain4j-1.13-orange" alt="LangChain4j" />
  <img src="https://img.shields.io/badge/Qdrant-Vector-purple" alt="Qdrant" />
  <img src="https://img.shields.io/badge/Docker-Compose-blue" alt="Docker Compose" />
  <a href="https://github.com/keiskeies/kbook/stargazers">
    <img src="https://img.shields.io/github/stars/keiskeies/kbook?style=social" alt="Stars" />
  </a>
</p>

<p align="center">
  <a href="./README.md">中文</a> · <a href="./README_EN.md">English</a>
</p>

<p align="center">
  <a href="#-为什么选择-kbook"><strong>为什么</strong></a> ·
  <a href="#-四大核心体验"><strong>核心体验</strong></a> ·
  <a href="#-四个反共识决策"><strong>反共识</strong></a> ·
  <a href="#-功能清单"><strong>功能清单</strong></a> ·
  <a href="#-快速开始"><strong>快速开始</strong></a>
</p>

---

## 为什么选择 KBook？

> **读完一本书，最难受的不是没读懂——是没人跟你辩。**

几乎每个深度读者，都被这四个卡点卡住过：

| 卡点 | 症状 |
|------|------|
| **① 读完就忘** | 划了线、发了朋友圈，过两天只剩书名。被动输入的记忆留存率约 10%，主动辩论约 75% |
| **② 视角单一** | 同一本《定位》，营销人看见心智、产品人看见功能、投资人看见模式。你只读出一种味道，是因为你只有一种视角 |
| **③ 道理停在空中** | "长期主义""第一性原理"听着都对，但落到你的处境里怎么用？没人帮你做迁移 |
| **④ 找书像相亲** | 算法永远在推畅销榜，它不懂你的人生状态。刚失业的人，不需要《从 0 到 1》 |

**KBook 为「真正想读懂一本书的人」而生。**

不做阅读器，不搞书城，不做"读完即走"的浅层功能。KBook 把整本书注入 RAG 向量知识库，让 AI 真正"读过"这本书——然后陪你一对一思辨、组局多人圆桌、甚至发起一场多性格辩手的激烈辩论。

**合上书不是阅读的终点，而是思辨的起点。书不是被读完的，是被聊透、辩明、想通的。**

---

## 🔥 四大核心体验

### 1️⃣ AI 圆桌讨论 —— 147 位角色，围坐一桌聊透一本书

一个人读书是独处，一群人读书是盛宴。

KBook 的圆桌不是"多 AI 聊天"——**147 位角色**，每一位都有自己的思维方式、语言习惯、价值立场和情绪脾性，像一档属于你的深夜读书脱口秀：

- **40 种职业人格** —— 哲学家追问前提假设，投资人算投入产出比，诗人在意语言的质地，农民只关心土地和天气
- **107 位真实思想者** —— 从马斯克、乔布斯、巴菲特、芒格、段永平，到鲁迅、王小波、爱因斯坦、卡尼曼……每一位都不是一段风格提示词，而是一套完整的"思维操作系统"：核心心智模型、决策启发法、表达风格、诚实边界

**一场讨论是怎么跑的？**

1. **AI 选角** —— 输入一本书，LLM 根据主题挑选 4-6 位"最合适坐这桌"的角色
2. **主持人开场** —— AI 主持人介绍书目、抛出第一个议题
3. **动态抢话** —— 不是轮流坐庄，谁有话要说谁先开口
4. **观点碰撞** —— 角色直接反驳、补充、跑题、甚至吵起来——这是真实的讨论张力，不是客套话的堆砌
5. **覆盖度追踪** —— 实时追踪讨论覆盖了书中多少内容，冷场时主持人引导新话题
6. **解读报告** —— 讨论结束，生成一份多视角结构化研报

**适合谁：** 想知道"一个投资人和一个诗人会怎么聊这本书"的好奇读者；读书俱乐部找讨论灵感的组织者；一个人读不透、渴望"听不同大脑怎么看"的社交型学习者。

<p align="center">
  <img src="./doc/圆桌派.jpg" alt="AI 圆桌讨论" width="280" />
  <img src="./doc/圆桌派2.jpg" alt="圆桌讨论角色视角" width="280" />
</p>

---

### 2️⃣ AI 辩论赛 —— 16 种性格辩手，把一本书辩得底朝天

这是 KBook 最燃的功能。**16 种鲜明性格的 AI 辩手**——逻辑严谨型、犀利毒舌型、机智幽默型、反叛颠覆型、哲学思辨型、数据分析型……围绕一本书的争议观点，打一场完整的辩论赛：

- **性格鲜明，开口就是人设** —— 毒舌型毫不留情拆穿逻辑漏洞，幽默型用段子包装犀利观点，数据型死磕每一条论据链
- **完整赛制，多轮攻防** —— 立论 → 质询 → 自由辩论 → 结辩，不是一次性输出，是真刀真枪的交锋
- **AI 评委 7 维度打分** —— 逻辑性、论据丰富度、反驳力、感染力、幽默感、表达清晰度、观点新颖度，逐轮打分并生成详细点评报告
- **看完辩论再读书** —— 很多用户说："看完 AI 辩论，才发现自己第一遍完全读偏了"

**适合谁：** 喜欢《奇葩说》的思辨爱好者；面对复杂议题想"先看正反双方怎么打"的理性决策者；觉得一本书"好像有道理又哪里不对"、想借辩论理清思路的纠结读者。

<p align="center">
  <img src="./doc/奇葩说.jpg" alt="AI 辩论" width="280" />
  <img src="./doc/奇葩说1.jpg" alt="AI 辩论角色视角" width="280" />
</p>

---

### 3️⃣ AI 思辨引擎 —— 和"真正读过这本书"的 AI 聊到天亮

全书按 800 字智能分块（200 字重叠）注入 RAG 向量库，AI 真正"读过"这本书——但它**只做分析、评论、引申、对比，绝不复述原文**：

- **跨章节灵魂追问** —— "作者第三章的论据在第五章被自我推翻了，你怎么看？"AI 精准检索全书，带你看清论证脉络
- **苏格拉底式对谈** —— 不直接给答案，而是用书中的思想框架向你层层提问，逼出你自己的独立思考
- **观点压力测试** —— 抛出与作者相左的观点，看 AI 如何替你拆解辩论双方的逻辑漏洞
- **四种对话风格** —— 随和 / 深度 / 简洁 / 幽默，随场景切换

**适合谁：** 读完后一肚子问题没人讨论的深度阅读者；写论文、做研究需要快速吃透一本书的人；要求每个回答都有据可依的较真读者。

<p align="center">
  <img src="./doc/AI图书问答1.jpg" alt="AI 思辨对话" width="280" />
  <img src="./doc/AI图书问答2.jpg" alt="AI 深入追问" width="280" />
  <img src="./doc/AI图书问答3.jpg" alt="AI 多轮对话" width="280" />
</p>

---

### 4️⃣ 画像推荐 —— 懂你的人生状态，才推得出对的书

30 岁未婚的 INTJ 与 35 岁二孩的 ESFJ，注定不该读到同一本"人生指南"。

- **人生画像** —— 年龄、婚姻、育儿阶段、MBTI、兴趣标签，构建你的多维阅读 DNA
- **行为画像** —— 阅读、收藏、评分、完读行为持续萃取，反推你的性格特质与思维方式
- **情绪与意图** —— 开心 / 焦虑 / 疲惫 / 迷茫，充电 / 共鸣 / 解惑——状态不同，推荐逻辑不同
- **匹配度量化** —— 每本书展示与你的多维匹配分数，拒绝"因为热门所以推你"的敷衍

**适合谁：** 厌倦"全网都在读"的同质化推荐的读者；处于人生转折期（换工作、结婚、育儿）、需要"对的书"而不是"热的书"的精准读者。

<p align="center">
  <img src="./doc/推荐列表.jpg" alt="个性推荐" width="280" />
  <img src="./doc/个人画像.jpg" alt="个人画像" width="280" />
</p>

---

## 🆚 与传统阅读应用的区别

| | 传统阅读 App | KBook |
|---|---|---|
| **阅读后** | 合上书，阅读结束 | 合上书，思辨才刚开始 |
| **讨论** | 看书评区的水评 | 147 位角色圆桌讨论 / 16 性格 AI 辩论 |
| **AI** | 摘要生成、语音朗读 | 思辨对话、圆桌控场、辩论攻防、跨书关联 |
| **推荐** | 热门榜单 + 标签筛选 | 人生画像 + 行为画像 + 情绪意图三重匹配 |
| **理解深度** | 依赖读者自身 | AI 陪你追问、辩论、讨论，直到真正想通 |
| **你的数据** | 平台的资产 | 在你自己服务器上 |

---

## 💡 四个反共识决策

真正定义 KBook 的，不是功能清单，是四个反共识决策：

1. **不做阅读器，只做思辨引擎。** 不管你怎么读，只管读完能不能想清楚。
2. **AI 不输出原文。** System Prompt 禁复述、RAG 检索脱敏、n-gram 后处理拦截——三道防线，只做评论与分析，不复述原著，版权风险几乎归零。
3. **数据自有，一键自部署。** Docker Compose 一条命令起全栈，数据在你自己服务器，AI 调用你自己的 Key。"你在和谁讨论什么书"是私密信息，我们不碰。
4. **开源而非 SaaS。** 不开会员、不卖数据、不养推荐算法、不追日活。代码公开，你的书、你的讨论、你的数据，都归你。

---

## 🎯 功能清单

### 🤖 AI 思辨引擎

- **AI 圆桌讨论** — 147 位角色（40 种职业人格 + 107 位思想者），LLM 智能选角，动态抢话，实时覆盖度追踪，结构化解读报告
- **AI 辩论赛** — 16 种性格辩手，立论 / 质询 / 自由辩论 / 结辩四阶段，AI 评委 7 维度打分点评
- **AI 图书思辨** — 单本书全量内容 RAG 向量问答，自动生成追问建议，四种对话风格
- **AI 伴读助手** — 全局悬浮对话，支持工具调用（搜书 / 查书架 / 查进度 / 查阅读统计）
- **AI 速读卡片** — 一键生成书籍核心观点摘要，快速判断一本书值不值得深读
- **Vision OCR** — 扫描版 PDF 自动调用大模型视觉能力逐页识别
- **多模型支持** — 管理后台配置多个 AI 提供商（Ollama / OpenAI / DeepSeek 等），按场景分配模型，一键切换

### 🎯 画像与推荐

- **用户画像** — 注册采集 + 运行时更新：年龄段 / 性别 / 婚姻 / 子女 / MBTI / 兴趣标签
- **行为画像** — 基于阅读行为持续萃取性格特质与思维方式
- **情绪与意图** — 按当下情绪（开心 / 焦虑 / 疲惫 / 迷茫等）和阅读意图（充电 / 共鸣 / 解惑等）动态调整推荐
- **多路召回** — 向量召回 + 协同过滤 + 规则匹配 + 探索发现 + 热门兜底，融合排序
- **匹配度评分** — 每本书展示多维匹配分数，量化推荐理由

### ⚙️ 管理后台

- **图书入库** — SSE 流式扫描本地 EPUB / PDF / TXT 目录，自动解析入库，断点续扫；支持手动上传
- **重新解析** — 一键重新提取图书元数据（作者 / 简介 / 封面 / 目录）
- **AI 场景配置** — 按业务场景（思辨问答 / 圆桌 / 辩论 / 评分 / 报告）分别配置模型与参数
- **模型管理** — 多提供商 CRUD、连接测试、一键启用 / 禁用
- **TTS 配置** — 管理多种 TTS 引擎，配置音色和参数
- **用户审核** — 邀请注册制，管理员审核 / 批量操作 / 封禁 / 解封

<p align="center">
  <img src="./doc/AI助手.jpg" alt="AI 伴读助手" width="280" />
  <img src="./doc/AI助手2.jpg" alt="AI 伴读助手多轮对话" width="280" />
</p>

---

## 🏗️ 系统架构

```
kbook/
├── frontend/                # React 19 + TypeScript + Vite
│   └── src/
│       ├── components/      # UI 组件
│       │   ├── ai/          # AI 伴读助手
│       │   ├── book/        # 图书组件（思辨面板 / 速读卡片 / 匹配度）
│       │   ├── round-table/ # 圆桌（覆盖度追踪面板）
│       │   ├── debate/      # 辩论（打分面板 / 报告面板）
│       │   └── ui/          # shadcn/ui 基础组件
│       ├── pages/
│       │   ├── home/        # 首页（推荐 / 榜单 / 分类）
│       │   ├── discover/    # 发现
│       │   ├── book/        # 图书详情 + 圆桌讨论 + AI 辩论
│       │   ├── search/      # 搜索
│       │   ├── profile/     # 个人中心 / 阅读画像
│       │   ├── admin/       # 管理后台
│       │   └── auth/        # 登录 / 注册 / 新手引导
│       ├── store/           # Zustand 状态管理
│       └── api/             # API 接口层
│
├── backend/                 # Spring Boot 3.4
│   └── src/main/java/com/kbook/
│       ├── config/          # Security / JWT / Redis / Qdrant / LangChain4j
│       ├── controller/      # REST API 控制器
│       ├── service/ai/      # AI 服务层
│       │   ├── BookChatService       # RAG 思辨问答
│       │   ├── RoundTableService     # 圆桌讨论 / 覆盖度 / 报告
│       │   ├── DebateService         # 辩论赛流程
│       │   ├── DebateScoringService  # 7 维度评分
│       │   ├── behavior/             # 用户行为画像
│       │   └── streaming/            # SSE 流式输出
│       └── ...
│
└── deploy/                  # Docker Compose 全栈部署
```

---

## 🛠️ 技术栈

### Frontend

| Tech | Version | Purpose |
|------|---------|---------|
| React + TypeScript | 19 / 5 | UI Framework |
| Vite | 7 | Build Tool |
| Tailwind CSS + shadcn/ui | 3.4 | Styling & Components |
| React Router | 7 | Routing |
| Zustand | 5 | State Management |
| PWA | — | Installable / Offline-ready |

### Backend

| Tech | Version | Purpose |
|------|---------|---------|
| Spring Boot | 3.4 | Application Framework |
| Java | 17 | Runtime |
| Spring Security + JWT | — | Stateless Auth |
| LangChain4j | 1.13 | AI Chat / RAG / Embedding |
| Apache PDFBox | — | PDF Parsing / OCR |
| Maven | — | Build Tool |

### Infrastructure

| Component | Tech | Purpose |
|-----------|------|---------|
| RDBMS | MySQL 8 | Primary Data Store |
| Cache | Redis 7 | Session / Rate Limit / Recommendation Cache |
| Search | Elasticsearch 8.12 | Full-Text Search |
| Vector DB | Qdrant v1.12 | Book Content Vectors (RAG) |
| Reverse Proxy | Nginx | SSL / SSE / Static |
| Container | Docker Compose | Full-Stack Deployment |

---

## 🔍 思辨引擎是怎么工作的

```
┌──────────┐     ┌──────────────┐     ┌─────────────────┐     ┌─────────────┐
│  用户提问  │ ──▶ │ Embedding 向量化 │ ──▶ │ Qdrant 相似度检索 │ ──▶ │ LLM 思辨生成  │
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
3. **思辨阶段** — 检索片段 + 书籍元数据 + 用户画像 + 问题 → LLM 生成有据可依的分析与评论，**绝不复述原文**
4. **追问阶段** — AI 自动生成深入追问建议，引导用户从不同角度探索书籍内容
5. **版权防线** — System Prompt 禁复述 + RAG 检索脱敏 + n-gram 后处理拦截，三道防线只做思辨、不输出原著内容

---

## 🔒 安全

- **JWT 无状态认证** — Access Token 2h + Refresh Token 7d，Refresh Token 走 HttpOnly Cookie，401 自动刷新 + 请求排队
- **验证码** — 点击式图片验证码 + 邮箱验证码双校验，60s 频率限制 + 10 次/天上限 + 5min TTL
- **密码安全** — BCrypt 哈希加密
- **权限控制** — `@PreAuthorize` 角色校验 + SecurityConfig 路径级控制
- **API Key 脱敏** — AI 提供商密钥在管理端响应中自动掩码
- **Token 黑名单** — 登出 Token 加入 Redis 黑名单
- **速率限制** — Redis 令牌桶限流，防止 API 滥用
- **安全头** — SecurityHeaderFilter 注入 X-Content-Type-Options / X-Frame-Options 等安全响应头

---

## 🚀 快速开始

### 环境要求

- Node.js 18+, Java 17, Maven 3.8+
- MySQL 8, Redis 7, Elasticsearch 8.12, Qdrant v1.12+

### 本地开发

```bash
# Frontend
cd frontend && npm install && npm run dev    # http://localhost:15173

# Backend
cd backend && mvn spring-boot:run            # http://localhost:8181
```

### Docker 部署

```bash
# 构建前端
cd frontend && npm run build

# 启动全栈
cd deploy && docker compose up -d
```

Docker Compose 包含：MySQL、Redis、Elasticsearch、Qdrant、Spring Boot 后端、Nginx（静态资源 + 反向代理）。

### 默认管理员

- Email: `admin@kbook`
- Password: `admin123456`

---

## 📡 API 概览

| 模块 | 前缀 | 认证 | 关键端点 |
|------|------|------|----------|
| 认证 | `/api/auth` | Public / Auth | 登录、注册、验证码、Token 刷新、密码重置 |
| 首页 | `/api/home` | Auth | 聚合数据（统计 / 推荐 / 榜单 / 分类） |
| 图书 | `/api/books` | Public / Auth | 搜索、详情、排行、封面、评分、文件流 |
| **图书思辨** | `/api/books/{id}/chat` | Auth | **RAG 流式思辨问答、推荐问题、历史记录、深入追问** |
| **圆桌讨论** | `/api/round-table` | Auth | **AI 选角、角色 SSE 发言、覆盖度追踪、解读报告** |
| **AI 辩论** | `/api/debate` | Auth | **创建辩论、辩手发言、7 维度评分、生成报告** |
| AI 助手 | `/api/ai` | Auth | 多轮对话 (SSE)、会话管理、工具调用 |
| 进度 | `/api/progress` | Auth | 上报、批量同步、统计 |
| 推荐 | `/api/recommend` | Auth / Admin | 个性推荐、缓存清理、向量重建 |
| 用户 | `/api/user` | Auth | 个人信息、头像、画像更新、对话风格 |
| 管理 | `/api/admin` | ADMIN | 用户审核、邀请注册、邮箱绑定 |
| 图书管理 | `/api/books/admin` | ADMIN | 扫描、上传、重新解析 |
| AI 配置 | `/api/admin/ai-provider` | ADMIN | 多模型 CRUD、启用 / 禁用、连接测试 |
| TTS 配置 | `/api/admin/tts` | ADMIN | TTS 引擎 CRUD、参数配置 |
| 验证码 | `/api/captcha` | Public | 点击式验证码生成 / 校验 |
| 健康检查 | `/api/health` | Public | 服务状态 |

---

## 🗺️ Roadmap

- [ ] 跨书思辨 — 同时打开书架上的多本书，让 AI 发现跨书的隐藏关联
- [ ] 阅读数据可视化 — 阅读时长热力图、标签云、知识图谱
- [ ] 多语言支持 — 界面与 AI 思辨的多语言适配
- [ ] 插件系统 — 自定义 AI 角色、辩论赛制、推荐策略

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

本项目采用 **个人免费使用，商业需授权** 的许可模式：

- **个人用户**：可免费下载、部署及使用本项目的全部功能，仅供个人学习、研究与非商业用途。
- **商业用途**：任何企业、组织或个人将本项目用于商业运营、内部生产环境、SaaS 服务、二次开发后销售，或产生直接或间接商业收益的行为，**必须事先获得书面授权**。请联系项目作者洽谈合作与授权事宜。
- **禁止行为**：未经授权，不得将本项目或其衍生作品用于商业目的；不得移除或篡改版权声明。

**未经授权的商业使用将被视为侵权，保留追究法律责任的权利。**
