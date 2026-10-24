# 后端架构优化方案：结构治理与巨型类拆分

> 状态：**v1 评审稿**
> 范围：`backend/src/main/java`（仅后端，361 个 Java 文件 / 约 6.1 万行）
> 目标：解决评估发现的四类结构问题——巨型 Service、service/ai 职责混杂、循环依赖（20 处 `@Lazy`）、零测试；让代码库回到"敢改、能测、看得懂"的状态
> 不改动：技术栈（Spring Boot 3.4 + LangChain4j）、API 契约（`Result<T>` 包装、SSE 协议）、数据库结构、前端任何代码

---

## 0. 结论先行

**分层骨架是健康的，不需要推倒重来。** Controller → Service → Repository 纪律执行良好，DTO 按功能分包，Entity 不直接出 API。问题集中在四处局部失控，按"先建安全网、再动刀"的顺序治理：

| # | 问题 | 严重度 | 方案 | 状态 |
|---|------|--------|------|------|
| 1 | `service/ai` 31 个文件混 5 种职责 | 高 | 按职责分子包（roundtable/debate/rag/admin/core） | **✅ 已实施，待提交**（见 §2） |
| 2 | 零测试（`src/test` 为空） | **最高** | 先给纯逻辑核心类补单测（零新依赖，Mockito 即可） | 待做（§3） |
| 3 | 巨型 Service（RoundTableService 3303 行 / 124 方法等 6 个 1200+ 行类） | 高 | 按职责拆分，RoundTableService 优先 | 待做（§4） |
| 4 | 20 处 `@Lazy` 循环依赖 | 中高 | EmbeddingService 降层 + 事件解耦，冻结新增 | 待做（§5） |
| 5 | 包位置不一致小项（枚举三处、util 两处、config 过载、dto 残留） | 低 | 逐项机械清理 | 待做（§6） |

**执行顺序强制为 1 → 2 → 3 → 4 → 5**：没有测试安全网（§3）之前不动 §4 的拆分；循环依赖解耦（§5）放在拆分之后，避免边拆边解结。

**最重要的认知（必须提前对齐）：**

1. **零测试是第一风险，优先级高于一切重构。** 6 万行代码、`@Lazy` 注入 + SSE 回调链 + 状态机式业务，没有任何回归保护——AGENTS.md 里的"反愚蠢罚分卡"每一条都是这个代价的实证。先补测试，拆分才有兜底。
2. **拆分全部保持类名与 Bean 语义不变**（移动/新建类，不改公开签名），Controller 与前端零感知；每步以 `mvn compile` 零错误 + 存量测试全绿为门禁。
3. **不做全盘目录重构。** 收益低、冲突面大；本方案所有动作都是局部手术，每项独立可回滚（独立 commit）。

---

## 1. 现状评估摘要（数据支撑）

### 1.1 巨型文件 Top 10

| 文件 | 行数 | 备注 |
|------|------|------|
| `service/ai/RoundTableService.java` | 3303 | **124 个方法**，角色推荐 + 会话 CRUD + 全局 Feed + 发言编排 + SSE 流式四种职责 |
| `service/ai/BookChatService.java` | 2312 | 图书问答全链路 |
| `service/book/BookParserService.java` | 2174 | 解析 + 速读 + 缓存失效 |
| `service/embedding/EmbeddingService.java` | 2110 | 向量全生命周期，被 3+ 个 feature 服务 `@Lazy` 反向依赖 |
| `constants/AiPromptConstants.java` | 1817 | 全部 prompt 挤一个常量类 |
| `service/ai/DebateService.java` | 1506 | 辩论编排（已随阶段 1 移入 `service/debate/`） |
| `service/recommend/RecommendService.java` | 1313 | 与 BookTrashService 互相 `@Lazy` |
| `service/recommend/RecommendMatchCalculator.java` | 1254 | 纯计算逻辑，**最适合先补单测的类** |
| `service/ai/RoundTableCoverageService.java` | 1217 | 已移入 `service/roundtable/` |
| `config/ChatModelFactory.java` | 804 | 8 个无参构建方法 + 场景解析 |

### 1.2 循环依赖实测（`@Lazy` 共 20 处，不止 AGENTS.md 记录的 1 条）

```
AiToolService ──@Lazy──→ RecommendService ──@Lazy──→ BookTrashService ──@Lazy──→ RecommendService（互指）
AiToolService ──@Lazy──→ EmbeddingService ←──@Lazy── BookService / BookSearchService
RecommendService ──@Lazy──→ RecommendCoefficientService / DimensionStatsService / BookTrashService
UserService ──@Lazy──→ RecommendService
DebateService / RoundTableService ──@Lazy──→ BookRepository
```

`@Lazy` 是止痛药：它推迟了初始化，但双向耦合仍在，每个新增 `@Lazy` 都在加重"改一处、崩一片"的风险。

### 1.3 包位置不一致清单

| 问题 | 现状 | 期望 |
|------|------|------|
| 枚举三处散落 | `entity/` 混入 CaptureMode、SourceStatus、NoteSourceType、NoteType；根 `enums/` 只有 DebateRole；`common/enums/` 有 ConditionEnum + chart/ | 统一收进 `common/enums/`（按需建子包） |
| util 两处 | 根 `util/` 仅 RefreshTokenCookieUtil 1 个；`common/util/` 10 个 | 合并到 `common/util/` |
| config/ 过载 | 29 个文件混真配置 + 5 个安全 Filter + JwtUtil + ChatModel 装饰器四件套（CircuitBreaker/Retryable/UsageRecording×2）+ DataInitializer | Filter → `security/filter/`；装饰器 → `service/ai/core/model/` |
| dto 残留 | `dto/request/` 2 个、`dto/response/` 1 个，与按功能分包的主流不符 | 归位到对应 feature dto 包 |
| 一次性包 | 根 `backfill/` 仅 BehaviorProfileBackfillRunner | 已跑完则删除；未跑完移入 `service/admin/` |

---

## 2. 阶段 1：service/ai 分子包重组 ✅（已实施，待提交）

> 本阶段已在工作区完成，未提交。40 个文件变更（+92/−59，全部为包声明与 import 行，零逻辑改动），21 个文件以 git rename（RM）保留历史，`mvn compile` 361 文件零错误，GitNexus 索引已重建。

### 2.1 变更结果

| 位置 | 内容 |
|------|------|
| `service/roundtable/`（新 feature 包，与 book/note 同级） | RoundTableService、RoundTableCoverageService、RoundTableReportService |
| `service/debate/`（新 feature 包，呼应 entity/debate、repository/debate） | DebateService、DebateReportService、DebateScoringService |
| `service/ai/rag/` | ListQueryDetector、ListQueryRetriever、ListQueryStrategySelector、ItemListRetriever、RagAnswerCache、RagPipelineComponents |
| `service/ai/admin/` | BookAdminChatService、BookAdminAssistant、BookAdminChatMemory、AdminBookToolService |
| `service/ai/core/` | 并入 ChatModelManager、AiProviderConfigService、AiSceneConfigService、AiUsageLogService、AiUsageContext |
| `service/ai/` 根目录 | 31 → 10 个文件（场景聊天 + 助手工具） |

### 2.2 验收标准（已达成）

- [x] `mvn compile` 零错误
- [x] 移动文件全部为 rename 状态，git blame 历史可追溯
- [x] `gitnexus_detect_changes` 变更全部为 import/包声明行（报告 critical 为 40 文件广度告警，属预期噪音）
- [ ] 提交前人工 `git diff` 复核（建议逐文件过一遍，重点看 ChatModelFactory、BookChatService 的 import 块）

---

## 3. 阶段 2（P0）：核心链路单测补齐

> **为什么最先做**：阶段 4 要拆 3300 行的类，没有回归保护就是赌博。测试是后续所有重构的准入条件。

### 3.1 范围与优先序（按"纯逻辑、高价值、易测"三标准筛选）

| 批次 | 目标类 | 测什么 | 依赖 |
|------|--------|--------|------|
| 第 1 批（纯函数，零依赖） | `RecommendMatchCalculator`（1254 行）、`rag/ListQueryDetector`、`debate/DebateScoringService`、`ai/outline/AnswerOutlineService`、`behavior/BehaviorProfileExtractor` | 打分边界（空值/满分/降权）、列表问句检测规则、评分区间、大纲解析 | 纯 JUnit + 断言 |
| 第 2 批（Mockito， mock 掉 Repository/ChatModelManager） | `roundtable/RoundTableService` 的发言编排、`debate/DebateService` 的轮次推进、`ai/core/AiSceneConfigService` 的场景解析与缓存 | 状态机转换：OPENING → CROSS_EXAM（2 组 Q&A）→ FREE → CLOSING；主持人步骤独立（对应 AGENTS.md A5 硬约束）；缓存失效 | spring-boot-starter-test（已有）+ Mockito（已有） |
| 第 3 批（切片/集成，可选） | Repository 查询、`Result<T>` 包装、鉴权过滤器 | JPA 查询正确性、JWT 过滤链 | Testcontainers（**新依赖，需单独评审**）或先跳过 |

### 3.2 约束

- **不引入新依赖**做第 1、2 批：`spring-boot-starter-test` 已在 pom（JUnit5 + Mockito + AssertJ 全套自带）
- 第 3 批 Testcontainers 需要单独决策（AGENTS.md 全栈规范：不随意引入新依赖）；若不做，第 1+2 批已足以支撑阶段 4 的拆分
- **先把 A2/A5 罚分卡场景写成用例**（自由辩论计数器、链路中断恢复），这些是已知爆过雷的地方，测试价值最高

### 3.3 验收标准

- [ ] `mvn test` 可运行且全绿（当前为空跑）
- [ ] 第 1 批 5 个类的核心分支覆盖（语句覆盖 ≥ 70%，用 jacoco 的话需新依赖——**建议先用 IDEA 覆盖率或干脆以用例数为准，不引依赖**）
- [ ] 辩论/圆桌状态机用例覆盖 AGENTS.md A5 全部硬约束
- [ ] 后续阶段每次拆分后 `mvn test` 全绿作为合并门禁

---

## 4. 阶段 3（P1）：拆分巨型 Service

> 原则：**只拆职责边界、不改公开签名**。原类保留为门面（Facade）委托新类，Controller 与调用方零改动；拆完跑阶段 2 的测试，全绿才算完成。每拆一个类一个独立 commit。

### 4.1 RoundTableService（3303 行，最痛的一根刺，先行）

按已有 public 方法的天然边界拆成四块：

| 新类（均在 `service/roundtable/`） | 吸收的职责 | 现方法定位参考 |
|------|------|------|
| `RoundTableRoleService` | 角色推荐/选择/搜索 | `callAiForRoleSelection(:150)`、`callAiForRoleSearchQuery(:177)`、`getRecommendedRoles(:283)` |
| `RoundTableSessionService` | 会话 CRUD + 全局 Feed | `createSession(:782)`、`getSessions(:935)`、`verifySessionOwnership(:964)`、`getGlobalSessions(:1029)` |
| `RoundTableSpeechService` | 发言编排 + SSE 流式 | `getNextSpeakerOnlyLLM(:1213)`、`streamCharacterSpeak(:2020)` |
| `RoundTableService`（保留为门面） | 对 Controller 暴露的原签名全部保留，内部委托上面三个 | — |

**拆分纪律：**
- 门面方法逐个迁移，每迁一批（10~15 个方法）编译 + 测试一次，不搞一把梭
- 私有辅助方法跟随唯一调用者走；被多处共享的私有方法上移为包内工具类或下沉到对应子服务
- `isChainActiveRef` 类回调链（A2/A5 罚分卡）迁移时逐条核对回调入口检查

### 4.2 其余巨型类（按同样手法，排在 RoundTable 之后）

| 类 | 拆法 | 批次 |
|----|------|------|
| `BookChatService`（2312） | 按"会话管理 / RAG 检索编排 / 流式输出 / 追问生成（:1488 已是天然边界）"四块拆 | P2 |
| `BookParserService`（2174） | "EPUB 解析 / 速读生成 / 缓存失效"三块拆 | P2 |
| `EmbeddingService`（2110） | 与阶段 5 降层联动："向量生成 / 批量任务 / Qdrant 读写"三块拆 | P2（在阶段 5 之后） |
| `AiPromptConstants`（1817） | 按场景拆成 `constants/ai/` 下的多个常量类（BookQaPrompts、DebatePrompts、RoundTablePrompts…），纯文本移动 | P3，随时可做 |

### 4.3 验收标准

- [ ] 每个拆分 commit：`mvn compile` + `mvn test` 全绿，Controller 零改动
- [ ] RoundTableService 门面 ≤ 400 行，三个子服务各 ≤ 1200 行（继续瘦身留给后续迭代）
- [ ] `gitnexus_impact` 确认无新增 upstream 断裂

---

## 5. 阶段 4（P2）：解循环依赖

> 目标：`@Lazy` 从 20 处降到 ≤ 5 处（保留确有必要的懒加载，消灭"为打破环"而存在的）。

### 5.1 手法一：EmbeddingService 降层（主刀）

`service/embedding/` 已是独立包，问题在于 feature 层与它双向纠缠。目标分层：

```
L0 基础设施：ai/core（ChatModelManager、Provider/Scene Config）
L1 能力层：  embedding、tts、speech、storage   ← 只依赖 L0 和 Repository
L2 feature：book、recommend、roundtable、debate… ← 依赖 L1，L1 不得反向依赖 L2
```

- EmbeddingService 中"书入库后建向量、画像联动失效"这类**反向通知改为 Spring 事件**——`service/embedding/event/` 包已存在（先例可循），扩展即可
- RecommendService ↔ BookTrashService 互指：删除书籍改为发 `BookDeletedEvent`，由 recommend 侧监听清理偏好数据
- `UserService ─@Lazy→ RecommendService`：注册后初始化偏好改为事件或异步

### 5.2 手法二：冻结增量

- 评审规则写入 AGENTS.md：**新增 `@Lazy` 必须在 PR 说明打破的是哪个环、为什么不能用事件/降层解决**
- 每季度盘点一次 `@Lazy` 数量，只减不增

### 5.3 验收标准

- [ ] `grep -rn "@Lazy" backend/src/main/java | wc -l` ≤ 5
- [ ] 新增事件监听不改变现有行为（删除书 → 偏好清理仍生效，用阶段 2 测试验证）
- [ ] 启动无 Bean 依赖环告警

---

## 6. 阶段 5（P3）：小项清理（每项一个 commit，可穿插进行）

| 项 | 动作 | 风险 |
|----|------|------|
| util 合并 | `util/RefreshTokenCookieUtil` → `common/util/`（纯移动 + import） | 极低 |
| 枚举统一 | `entity/` 内 4 个枚举 + 根 `enums/DebateRole` → `common/enums/`；JPA `@Enumerated` 不受包位置影响，纯移动 | 极低 |
| config 拆分 | JwtAuthenticationFilter、RateLimitFilter、SecurityHeaderFilter、RequestSecurityFilter、ClientIpFilter、JwtUtil → `config/security/`；CircuitBreaker/Retryable/UsageRecording 四件套 + ChatModelFactory 相关装饰器 → `service/ai/core/model/` | 低（同阶段 1 手法） |
| dto 残留 | `dto/request`、`dto/response` 3 个文件归位到 feature dto 包 | 极低 |
| backfill 处置 | BehaviorProfileBackfillRunner：确认已执行完毕后删除（git 里随时找回）；未跑完则移入 `service/admin/` | 极低 |

---

## 7. 排期建议与里程碑

| 里程碑 | 内容 | 规模估算 |
|--------|------|----------|
| M1（本周可完成） | 阶段 1 提交；阶段 2 第 1 批纯逻辑单测 | 0.5~1 天 |
| M2 | 阶段 2 第 2 批状态机/编排单测（Mockito） | 2~3 天 |
| M3 | 阶段 4.1 拆 RoundTableService | 2~3 天（含回归验证） |
| M4 | 阶段 5 解循环依赖 + EmbeddingService 拆分 | 2~3 天 |
| M5 | BookChatService / BookParserService 拆分 + 阶段 6 清理 | 3~4 天 |

> 规模按"AI 结对 + 人工复核"节奏估算；单人纯手做约 ×2。M3 之后每步都有测试兜底，节奏可按业务迭代穿插，不必连续投入。

---

## 8. 明确不做的事

1. **不换框架、不升级大版本**（Spring Boot 3.4 / LangChain4j 维持现状）
2. **不引入 DDD 全套战术模式**（聚合根/值对象改造对当前规模是过度设计，分层 + 门面足够）
3. **不做多模块拆分**（Gradle/Maven 多 module 或 Modulith —— 等循环依赖清零、测试齐了再评估，现在做是搬家不是治病）
4. **不动 API 契约与前端**（所有阶段对 `/api` 消费者透明）
5. **不为覆盖率数字引依赖**（jacoco/Testcontainers 单独评审，不让"测好"变成"装好"）
