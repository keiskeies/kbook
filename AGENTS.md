# AGENTS.md — KBook

AI-native reading platform. React 19 frontend + Spring Boot 3.4 backend.

---

## I. 项目速查（信息参考，被动查阅）

### 命令
| 位置 | 命令 | 说明 |
|------|------|------|
| `frontend/` | `npm run dev` | Vite dev server **port 15173**, proxy `/api` → `localhost:8181` |
| `frontend/` | `npm run build` | `tsc -b && vite build` → `dist/` |
| `backend/` | `mvn spring-boot:run` | starts on **port 8181** |
| `backend/` | `mvn clean package -DskipTests` | build JAR |
| `deploy/` | `docker compose up -d` | full stack, backend on 8080 |

### 基础设施
| Service | Dev Port | Docker Port |
|---------|----------|-------------|
| MySQL 8 | 3306 | 3306 |
| Redis 7 | 6379 | 6379 |
| ES 8.12 | 9201 | 9200 |
| Qdrant v1.12 | 6334 | 6334 |

### 后端关键配置
- DB `kbook-dev`, user `root`, pw `123456`
- Redis pw `123456`, db `4`
- JPA `ddl-auto: update`, JWT 2h/7d
- Admin: `admin@kbook` / `admin123456`

### 前端关键信息
- Zustand stores: `auth`, `reader`, `progress`, `tts`, `ui`
- Router: React Router 7, lazy pages, `AppLayout` + `BlankLayout`
- Path alias: `@/` → `src/`
- No test files despite `@playwright/test` in devDeps

### 架构摘要
- API 响应固定包装 `Result<T>` → `{code, message, data}`, `code=0` = success
- 循环依赖: `AiToolService → RecommendService → EmbeddingService → AiProviderConfigService`, 用 `@Lazy` 解决
- SSE 超时 1 小时（Vite proxy + Tomcat + Nginx 三处设置）
- ChatModelFactory 8 个无参方法，provider 自动检测

---

## II. 编码规范（AI 自然遵循，不强制检查）

以下规范我通常能自行遵守，你可以假定我默认会做到：

### 后端
- Controller 只管参数校验和路由，不含业务逻辑
- Service 管业务，Repository 管 DB
- Entity 不直接返回 API，必须经过 DTO/VO
- 异常用 `BusinessException`，由 `@RestControllerAdvice` 全局处理
- 多表修改加 `@Transactional(rollbackFor = Exception.class)`

### 前端
- API 调用统一在 `src/api/` 下封装，不裸写 `fetch`/`axios`
- TypeScript `strict: true`，不用 `any`
- 样式用 Tailwind CSS
- 组件 Props 用 `interface` 显式定义

### 全栈
- 后端改 DTO 时同步改前端 TS Interface
- 前后端统一 camelCase
- 不随意引入新依赖，优先用项目已有工具库

### LLM 使用原则
- 涉及算法时优先判断 LLM 是否更适合
- 复杂文本处理用 LLM 兜底

---

## III. 必须强制执行的规则（AI 容易跳过）

> ⚠️ 以下规则我**不会自动执行**——必须有你的显式提醒或系统的硬性拦截才能生效。每当你觉得我可能忽略了某条，直接引用编号提醒。

### R1: GitNexus 强制使用
**我默认用 `grep`/`Select-String` 翻代码，从不主动用 GitNexus。**

- **改任何函数/类/方法前**，先跑 `gitnexus_impact({target: "名称", direction: "upstream"})` 看调用方和风险
- **改完后提交前**，跑 `gitnexus_detect_changes()` 确认影响范围
- 搜索概念用 `gitnexus_query({query: "概念"})` 而不是 grep
- 360° 看调用链用 `gitnexus_context({name: "符号名"})`

### R2: 数据流全链路追溯
**我只改"看起来有问题"的那一层，不追源头。**

改任何涉及数据显示/存储/传递的问题前：
1. 从后端 Entity 字段 → DTO/VO 映射 → Controller → API 响应
2. 到前端 TypeScript interface → state/setState → 组件渲染
3. **确认每步数据都在**再动手。中间缺一环就是根因。

**反面案例**: 人格标题不显示，连改 5 轮前端渲染，根因是 `startStreaming` 占位消息根本没设 `personalityTitle`。

### R3: 改共享函数/组件先列全引用点
**改了签名或行为不查调用方。**

- 先用 grep 找到**所有**引用点，列清单
- 逐项确认兼容性后再改

### R4: 修改后自检清单
每次交差前：
1. `npm run build` / `mvn compile` 零错误？
2. 前后端联动 → 两端都编译了？
3. `setXxx` 插入的数据 → 刷新还在吗？
4. `useCallback` 递归 → 计数器用 `let` 了？
5. PC/手机版 `md:` 断点都对吗？

---

## IV. 反愚蠢规则（罚分卡 —— 每条对应实际 Bug）

> 这些规则来自对话中已发生的错误。每条有触发条件、强制规则、反面案例。

### A1. 前端状态 ≠ 持久化
**触发**: 通过 `setMessages` / `setState` 插入数据，不调后端 API。
**规则**: 每次插入时问自己"刷新还在吗？"。不在 → 调后端或重建。
**Bug**: 主持人消息全 `setMessages`，刷新消失 → 加 `insertHostMessages()` 重建。

### A2. 闭包陷阱
**触发**: `setTimeout(fn)` 递归，`fn` 读 state/props。
**规则**: 计数器用 `let` + `++`，禁止读闭包 state 的 `.length`。`isChainActiveRef` 检查放在所有回调最前面。
**Bug**: 自由辩论 `currentFreeMsgs.length` 永远为 0 → 用 `let freeCount` 就地 +1。

### A3. 预写 > LLM（确定性内容）
**触发**: 内容是固定的、可枚举的（主持词、环节名、辩手名）。
**规则**: 优先预写文本。只有需要实际总结/分析时才调 LLM。预写文本同时确保 A1 的重建逻辑。
**Bug**: 主持人 INTRO/TRANSITION 被 LLM 编造辩手观点、叫错名字。

### A4. UI 先读布局
**触发**: "在 XX 上加一个按钮"。
**规则**: 先读完整 JSX（含 `md:hidden` 等响应式标记），确定 PC/手机版分别渲染位置，再改。
**Bug**: 奇葩说按钮加到手机 header，实际在底部栏。

### A5. 辩论流程硬约束
- `OPENING_ORDER` 不含 HOST，主持人是独立步骤
- `CROSS_EXAM_ORDER` 只有 2 Q&A 对（4 轮）
- 所有回调链检查 `isChainActiveRef.current`
- 结束前必须调 `advanceDebateRound` 通知后端
- 主持人消息全前端预写，刷新重建

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **kbook** (6668 symbols, 16274 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "master"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

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
