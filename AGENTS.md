# AGENTS.md — KBook

AI-native reading platform. React 19 frontend + Spring Boot 3.4 backend.

## Commands

### Frontend (`frontend/`)
- `npm install` — install deps (uses npm, not pnpm despite lockfile presence)
- `npm run dev` — Vite dev server on **port 15173**, proxies `/api` → `http://localhost:8181`
- `npm run build` — runs `tsc -b && vite build` (TypeScript project references, outputs to `dist/`)
- `npm run lint` — ESLint
- Path alias: `@/` → `src/`

### Backend (`backend/`)
- `mvn spring-boot:run` — starts on **port 8181** (not 8080)
- `mvn clean package -DskipTests` — build JAR to `target/kbook-server-1.0.0.jar`
- Java 17 required

### Docker (`deploy/`)
- `docker compose up -d` — MySQL, Redis, ES, Qdrant, backend (port 8080), Nginx
- Docker backend runs on **port 8080**, dev runs on **8181** — do not confuse

## Infrastructure (all required for backend to start)
| Service | Dev Port | Docker Port |
|---------|----------|-------------|
| MySQL 8 | 3306 | 3306 |
| Redis 7 | 6379 | 6379 |
| Elasticsearch 8.12 | 9201 | 9200 |
| Qdrant v1.12 | 6334 | 6334 |

**ES port mismatch**: dev default is `9201` (`application.yml`), Docker exposes `9200`. Set `ES_URIS` accordingly.

## Backend env defaults (dev)
- DB: `kbook-dev`, user `root`, password `123456`
- Redis password: `123456`, database `4`
- ES: `http://localhost:9201` (no auth)
- Qdrant: `localhost:6334` (gRPC)
- AI: Ollama at `http://localhost:11434`, model `gemma4:e4b`, embedding `bge-m3:latest`
- Book paths default to `G:/图书/{epub,pdf1,txt1}` — change for your machine
- Admin default: `admin@kbook` / `admin123456` (injected by `DataInitializer`)

## Architecture notes
- **API response envelope**: all responses wrapped in `Result<T>` → `{code, message, data}`. `code=0` = success. Frontend `request.ts` unwraps `data` on `code===0`.
- **Business codes**: `1001` = user pending approval, `1002` = user banned. Frontend handles these specially (no toast).
- **SSE endpoints**: AI chat (`/api/ai/chat`, `/api/books/{id}/chat`), admin scan (`/api/books/admin/scan`). Timeouts set to 1 hour across Vite proxy, Tomcat, and Nginx.
- **Circular dependency**: `AiToolService → RecommendService → EmbeddingService → AiProviderConfigService → AiToolService`. Resolved with `@Lazy` and `ObjectProvider`. Do not remove.
- **EmbeddingService lazy init**: breaks circular dep with `AiProviderConfigService`. Embedding model is lazily initialized.
- **Two Qdrant collections**: `kbook_books` (metadata vectors for recommendation) and `kbook_content` (chunked book content for RAG). 1024-dim vectors, int8 scalar quantization.
- **RAG chunking**: 800 chars per chunk, 200 char overlap. Configurable via `kbook.qdrant.chunk-size` / `chunk-overlap`.
- **JPA ddl-auto**: `update` — schema auto-migrates on startup. No manual migrations.
- **JWT**: access token 2h, refresh token 7d. Frontend auto-refreshes on 401 with request queuing.

## Frontend specifics
- State: Zustand stores (`auth`, `reader`, `progress`, `tts`, `ui`)
- Router: React Router 7 with `createBrowserRouter`, lazy-loaded pages, two layouts (`AppLayout` with TabBar, `BlankLayout` for reader/auth/admin)
- Route guards: `AuthGuard`, `AdminGuard`, `GuestGuard`
- Three reader renderers: `EpubRenderer` (epubjs), `PdfRenderer` (pdfjs-dist), `TxtRenderer`
- PWA enabled with `autoUpdate` registration
- No test files exist despite `@playwright/test` in devDependencies

## Deployment
- Production behind Nginx at `book.keiskei.top` (SSL via Let's Encrypt)
- Nginx proxies to backend on `127.0.0.1:8080`
- Frontend `dist/` served as static files by Nginx
- `deploy/nginx/kbook.conf` also routes other domains on same server — only `book.keiskei.top` is KBook

## ChatModelFactory — 8 no-arg methods

All 8 methods return `ChatModel` or `StreamingChatModel`, zero parameters, provider auto-detected inside.

| # | Method | Source | Thinking | Streaming |
|---|--------|--------|----------|-----------|
| 1 | `buildChatModel()` | DB→yml | on | no |
| 2 | `buildChatModelWithoutThinking()` | DB→yml | off | no |
| 3 | `buildStreamingChatModel()` | DB→yml | on | yes |
| 4 | `buildStreamingChatModelWithoutThinking()` | DB→yml | off | yes |
| 5 | `buildChatModelFromYml()` | yml | on | no |
| 6 | `buildChatModelWithoutThinkingFromYml()` | yml | off | no |
| 7 | `buildStreamingChatModelFromYml()` | yml | on | yes |
| 8 | `buildStreamingChatModelWithoutThinkingFromYml()` | yml | off | yes |

### Secondary methods
- `buildVisionChatModel()` — Ollama only, temperature 0.3, 600s timeout, no thinking
- `buildChatModelForTest(Long configId)` — loads from DB by ID
- `buildOllamaEmbeddingModel(...)` — create from params
- `buildDefaultEmbeddingModel()` — yml embedding config

### All models (except vision) wrapped with:
- `RetryableChatModel` — exponential backoff 1s/2s/4s, ±25% jitter, max 30s, 3 retries on 429
- `customHeaders` with UTF-8 charset (Ollama only)
- OpenAI models get `DiagnosticChatListener`

## AiProviderConfig.provider — enum
- `Provider` enum: `OLLAMA`, `OPENAI`
- JPA `AttributeConverter`: case-insensitive reads
- `@JsonCreator from(String)`: case-insensitive deserialization
- All comparisons use `==` not `.equals()`

## Callers using yml-only methods
- `BookParserService.generateSpeedRead()` → `buildChatModelWithoutThinkingFromYml()`
- `BookChatService.followUpQuestions()` → `buildChatModelWithoutThinkingFromYml()`

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **kbook** (31656 symbols, 38986 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `gitnexus_detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/kbook/context` | Codebase overview, check index freshness |
| `gitnexus://repo/kbook/clusters` | All functional areas |
| `gitnexus://repo/kbook/processes` | All execution flows |
| `gitnexus://repo/kbook/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

# 全栈 Monorepo AI 编码规范 (Claude.md)

你是一个资深的 Spring Boot + React + TypeScript 全栈架构师。本项目采用 Monorepo 架构。
在生成、修改或重构代码时，必须严格遵守以下全栈规范，确保前后端架构的一致性、类型安全和代码的可维护性。每次生成代码前，请先在内心回顾以下规范。

## 0. 工程边界与目录结构 (Workspace)
- **严格物理隔离**：后端代码必须在 `/backend` 目录下，前端代码必须在 `/frontend` 目录下。严禁在根目录混合存放 `pom.xml`、`package.json` 或源码文件。
- **跨栈修改原则**：当要求实现“完整功能”时，必须按以下顺序思考并输出：
    1. 后端 Entity / Repository
    2. 后端 DTO / Service / Controller
    3. 前端 TS Interface / API 请求封装
    4. 前端 React 组件 / Custom Hooks
- **禁止越界**：后端代码严禁引入前端依赖，前端代码严禁直接读取后端数据库或配置。

## 1. 全栈 API 契约规范 (Contract First) ⚠️核心
- **统一响应体解包**：
    - 后端必须统一返回 `Result<T> { code: number, message: string, data: T }`。
    - 前端必须在 Axios 拦截器中**统一解包**，直接返回 `data` 给业务组件。前端业务代码严禁频繁判断 `res.code === 200`。
- **类型强同步**：修改后端 DTO/VO 时，**必须主动同步修改**前端的 TypeScript Interface。严禁前端使用 `any` 接收后端数据。
- **命名风格统一**：强制全链路使用 **小驼峰命名法 (camelCase)**。后端 Jackson 必须配置全局策略映射为驼峰，严禁出现前后端字段命名风格不一致（如 `createTime` vs `create_time`）。
- **日期时间格式**：后端必须通过全局 Jackson 配置，将时间统一序列化为 `yyyy-MM-dd HH:mm:ss` 或 毫秒时间戳。严禁返回带 `T` 和时区的 ISO-8601 原始字符串让前端手动 parse。

## 2. 后端开发规范 (Spring Boot)

### 2.1 架构与分层 (Architecture)
- **严格分层**：
    - `Controller`：只负责参数校验（`@Validated`）、权限控制、接收请求和返回统一响应。严禁包含业务逻辑，严禁直接调用 `Repository`。
    - `Service`：负责核心业务逻辑。严禁操作 `HttpServletRequest/Response`。
    - `Repository`：只负责数据库交互。
- **DTO/VO 模式**：严禁将 `@Entity` 直接作为 API 响应返回。必须定义专门的 `XxxRequest`、`XxxResponse`。使用 `MapStruct` 或 `BeanUtils` 进行对象转换。

### 2.2 通用基类设计 (Base Classes)
所有实体、Repository、Service 必须继承通用基类，严禁重复造轮子：
- **BaseEntity**：必须包含 `@Id @GeneratedValue Long id`；必须使用 JPA Auditing 自动管理 `createTime` 和 `updateTime`；必须包含逻辑删除字段（如 `@SQLRestriction("is_deleted = false")`）。
- **BaseRepository**：必须继承 `JpaRepository<T, Long>` 和 `JpaSpecificationExecutor<T>`。
    - **动态查询规范**：严禁在 Repository 中编写大量 `findByXxxAndYyy` 方法。复杂动态查询必须使用 `Specification` 或 `QueryDSL` 在 Service 层构建。
- **BaseService**：封装通用的分页查询、基础 CRUD 等方法，子类直接继承复用。

### 2.3 Controller 与 RESTful 规范
- **URL 命名规范**：
    - 必须遵循 RESTful 风格。资源名称使用**复数名词**（如 `/api/v1/books`）。
    - 操作通过 HTTP Method 区分（`GET` 查询，`POST` 创建，`PUT` 更新，`DELETE` 删除）。
    - **严禁 URL 混乱**：同一个资源的 CRUD 接口必须放在同一个 Controller 类中。严禁出现 `/api/book/`、`/api/chat/book/`、`/api/book/chat_stream` 这种随意散落的接口。

### 2.4 配置管理 (Configuration)
- **严禁滥用 `@Value`**：严禁在代码中使用散落的 `@Value("${xxx}")` 注入配置。
- **统一配置类**：必须使用 `@ConfigurationProperties` 创建强类型的配置类（如 `OssProperties`），按业务域分包管理。

### 2.5 异常、事务与校验
- **全局异常处理**：严禁在 Controller/Service 中写大段的 `try-catch` 并手动返回错误信息。业务异常必须抛出自定义的 `BusinessException`，由 `@RestControllerAdvice` 全局拦截并封装为 `Result` 返回。
- **事务规范**：涉及多表修改必须加 `@Transactional(rollbackFor = Exception.class)`。事务方法必须是 `public`，严禁在事务内执行耗时的 RPC/HTTP 调用（大事务问题）。
- **参数校验**：必须在 DTO 上使用 JSR-380 注解（`@NotBlank`, `@NotNull` 等），并在 Controller 参数前加上 `@Validated`。

## 3. 前端开发规范 (React + TypeScript)

### 3.1 网络请求与状态管理
- **API 封装**：严禁在组件内直接使用 `fetch` 或裸写 `axios`。必须在 `/frontend/src/api/` 目录下按模块（如 `book.ts`）封装 API 请求，且与后端 Controller 一一对应。
- **全局拦截**：必须配置全局拦截器处理 Token 注入、全局 Loading、统一错误提示（Toast）和 401 跳转。
- **服务端状态**：推荐使用 **React Query (TanStack Query)** 或 **SWR** 管理服务端状态，替代传统的 `useEffect` + `useState` + `loading` 样板代码。

### 3.2 组件与逻辑分离 (Hooks 驱动)
- **严禁面条代码**：严禁在 React 组件内编写超过 50 行的复杂业务逻辑或嵌套的 `useEffect`。
- **Custom Hooks**：数据请求、复杂表单校验、状态派生必须抽离为 Custom Hooks (如 `useFetchBooks`, `useBookForm`)。
- **组件拆分**：如果生成的 React 组件超过 200 行，必须主动将其拆分为子组件。

### 3.3 类型与样式
- **TS 严格模式**：开启 `strict: true`。严禁使用 `any`。对于不确定的后端返回结构，使用 `unknown` 并进行类型守卫校验。组件 Props 必须使用 `interface` 显式定义。
- **样式规范**：统一使用 Tailwind CSS 或 CSS Modules。严禁混用内联样式 (`style={{}}`) 和全局 CSS 文件。提取通用 UI 组件到 `/components/ui`。

## 4. AI 行为准则与自我审查 (Self-Correction)
1. **全栈联动检查**：在生成前端 API 调用代码前，先确认后端对应的 Controller 和 DTO 是否已经存在或一并生成。
2. **依赖克制**：不要为了一个小功能随意引入新的 npm 包或 Maven 依赖，优先使用项目已有的工具库（如 lodash, dayjs, hutool）。
3. **清理无用代码**：修改逻辑时，必须彻底删除旧的、废弃的代码和 import，不要留下注释掉的“死代码”。
4. **安全底线**：前端代码中严禁硬编码任何后端密钥、Token 或敏感环境配置；后端代码严禁将密码、密钥明文打印到日志或返回给前端。
5. **规范优先**：如果用户的要求与上述规范冲突，请优先遵循上述规范，并在回复中温和地提醒用户架构上的隐患。

## 5. 项目代码设计准则
1. **LLM逻辑算法优先**: 当涉及到算法逻辑时, 优先判断是否通过LLM实现更有优势, 而不是无脑堆死代码;
2. **LLM逻辑判断兜底**: 当处理复杂内容时, 无法通过普通的文本处理, 请尽量使用LLM进行判断和修正;