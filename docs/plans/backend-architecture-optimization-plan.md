# 后端架构优化方案：结构治理与巨型类拆分

> 状态：**v2.1 评审稿（v2 已按代码逐条复核修订，见 §9；阶段 2 第 1 批已实施，mvn test 318 例全绿）**
> 范围：`backend/src/main/java`（仅后端，361 个 Java 文件 / 约 6.1 万行）
> 目标：解决评估发现的四类结构问题——巨型 Service、service/ai 职责混杂、循环依赖（19 处 `@Lazy`）、测试缺口；让代码库回到"敢改、能测、看得懂"的状态
> 不改动：技术栈（Spring Boot 3.4 + LangChain4j）、API 契约（`Result<T>` 包装、SSE 协议）、数据库结构、前端任何代码

---

## 0. 结论先行

**分层骨架是健康的，不需要推倒重来。** Controller → Service → Repository 纪律执行良好，DTO 按功能分包，Entity 不直接出 API。问题集中在四处局部失控，按"先建安全网、再动刀"的顺序治理：

| # | 问题 | 严重度 | 方案 | 状态 |
|---|------|--------|------|------|
| 1 | `service/ai` 31 个文件混 5 种职责 | 高 | 按职责分子包（roundtable/debate/rag/admin/core） | **✅ 已实施，待提交**（见 §2） |
| 2 | 测试缺口：推荐/AI/RAG 领域零覆盖（src/test 现有 263 例集中在 profile/审计域） | **最高** | 先给纯函数类补单测（零 mock），再给 LLM 依赖类补 Mockito 单测 | **第 1 批 ✅ 已实施**（55 例，§3），第 2 批待做 |
| 3 | 巨型 Service（RoundTableService 3308 行 / 88 个方法等 6 个 1200+ 行类） | 高 | 按职责拆分，RoundTableService 优先 | 待做（§4） |
| 4 | 19 处 `@Lazy` 循环依赖，核心结在「AI 配置服务反向持有聊天 Bean」 | 中高 | 配置变更通知事件化（复用已有 AiConfigCacheInvalidator 机制） | 待做（§5） |
| 5 | 包位置不一致小项（枚举三处、util 两处、config 过载、dto 残留） | 低 | 逐项机械清理 | 待做（§6） |

**执行顺序强制为 1 → 2 → 3 → 4 → 5**：没有测试安全网（§3）之前不动 §4 的拆分；循环依赖解耦（§5）放在拆分之后，避免边拆边解结。

**最重要的认知（必须提前对齐）：**

1. **测试缺口是第一风险，优先级高于一切重构。** 既有 263 例测试集中在 profile/审计域；而本方案要动的推荐匹配、AI 编排、SSE 回调链、辩论/圆桌状态机此前仍无任何回归保护——AGENTS.md 里的"反愚蠢罚分卡"每一条都是这个代价的实证。先补测试，拆分才有兜底。
2. **拆分全部保持类名与 Bean 语义不变**（移动/新建类，不改公开签名），Controller 与前端零感知；每步以 `mvn compile` 零错误 + 存量测试全绿为门禁。
3. **不做全盘目录重构。** 收益低、冲突面大；本方案所有动作都是局部手术，每项独立可回滚（独立 commit）。

---

## 1. 现状评估摘要（数据支撑）

### 1.1 巨型文件 Top 10（行数为 2026-10 阶段 1 重组后的实测值）

| 文件 | 行数 | 备注 |
|------|------|------|
| `service/roundtable/RoundTableService.java` | 3308 | **88 个方法定义**，角色推荐 + 会话 CRUD + 全局 Feed + 发言编排 + SSE 流式四种职责 |
| `service/ai/BookChatService.java` | 2321 | 图书问答全链路 |
| `service/book/BookParserService.java` | 2174 | 解析 + 速读 + 缓存失效 |
| `service/embedding/EmbeddingService.java` | 2110 | 向量全生命周期。**依赖面干净**：仅 QdrantClient、ChatModelFactory、QdrantProperties、BookRepository、事件发布器，不依赖任何 feature 服务 |
| `constants/AiPromptConstants.java` | 1817 | 全部 prompt 挤一个常量类 |
| `service/debate/DebateService.java` | 1508 | 辩论编排 |
| `service/recommend/RecommendService.java` | 1313 | 与 BookTrashService 互相 `@Lazy`（见 §1.2） |
| `service/recommend/RecommendMatchCalculator.java` | 1254 | **零字段、37 个 static 方法的纯计算类——最适合先补单测** |
| `service/roundtable/RoundTableCoverageService.java` | 1218 | 圆桌覆盖度 |
| `config/ChatModelFactory.java` | 804 | 8 个无参构建方法 + 场景解析 |

### 1.2 循环依赖实测（`@Lazy` 注解共 **19 处**，grep 全量剔除注释后统计）

**真正的环**（v1 版本诊断有误，v2 已按代码修正，见 §9）：

```
LangChain4jConfig 默认 AiAssistant Bean（:63-66，@Lazy+@Primary）
  └→ AiToolService（:57,:59 @Lazy EmbeddingService、RecommendService）
       └→ RecommendService（:81 @Lazy BookTrashService）
            └→ EmbeddingService ──→ ChatModelFactory（场景解析）
                  └→ AiProviderConfigService / AiSceneConfigService（ai/core）
                       └→ 反向持有聊天 Bean：ObjectProvider<AiToolService>、
                          ObjectProvider<BookAdminChatService>、AiChatMemory、
                          AiAssistant 实例缓存（按 configId，配置变更时清空重建）★环的闭合边
```

**其余 `@Lazy` 分布**（逐处核实过的用途）：

| 位置 | 依赖 | 实际用途 |
|------|------|----------|
| `RecommendService:79,:80` | RecommendCoefficientService、DimensionStatsService | 推荐系数/维度统计（环路径待逐个核对） |
| `BookTrashService:57` ↔ `RecommendService:81` | 互指 | 回收站删书→`removeSingleBook(:111)`；还原→`computeAndAddSingleBook(:154)`；推荐时→`getTrashedBookIds(:806)` 排除 |
| `BookService:84,:85`、`BookSearchService:80` | EmbeddingService、ChatModelManager | 入库建向量 / 语义检索（环经 ChatModelFactory 闭合） |
| `DynamicQueryService:58` | BookService | 动态查询工具（环经 AiToolService→ChatModelFactory 闭合） |
| `UserService:67,:71` | RecommendService、EmailNotificationService | 偏好变更后 `asyncRecompute(:338,:371)`；管理员审核邮件（:166-:259） |
| `AdminBookToolService:56` | BookScanService | 管理端图书扫描工具 |
| `DebateService:109,:110`、`RoundTableService:124,:125` | BookRepository 等 2 个 Repository | Repository 上的懒加载，疑似非环而是初始化顺序诉求，**保留不动** |

`@Lazy` 是止痛药：它推迟了初始化，但双向耦合仍在。核心结在 **`ai/core` 的两个配置服务为了「配置变更时清缓存重建 Assistant」而反向持有顶层聊天 Bean**——这一条边断了，环上大部分 `@Lazy` 都能拆掉。

### 1.3 包位置不一致清单

| 问题 | 现状 | 期望 |
|------|------|------|
| 枚举三处散落 | `entity/` 有 4 个独立枚举文件（CaptureMode、SourceStatus、NoteSourceType、NoteType，另有 4 个实体内嵌枚举）；根 `enums/` 只有 DebateRole；`common/enums/` 有 ConditionEnum + chart/ | 独立枚举统一收进 `common/enums/`（按需建子包） |
| util 两处 | 根 `util/` 仅 RefreshTokenCookieUtil 1 个；`common/util/` 9 个 | 合并到 `common/util/` |
| config/ 过载 | 顶层 29 个 .java（另有 ai/annotation/aspect/properties 4 个子包）混真配置 + 5 个安全 Filter + JwtUtil + **5 个 ChatModel 装饰器类**（CircuitBreaker×2、Retryable×1、UsageRecording×2）+ DataInitializer | Filter → `security/filter/`；装饰器 → `service/ai/core/model/` |
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
>
> **实施记录（2026-10-25）**：第 1 批 4 个类 55 例已落地并全绿——RecommendMatchCalculatorTest（23 例：归一化/覆盖因子/年龄分组/意图调制/关联链/匹配分边界与排序）、ScoreSqlBuilderTest（5 例）、ThoughtTagParserTest（10 例）、SpeechSanitizerTest（17 例）。全程零 mock（两个协作服务用匿名子类覆写）。

### 3.1 范围与优先序（按依赖成色分批，**已逐类核实构造器/字段**）

**第 1 批：纯函数类，零依赖零 mock，直接 new 出来测**

| 目标类 | 核实结果 | 测什么 |
|--------|----------|--------|
| `RecommendMatchCalculator` | 零字段、37 个 static 方法 | 打分边界：空值、满分、系数降权、排序稳定性 |
| `recommend/ScoreSqlBuilder` | @Component 但零字段、12 个 static 方法 | SQL 片段生成的转义与边界 |
| `ai/streaming/ThoughtTagParser` | 无状态工具、static 方法 | 思考标签解析：残缺标签、嵌套、空输入 |
| `common/util/SpeechSanitizer` | static 方法 | 语音文本清洗规则 |

**第 2 批：Mockito mock 掉 ChatModelFactory/ChatModelManager/Repository**

| 目标类 | 核实结果（需 mock 的依赖） | 测什么 |
|--------|---------------------------|--------|
| `ai/core/AiSceneConfigService` | 场景解析与缓存 | 配置解析、缓存失效、默认回退 |
| `rag/ListQueryDetector` | ChatModelFactory（**LLM 参与检测，非纯规则**） | 检测判定、LLM 失败回退 |
| `debate/DebateScoringService` | ChatModelManager + 3 个 Repository | 评分区间、分数持久化 |
| `ai/outline/AnswerOutlineService` | ChatModelFactory + ObjectMapper | 大纲解析：非法 JSON 回退 |
| `ai/behavior/BehaviorProfileExtractor` | ChatModelFactory + ObjectMapper | 画像抽取与截断 |
| `roundtable/RoundTableService`、`debate/DebateService` 的编排方法 | 较多（见 §4 拆分后逐子服务补） | 状态机：OPENING → CROSS_EXAM（2 组 Q&A）→ FREE → CLOSING；主持人独立步骤（AGENTS.md A5 硬约束） |

**第 3 批：切片/集成（可选，需单独评审）**
- Repository 查询、`Result<T>` 包装、JWT 过滤链
- 需要 Testcontainers（**新依赖**，与 AGENTS.md"不随意引入新依赖"冲突，单独决策）；若不做，第 1+2 批已足以支撑阶段 4

### 3.2 约束

- 第 1、2 批**不引入新依赖**：pom 已含 `spring-boot-starter-test`（test scope，JUnit5 + Mockito + AssertJ 全套）
- **先把 A2/A5 罚分卡场景写成用例**（自由辩论计数器、链路中断恢复），这些是已知爆过雷的地方，测试价值最高
- 不为覆盖率数字引依赖（jacoco 同样单独评审）

### 3.3 验收标准

- [x] `mvn test` 可运行且全绿（2026-10-25：318 例全绿 = 263 既有 + 55 本批新增）
- [x] 第 1 批 4 个类的核心分支有用例覆盖（以用例数为准，未引 jacoco）
- [ ] 辩论/圆桌状态机用例覆盖 AGENTS.md A5 全部硬约束（第 2 批）
- [ ] 后续阶段每次拆分后 `mvn test` 全绿作为合并门禁

---

## 4. 阶段 3（P1）：拆分巨型 Service

> 原则：**只拆职责边界、不改公开签名**。原类保留为门面（Facade）委托新类，Controller 与调用方零改动；拆完跑阶段 2 的测试，全绿才算完成。每拆一个类一个独立 commit。

### 4.1 RoundTableService（3308 行 / 88 个方法，最痛的一根刺，先行）

按已有 public 方法的天然边界拆成四块（行号为当前实测值）：

| 新类（均在 `service/roundtable/`） | 吸收的职责 | 现方法定位参考 |
|------|------|------|
| `RoundTableRoleService` | 角色推荐/选择/搜索 | `callAiForRoleSelection(:155)`、`callAiForRoleSearchQuery(:182)`、`getRecommendedRoles(:288)` |
| `RoundTableSessionService` | 会话 CRUD + 全局 Feed | `createSession(:787)`、`getSessions(:940)`、`verifySessionOwnership(:969)`、`getGlobalSessions(:1034)` |
| `RoundTableSpeechService` | 发言编排 + SSE 流式 | `getNextSpeakerOnlyLLM(:1218)`、`streamCharacterSpeak(:2025)` |
| `RoundTableService`（保留为门面） | 对 Controller 暴露的原签名全部保留，内部委托上面三个 | — |

**拆分纪律：**
- 门面方法逐个迁移，每迁一批（10~15 个方法）编译 + 测试一次，不搞一把梭
- 私有辅助方法跟随唯一调用者走；被多处共享的私有方法上移为包内工具类或下沉到对应子服务
- `isChainActiveRef` 类回调链（A2/A5 罚分卡）迁移时逐条核对回调入口检查

### 4.2 其余巨型类（按同样手法，排在 RoundTable 之后）

| 类 | 拆法 | 批次 |
|----|------|------|
| `BookChatService`（2321） | 按"会话管理 / RAG 检索编排 / 流式输出 / 追问生成（:1497 私有重载、:520 公有入口，天然边界）"四块拆 | P2 |
| `BookParserService`（2174） | "EPUB 解析 / 速读生成 / 缓存失效"三块拆 | P2 |
| `EmbeddingService`（2110） | "向量生成 / 批量任务 / Qdrant 读写"三块拆。**依赖面已干净（不依赖任何 feature 服务），拆分低风险** | P2 |
| `AiPromptConstants`（1817） | 按场景拆成 `constants/ai/` 下的多个常量类（BookQaPrompts、DebatePrompts、RoundTablePrompts…），纯文本移动 | P3，随时可做 |

### 4.3 验收标准

- [ ] 每个拆分 commit：`mvn compile` + `mvn test` 全绿，Controller 零改动
- [ ] RoundTableService 门面 ≤ 400 行，三个子服务各 ≤ 1200 行（继续瘦身留给后续迭代）
- [ ] `gitnexus_impact` 确认无新增 upstream 断裂

---

## 5. 阶段 4（P2）：解循环依赖

> 目标：`@Lazy` 从 19 处降到 **≤ 6 处**（保留 Debate/RoundTable 挂在 Repository 上的 4 处懒加载与 UserService 邮件通知 1 处，另留 1 处余量）。

### 5.1 主刀：配置变更通知事件化（断开环的闭合边）

**现状**（已核实）：`ai/core/AiProviderConfigService` 为了「AI 配置变更时清缓存重建」，直接持有 `ObjectProvider<AiToolService>`、`ObjectProvider<BookAdminChatService>`、`AiChatMemory`，并维护按 configId 缓存的 `AiAssistant` 实例表——这就是 `ai/core → 顶层聊天 Bean` 的反向依赖，整个大环由它闭合。

**修复**：`config/AiConfigCacheInvalidator` **已经有一套可复用的失效机制**（Redis 版本号 + `register(Runnable)` 监听器注册 + `pollRemoteVersion()` 轮询 + `fire(reason)`），把「配置变更 → 重建聊天 Bean」的触发改为走这套监听器或 Spring `ApplicationEvent`：

1. AiProviderConfigService 只发 `AiConfigChangedEvent`，不再引用任何聊天 Bean
2. 聊天 Bean 的持有方（LangChain4jConfig、AiChatMemory 等）自己监听并清缓存
3. 环断开后，环上为打补丁而生的 `@Lazy` 逐个摘除：AiToolService×2、AiProviderConfigService×1、BookService×2、BookSearchService×1、DynamicQueryService×1、AdminBookToolService×1、LangChain4jConfig×1（每摘一个先 `mvn compile` 确认 Spring 启动无环告警）

### 5.2 次刀：两处互指/回调用事件解耦

- **RecommendService ↔ BookTrashService 互指**：回收站的删书/还原改为发 `BookTrashedEvent` / `BookRestoredEvent`，recommend 侧监听执行 `removeSingleBook` / `computeAndAddSingleBook`；推荐侧保留对 `getTrashedBookIds` 的**单向**依赖（同步排除过滤，非环，去掉 `@Lazy` 直接注入）
- **UserService → RecommendService**：偏好/画像变更后的 `asyncRecompute(:338,:371)` 改发 `UserPreferenceChangedEvent`（`service/embedding/event/RagHitEvent` 已证明项目在用 `ApplicationEventPublisher`，事件模式有先例——注意 RagHitEvent 是 RAG 命中埋点用途，解耦用的事件需新建）
- `RecommendService:79,:80`（CoefficientService、DimensionStatsService）环路径**待逐个核对**后再决定去留

### 5.3 冻结增量

- 评审规则写入 AGENTS.md：**新增 `@Lazy` 必须在 PR 说明打破的是哪个环、为什么不能用事件解耦**
- 每季度盘点一次 `@Lazy` 数量，只减不增

### 5.4 验收标准

- [ ] `grep -rn "@Lazy" backend/src/main/java --include="*.java" | grep -vE ":[0-9]+:\s*(\*|//|/\*)" | wc -l` ≤ 6
- [ ] 新增事件监听不改变现有行为（删书→推荐移除仍生效，用阶段 2 测试验证）
- [ ] 应用启动无 Bean 依赖环告警

---

## 6. 阶段 5（P3）：小项清理（每项一个 commit，可穿插进行）

| 项 | 动作 | 风险 |
|----|------|------|
| util 合并 | `util/RefreshTokenCookieUtil` → `common/util/`（纯移动 + import） | 极低 |
| 枚举统一 | `entity/` 4 个独立枚举文件 + 根 `enums/DebateRole` → `common/enums/`；JPA `@Enumerated` 按枚举常量名持久化，不受包位置影响，纯移动 | 极低 |
| config 拆分 | JwtAuthenticationFilter、RateLimitFilter、SecurityHeaderFilter、RequestSecurityFilter、ClientIpFilter、JwtUtil → `config/security/`；CircuitBreakerChatModel、CircuitBreakerStreamingChatModel、RetryableChatModel、UsageRecordingChatModel、UsageRecordingStreamingChatModel（5 个装饰器）→ `service/ai/core/model/` | 低（同阶段 1 手法） |
| dto 残留 | `dto/request`、`dto/response` 3 个文件归位到 feature dto 包 | 极低 |
| backfill 处置 | BehaviorProfileBackfillRunner：确认已执行完毕后删除（git 里随时找回）；未跑完则移入 `service/admin/` | 极低 |

---

## 7. 排期建议与里程碑

| 里程碑 | 内容 | 规模估算 |
|--------|------|----------|
| M1（本周可完成） | 阶段 1 提交；阶段 2 第 1 批纯函数单测 | 0.5~1 天 |
| M2 | 阶段 2 第 2 批 Mockito 单测 | 2~3 天 |
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

---

## 9. v2 复核修订记录（2026-10-25，逐条对照代码核实）

| # | v1 错误 | v2 修正（依据） |
|---|---------|----------------|
| 1 | `@Lazy` 写"20 处" | 实测 **19 处注解**（grep 命中 21 行，其中 UserService:65、:69 两行是 javadoc 注释） |
| 2 | **循环依赖诊断错误**：称"EmbeddingService 与 feature 层双向纠缠，需降层" | EmbeddingService 只依赖 QdrantClient/ChatModelFactory/QdrantProperties/BookRepository/事件发布器，**不依赖任何 feature 服务**。真正的环：`ai/core` 配置服务为「配置变更重建聊天 Bean」反向持有 ObjectProvider\<AiToolService\> 等（AiProviderConfigService:44-60 实测）。方案从"降层"改为"配置变更事件化 + 复用已有 AiConfigCacheInvalidator" |
| 3 | **第 1 批"纯函数零依赖"名单大半错误**：ListQueryDetector、DebateScoringService、AnswerOutlineService、BehaviorProfileExtractor 都标成了纯函数 | 逐类核实构造器：这 4 个全部注入 ChatModelFactory/ChatModelManager/Repository，移入第 2 批（Mockito）。真纯函数：RecommendMatchCalculator（0 字段 37 static）、ScoreSqlBuilder（12 static）、ThoughtTagParser、SpeechSanitizer |
| 4 | UserService 用途写成"注册后初始化偏好" | 实测是偏好/画像变更后 `asyncRecompute`（:338、:371）+ 管理员审核邮件（:166-:259） |
| 5 | RoundTableService "3303 行 / 124 个方法" | 行数 3303→**3308**（阶段 1 加了 import）；方法数当初统计口径含字段行，按方法定义重数为 **88 个** |
| 6 | 行号引用是重组前的旧值（如 getRecommendedRoles:283、追问生成:1488） | 全部按当前文件重测：getRecommendedRoles:288、createSession:787、getNextSpeakerOnlyLLM:1218、streamCharacterSpeak:2025、generateFollowUpQuestions:520/:1497 等 |
| 7 | 小项误差：ChatModel 装饰器"四件套"（实为 5 个类）、common/util"10 个"（实为 9 个）、`embedding/event` 被描述为"向量入库事件先例"（实为 RagHitEvent 埋点） | 均已修正；RagHitEvent 保留为"项目已在用 ApplicationEvent"的先例引用，解耦事件需新建 |

> 复核方法：`@Lazy` 与依赖边用构造器/字段逐处 grep；纯函数判定用"类注解 + 字段声明 + static 方法数"三查；行号用当前文件 `grep -n` 重测；pom 依赖读 `backend/pom.xml` 原文。

**v2.1 补记（2026-10-25）**：评估时"`src/test` 为空"的前提已过时——其间其他迭代补入了 263 例（profile/审计域，见 AdminRangeRulesTest、BookProfileCalculatorTest 等）。§0/§3 的测试现状描述已按实际修正；缺口重定位为"推荐/AI/RAG 域零覆盖"，由第 1 批 55 例开始填补。
