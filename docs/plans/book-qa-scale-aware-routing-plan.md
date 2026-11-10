# 图书问答按书规模分档路由 + 准确率优化实施方案

> 状态：**P0 / P1 / P2 已实施**（2026-11-10；落地清单见 §10，设计原文保留供对照） · 日期：2026-11-10
> 范围：`POST /api/books/{bookId}/chat/stream` 主链路、LLM 请求链、检索快照与离线评测
> 行号基准：2026-11-10 代码快照
> 关联：[`book-qa-accuracy-grounding-and-eval-plan.md`](./book-qa-accuracy-grounding-and-eval-plan.md)（事实边界/评测）、[`book-qa-iterative-generation-plan.md`](./book-qa-iterative-generation-plan.md)（列表流水线）、[`rag-ingestion-and-chunking-plan.md`](./rag-ingestion-and-chunking-plan.md)（分块与章节结构）、[`ai-cache-bust-plan.md`](./ai-cache-bust-plan.md)（nonce 层）、[`../eval/README.md`](../eval/README.md)（评测口径）

---

## 0. 结论先行

用户提出的判据成立且比现有实现更准确：**可用空间 = 模型上下文 − 系统/画像 − 历史问答 − 输出预留**，书远小于该空间时整本直塞优于检索。三路只读核查后，方案重心从「补检索算法」调整为「先让分档跑得起来、再谈算法」：

| 发现 | 对方案的影响 |
|---|---|
| `books.full_text`（`MEDIUMTEXT`）已持久化全书原文 | 档 A 的数据源现成，无需从 Qdrant 拼 chunk |
| `books` 已有 `toc` / `chapter_summary` / `compressed_summary` / `description` | 档 B 的章节定位、合规语料替换都是「接线」，不是「从零生成」 |
| `cache-bust-enabled` 默认 `true`，每次请求注入随机 nonce | **当前配置下整本直塞没有任何缓存收益**；但该层是临时排查措施，文档 §5 明确允许关闭 |
| `ListQueryStrategySelector` 已有 200 / 2000 chunks 分档 | 骨架可复用，但只对列表型问题生效、且三档都是「怎么检索」 |
| `AiProviderConfig.maxTokens` 种子 32768，与真实模型 128K/1M 不符 | 阈值公式的分子是错的，必须先修数据 |
| 目录/摘要已注入 UserMessage，但未进 Qdrant payload | 合规改造与检索增强可以一次性做完 |

**一句话**：分 P0（清障，零风险）、P1（三档路由主体）、P2（章节档 + 合规 + 闭环）三批，每批都能独立上线、独立 A/B、独立回滚；**先清障再分档，不要在错误的缓存与错误的上下文长度上标定阈值**。

### 0.1 明确不做

lambdaMART / LTR（无点击日志与标注样本）、GraphRAG（单书单跳）、cross-encoder 重排（无推理服务，桌面形态再评估）、绝对余弦阈值（embedding 可热切换，分数不可校准）、重做分块与微调 embedding（重向量化成本高）、Agentic RAG 循环。理由见 `book-qa-accuracy-grounding-and-eval-plan.md` §7.2。

---

## 1. 现状基线（本次核查确认）

| 能力 | 位置 | 现状 |
|---|---|---|
| 全书原文 | `entity/Book.java:133-135` | `fullText` 持久化 `MEDIUMTEXT`；`ragContent`/`parsedContent` 才是 `@Transient` |
| 结构化信息 | `Book.java:42-91` | `description` / `toc` / `chapterSummary` / `compressedSummary` / `tocQualityScore` |
| 书籍信息注入 | `service/ai/BookChatService.java:617-665` | 每轮拼成独立 UserMessage；**`:640-649` 混入 `rating` / `readCount` 动态值** |
| 消息顺序 | `BookChatService.java:462-513` | System → bookInfo → 历史 → RAG → 画像 → 问题；注释称「优化 KV Cache」，与 nonce 层矛盾 |
| 缓存破坏 | `application.yml:291`、`AiModelProperties.java:30`、`ChatModelFactory.java:582-588` | 默认开；`CacheBustingSupport.inject()` 在首条 System 前加随机 nonce |
| 检索入口 | `BookChatService.java:231-251` | `doRagRetrieval(...)`；`retrievalAttempted` 用于拒答判定 |
| 分档骨架 | `service/ai/rag/ListQueryStrategySelector.java:38-40, 93-106` | 200 / 2000 chunks；仅 `BookChatRagService.java:256-265` 列表型分支调用 |
| 上下文预算 | `BookChatRagService.java:128-131, 167-198` | `getRagMaxChars() = maxTokens × 1.5 × 0.6`；`rag-max-chars` 默认 0 |
| 历史预算 | `BookChatSessionService.java:675-684` | `contextCharLimit() = maxTokens × 1.5`；压缩触发 0.8 |
| 输出上限 | `ChatModelFactory.java:199-202` | `min(desired, contextTokens / 2)` |
| 快照/证据 | `entity/AiRetrievalSnapshot.java:42-59`、`BookChatService.java:392`、`BookChatController.java:151-162` | `chunkIndex` 非空、`score`、`source`（`context`/`neighbor`/`anchor`/`llm_refine`/`list`） |
| 评测资产 | `docs/eval/`、`scripts/eval-bookqa*.mjs` | 黄金集 14 例有效（book 24689 / 273 chunks 人工标注）+ 19 例阈值探针 + 26 份 A/B 报告 |

> **口径提示**：现有 `TOKEN_TO_CHAR_RATIO = 1.5` 表示「1 token ≈ 1.5 中文字符」，与 `application.yml:266` 的「12 万字 ≈ 84K tokens」一致。该系数是按项目当前模型实测的经验值，**换 tokenizer 或换厂商后必须重新标定**，否则分档阈值整体偏移。

---

## 2. 架构设计

### 2.1 统一预算（新增 `ContextBudget`）

现状是三套独立系数（`×0.6`、`/2`、`×0.8`），没有用户提出的那条减法。新增一个纯计算类，作为档位判定与所有截断的唯一来源：

```
contextTokens  = resolveConfig(BOOK_QA).maxTokens        // P0-3 修正后为真实窗口
outputTokens   = effectiveMaxOutputTokens(...)            // ChatModelFactory:199-202
charPerToken   = 1.5                                     // 可配置，见 P0-3
grossChars     = (contextTokens - outputTokens) * charPerToken
overheadChars  = systemLen + staticBookInfoLen + behaviorLen
                 + questionLen + historyChars + 2000(回复预留)
availableChars = grossChars - overheadChars - grossChars * safetyMargin(0.15)
```

`historyChars` 取 `loadAndCompressHistory()` 已有的 `totalChars`（`BookChatSessionService.java:468-474`），无需新增查询。

### 2.2 三档判定（新增 `BookScaleRouter`）

```
if (!scaleRoutingEnabled || fullText == null || fullText.length() > fullTextMaxChars)
        → LARGE                                     // 开关/兜底
mode = sessionScaleLock.get(sessionId)               // 见 2.3
if (mode != null) return mode
if (fullText.length() <= availableChars * smallBookRatio)  → SMALL
if (chapterLocated && chapterLen <= mediumChapterMaxChars)  → MEDIUM
else                                                        → LARGE
```

| 档 | 条件 | 做法 | 检索 |
|---|---|---|---|
| **SMALL** | 全书 ≤ 可用空间 60% | `full_text` 作为静态前缀整本注入 | 跳过 |
| **MEDIUM** | 装不下，但问题可定位到章节 | `TextStructureExtractor` 字符偏移 → `fullText.substring` 整章注入 | 跳过（P2） |
| **LARGE** | 其余 | 现有 `doRagRetrieval` 全链路 | 保留 |

`smallBookRatio = 0.6` 与 `safetyMargin = 0.15` 是**起步值，必须用黄金集 A/B 标定**，不是拍定值。对应此前判断「阈值取可用空间 50%~60%」。

### 2.3 按会话锁定档位（必须做）

`availableChars` 随历史增长而减小，若每轮重新判定，一本书会在会话中途从 SMALL 掉到 LARGE，**前缀随之改变，缓存收益归零**。因此：会话首轮定档后写进会话级状态（复用 `ai_sessions` 增列，或 Caffeine/Redis 按 `sessionId` 缓存；桌面形态优先本地缓存），后续轮次沿用；仅「切换书籍 / 新建会话 / 显式重置」时重算。档位写进 `ai_usage_log` 便于 A/B 分组。

### 2.4 静态前缀与动态信息拆分（P0-2）

缓存命中的必要条件是**前缀逐字节稳定**。`BookChatService.java:610-612` 的注释声称 `buildBookInfoPrompt` 是「纯静态信息」，但 `:640-649` 拼了 `rating` 与 `readCount`（随用户行为变化），`:658-661` 的 `compressedSummary` 又是首轮懒生成（`:591-606`）——三者都会让前缀漂移。

改造后的消息顺序：

```
1. SystemMessage        固定约束（按 style，同一用户稳定）
2. UserMessage          【静态书籍语料】书名/作者/目录/摘要 + 档A的全文   ← 缓存前缀
3. HistoryMessages      历史对话
4. UserMessage          【动态书籍信息】评分/阅读量/标签（从 2 中移出）
5. UserMessage          读者画像
6. UserMessage          问题 + 回答契约（按档切换，见 2.5）
```

`compressedSummary` 建议**改为导入时预生成**（P0-7），消除首轮与后续轮次的前缀差异。

### 2.5 档 A 的兼容处理（最容易漏的一环）

跳过检索会连带影响四套既有机制，必须同时改，否则评测与引用全乱：

| 依赖 | 问题 | 处理 |
|---|---|---|
| `ai_retrieval_snapshots` | `chunk_index NOT NULL`，无 chunk 可写 | 写一条占位：`chunk_index = -1`、`score = NULL`、`source = "full_text"`、`text_preview` 记 `mode/length/hash`（`source` 列 `VARCHAR(20)` 够用） |
| `/evidence` 与 eval runner | 把无 chunk 读成 Recall=0 | runner 识别 `source=full_text` → 检索类指标标 `n/a` **而非 0**，答案侧指标照常判；遵守 `docs/eval/README.md:103-107` 的 `pass^k(上下文)` 陷阱 |
| 引用契约 | `buildQuestionMessage(question, listTopic, includedCount)` 会要求 `[N]` | 新增 `ContextMode` 重载：档 A 改为「可指出章节名，不要求片段编号」，避免模型编造引用 |
| `BookAnswerGuard` | `noContext=true` 可能触发拒答 | `retrievalAttempted=false`（`BookChatService.java:290`）已使条件短路，**无需改**，但需回归验证 |
| 列表流水线 | 依赖 `ragMatches` | 档 A 首版**禁用** `streamItemListPipeline`，走单次流式；全文在上下文里，列表题反而更简单，作为 A/B 观察项 |

---

## 3. 分阶段实施

### P0 — 清障（可独立上线，零检索改动）

| 编号 | 改动 | 文件 | 说明 |
|---|---|---|---|
| **P0-1** | 缓存层默认关闭 | `application.yml:291`、`config/properties/AiModelProperties.java:30` | 默认值 `true → false`。依据 `ai-cache-bust-plan.md` §5「排查结束后可直接关闭恢复缓存收益」，零代码改动、环境变量即时回滚 |
| **P0-1b** | 评测强制开启 | `docs/eval/README.md` §1、`scripts/eval-bookqa.mjs:1-15` 注释 | 增加 `export KBOOK_AI_CACHE_BUST_ENABLED=true`。**评测必须开 nonce**，否则相同 prompt 命中缓存会污染 A/B 结论 |
| **P0-2** | 静态/动态书籍信息拆分 | `BookChatService.java:617-665`、`:462-513` | 拆为 `buildStaticBookCorpus()` 与 `buildDynamicBookMeta()`；动态部分移到历史之后 |
| **P0-3** | 修正上下文长度数据 | `config/DataInitializer.java:101`、`AiProviderProperties.java:104-115`、后台保存逻辑 | 种子 `32768` 不再硬编码；从 `ai-providers.yml` 的 `models[].maxTokens` 同步到 `AiProviderConfig.maxTokens`；对**已有 DB 行**加一次性回填迁移（勿只改种子） |
| **P0-4** | 保留原 query 作为独立召回路 | `service/ai/BookChatRagService.java:561`（`expandQuery` / `extractKeywords`） | 当前 >15 字问题的首个查询被替换成 ≤15 字关键词短语，原句未进检索。改为「原句 + N 路扩展」并行，上限仍 `MAX_SUB_QUERIES=6` |
| **P0-5** | 多路分数分路归一化 | `BookChatRagService.java:402-415` | 现全局混排后用单一 `maxScore × 0.3` 裁剪，会系统性偏向最匹配那一路。改为各子查询内部归一化后合并（或 RRF 按名次融合，零模型成本） |
| **P0-6** | 摘要一致性 | `BookService.java`（重扫路径） | `setCompressedSummary(null)` 与 `toc`/`chapterSummary` 重解析同批；`Book.java:97-109` 的 setter 只清了 `tocQualityScore` |
| **P0-7** | 导入时预生成摘要 | `BookScanService.java:310-316` | 把 `ensureCompressedSummary` 的懒生成前移到导入流程，消除首轮前缀漂移 |
| **P0-8** | 对外表述对齐 | `README.md:164,304`、`README_EN.md` | 三道防线代码未落地而文档宣称已实现；先改文案（或按 P2-3 真落地），二选一，不要留不一致 |

**P0 验收**：`mvn compile` + `npm run build` 全绿；`CacheBustingModelsTest` 8 例仍通过；黄金集 A/B 的 `pass^3`、误拒率、P95 **不劣化**；网关/日志侧确认同书连续两问出现缓存命中（见 §5.3）。

### P1 — 三档路由主体（档 A + 档 C）

| 编号 | 改动 | 文件 | 类型 |
|---|---|---|---|
| **P1-1** | 新增 `BookQaProperties` | `config/properties/BookQaProperties.java` | 新增，`prefix=kbook.book-qa` |
| **P1-2** | 新增 `ContextBudget` | `service/ai/rag/ContextBudget.java` | 新增，纯函数 + 单测 |
| **P1-3** | 新增 `BookScaleRouter` | `service/ai/rag/BookScaleRouter.java` | 新增，含会话档位锁定 |
| **P1-4** | 新增 `FullTextContextBuilder` | `service/ai/rag/FullTextContextBuilder.java` | 新增，读 `full_text`、拼静态语料 |
| **P1-5** | 只取全文的投影查询 | `repository/BookRepository.java`、`service/book/BookService.java` | 修改：`getBookById()` 会 EAGER 加载 MEDIUMTEXT，档 C 无需全文；加投影 + 书级本地缓存避免每轮拉全本 |
| **P1-6** | 主链路插入路由 | `BookChatService.java:231`（检索前）、`:256-257`、`:319-327` | 修改：按档决定「跳过检索 / 走现有检索」，静态语料注入、`ContextMode` 契约 |
| **P1-7** | 快照占位 | `service/ai/rag/AiRetrievalSnapshotService.java:67`、`:save()` | 修改：支持 `source="full_text"`、`chunkIndex=-1`、`score=null` |
| **P1-8** | 评测适配 | `scripts/eval-bookqa.mjs`、`eval-bookqa-metrics.mjs`、`eval-snapshot-coverage.mjs` | 修改：识别全文档 → 检索指标 `n/a`、新增档位与缓存指标字段 |
| **P1-9** | 列表流水线闸口 | `BookChatService.java:267-276` | 修改：档 A 不走流水线 |
| **P1-10** | 单测 | `backend/src/test/java/.../rag/` | 新增：`ContextBudgetTest`、`BookScaleRouterTest`、`FullTextContextBuilderTest`（含边界：无 fullText、超长书、历史增长、会话锁定） |

`BookQaProperties` 配置项（`application.yml` 新增 `kbook.book-qa` 段）：

| 键 | 默认 | 说明 |
|---|---|---|
| `scale-routing-enabled` | `false` | **灰度总开关，起步关闭**，验证后再开 |
| `small-book-ratio` | `0.6` | 全书 ≤ 可用空间该比例 → SMALL；需 A/B 标定 |
| `safety-margin-ratio` | `0.15` | 预算安全余量 |
| `full-text-max-chars` | `400000` | 硬上限，防止 MEDIUMTEXT 极端值把请求打爆 |
| `char-per-token` | `1.5` | 与现有 `TOKEN_TO_CHAR_RATIO` 统一，换模型时可调 |
| `session-lock-enabled` | `true` | 会话内锁定档位 |

**P1 验收**：开关关闭时与现状**逐字节等价**（全量 A/B 报告对比）；开关打开后，档 A 的 `pass^3(上下文)` 不低于档 C、P95 与输入成本下降、`must_not_claim` 无新增失败；`ragMaxChars=40000` 的历史教训（ctxRecall 0.917→0.722、P95 76.7s→113.9s）不得重演。

### P2 — 档 B + 合规改造 + 闭环

| 编号 | 改动 | 依据 |
|---|---|---|
| **P2-1** | 章节结构落库：新增 `books.structure_json`（或 `book_chapters` 表）+ V8 迁移，存 `TextStructureExtractor.Chapter(index,title,startChar,endChar)` | `rag-ingestion-and-chunking-plan.md:212-219` 已设计，`TextStructureExtractor.java:22` 已产出该结构，目前只在向量化时现算不落库 |
| **P2-2** | 新增 `ChapterContextBuilder`：目录/章节定位 → `fullText.substring(start,end)` 整章注入 | 优先用 `full_text` 切片，**绕开** Qdrant 的 200 字 overlap、`ContentQualityGate` 丢块、`MAX_FULL_SCAN_CHUNKS=500` 三处失真；`TocAnchorResolver` 可作定位备选 |
| **P2-3** | 目录/摘要接入 Qdrant payload，替换 `text_segment` | 合规三道防线的第 2 道。复用 `books.toc` / `compressedSummary`，采用 `rag-ingestion-and-chunking-plan.md:201` 的 `embed_text` / `display_text` 分离设计；**遵守同文档 `:204`「不要用 LLM 改写正文入库」**。注意原文在 MySQL `books.full_text` 还有一份 |
| **P2-4** | 快照/评测/引用契约适配档 B | 同 P1-7/P1-8，`source="chapter"` |
| **P2-5** | 反馈闭环：`answer_feedback` 表 + 接口 + 「有用/没用」按钮 + 差评入标注队列 | `book-qa-accuracy-grounding-and-eval-plan.md` §4.5 已设计未落地；`BookChatSheet.tsx` 操作栏目前只有复制/重生成/朗读 |
| **P2-6** | 混合召回（BM25 + dense + RRF） | **并入桌面化路径 B 的 Lucene 内嵌改造**，不单独立项，避免 `rag-ingestion-and-chunking-plan.md` §7.2 指出的「chunk 索引 + 双写一致性」两大障碍 |
| **P2-7** | 引用依据前端展示 | `/evidence` 接口与 `citation_json` 已存在，前端 `BookChatSheet.tsx` 零调用 |

> **P2-3 是分档阈值的重置事件**：把 payload 从原文换成章节+主题摘要后，语料规模、相似度分布、所有已标定阈值全部改变。若决定近期做，**应先做 P2-3 再回来标定 P1 的 `small-book-ratio`**，否则阈值要返工两次。这也是 0.1 之外唯一需要产品决策的前置项。

---

## 4. 改动清单汇总

| 文件 | 涉及项 | 风险 |
|---|---|---|
| `resources/application.yml` | P0-1、P1-1 | 低（可环境变量覆盖） |
| `config/properties/AiModelProperties.java` | P0-1 | 低 |
| `config/properties/BookQaProperties.java` | P1-1 | 新增 |
| `config/DataInitializer.java` | P0-3 | 中（只影响新库；老库需回填迁移） |
| `config/ChatModelFactory.java` | 仅核对，不改 | — |
| `entity/Book.java` | P2-1（加字段） | 中（`ddl-auto:update` 自动加列） |
| `entity/AiProviderConfig.java` | 仅核对，不改 | — |
| `service/ai/BookChatService.java` | P0-2、P1-6、P1-9 | **高（主链路共享符号）** |
| `service/ai/BookChatRagService.java` | P0-4、P0-5 | **高（检索共享符号）** |
| `service/ai/rag/*`（新增 4 类） | P1-2/3/4、P2-2 | 新增 |
| `service/ai/rag/AiRetrievalSnapshotService.java` | P1-7、P2-4 | 中（NOT NULL 约束） |
| `service/ai/BookChatSessionService.java` | P1-3 复用 `historyChars` | 低（只读接入） |
| `service/book/BookService.java`、`BookScanService.java` | P0-6、P0-7 | 中 |
| `repository/BookRepository.java` | P1-5 | 中 |
| `service/embedding/EmbeddingStoreManager.java`、`EmbeddingIndexService.java` | P2-3 | **高（需重建向量）** |
| `scripts/eval-bookqa*.mjs`、`docs/eval/README.md` | P0-1b、P1-8 | 低 |
| `frontend/src/components/book/BookChatSheet.tsx` | P2-5、P2-7 | 中 |
| `README.md`、`README_EN.md` | P0-8 | 低 |

### 数据库迁移

- `V8__book_qa_scale.sql`：为已有 `ai_provider_config` 行回填真实 `max_tokens`（P0-3）；新增 `books.structure_json TEXT NULL`（P2-1）；新增 `answer_feedback` 表（P2-5）。
- 全部为**加列/加表**，不改既有列类型、不加约束，可安全回滚。
- `ai_retrieval_snapshots.chunk_index` 已 `NOT NULL`，用 `-1` 占位，**不要改成 nullable**（会影响 `/evidence` 排序口径）。

---

## 5. 验证方案

### 5.1 现有资产（不新增基础设施）

- 数据集：`docs/eval/golden-set.real.jsonl`（14 例有效，`gold_chunks` 人工通读 273 chunk 标注）、`threshold-probe.jsonl`（19 例切题/离题）。
- Runner：`scripts/eval-bookqa.mjs --runs 3`、`eval-snapshot-coverage.mjs`（快照回放不烧模型）、`node --test scripts/eval-bookqa-metrics.test.mjs`。
- 26 份历史 A/B 报告可作基线对照。

### 5.2 必须遵守的 runbook 约束

1. **评测时 `KBOOK_AI_CACHE_BUST_ENABLED=true`**，生产/日常 `false`（P0-1/P0-1b）。
2. 一次评测**独占一个后端**（`docs/eval/README.md:68-72`）；自签 24h token（`:12-17`）；跑完核对日志中每个 `question=` 出现次数 == `--runs`。
3. 报告 `valid:false` 不得当基线（`:63-66`）。
4. **整本书进上下文会让 `Recall@10` 变低但答案可能正确**——必须同时看 `pass^k(上下文)` 与 `ctxRecall`（`:103-107`）。档 A 的排序类指标应标 `n/a`，不能当 0 判失败。
5. 列表型用例 `gold_chunks` 存在标注口径问题（`:121-130`），以 `gold_items` 覆盖率为主判据，改 gold 之前不要为指标动检索。

### 5.3 新增观测项

| 指标 | 采集点 | 用途 |
|---|---|---|
| `scale_mode`（small/medium/large） | `BookScaleRouter` → `ai_usage_log` | A/B 分组、档位漂移监控 |
| `static_prefix_hash` / `prefix_chars` | `FullTextContextBuilder` | 确认前缀逐轮稳定（缓存生效的必要条件） |
| `cached_tokens` / TTFT / 输入 token | provider 网关日志或响应 usage | 验证缓存收益；`ai-cache-bust-plan.md:96` 的运行时核对本机未做，正好补上 |
| 生成侧证据充分性 | 现有 `must_not_claim` + 拒答正确率 | 不新增 LLM judge（其偏差问题见此前研判） |

### 5.4 各阶段门槛

| 阶段 | 通过条件 |
|---|---|
| P0 | 编译全绿；`CacheBustingModelsTest` 8/8；黄金集 `pass^3` / 误拒率 / P95 不劣化 |
| P1 | 开关 off 与现状等价；开关 on 后档 A 的 `pass^3(上下文)` ≥ 档 C，P95 与输入 token 下降，无新增 `must_not_claim` 失败 |
| P2 | 档 B 章节问答命中率提升；payload 脱敏后重新标定全部阈值并留 A/B 报告；反馈通道有真实回流样本 |

---

## 6. 影响面分析（AGENTS.md R1 强制）

**改任何符号前**先跑 `gitnexus_impact({target, direction:"upstream"})`，HIGH / CRITICAL 需先向用户报告；改完提交前跑 `gitnexus_detect_changes()`。需重点分析的高风险共享符号：

| 符号 | 原因 |
|---|---|
| `BookChatService.streamBookChat` / `buildChatMessages` / `buildBookInfoPrompt` / `buildQuestionMessage` | SSE 主链路；`buildQuestionMessage` 还被列表流水线复用 |
| `BookChatRagService.expandQuery` / `doRagRetrieval` | 常规与列表检索共用；改分数归一化会影响 `BookAnswerGuard.isLowScore` 的 `topScore` 口径 |
| `AiRetrievalSnapshotService.save` | 被主链路（`BookChatService.java:392`）与流水线（`BookChatPipelineService.java:643`）双写 |
| `ChatModelFactory.wrap` / `wrapStreaming` / `cacheBust` | 全局 LLM 请求链（本次只改默认值，不改结构） |
| `Book`（加 `structure_json`） | `BookRepository` 投影查询、`BookDocument` ES 映射需同步核对 |
| `EmbeddingIndexService.buildChunkMetadata` / `EmbeddingStoreManager.buildQdrantPayload` | P2-3 会改变向量语义，需全量重建并复核 `EmbeddingConsistencyChecker` |

> 注：`book-qa-accuracy-grounding-and-eval-plan.md` §7.1 记录 GitNexus 当前未连接。若仍未恢复，按 AGENTS.md 的说明先重建索引，或用 `query()` / `context()` 替代 grep；同时满足 R3「改共享函数先列全引用点」。

**R2 数据流追溯**（P1-6 必做）：`Book.fullText` → `BookQaProperties` 阈值 → `BookScaleRouter` → `BookChatService` 消息列表 → SSE 响应 → `ai_retrieval_snapshots` → `/evidence` → eval runner → 报告。任何一环缺失都会让「答对了但评测判失败」。

**R4 自检**：`mvn compile` + `npm run build`；前后端联动两端都编译；新增配置在 `application.yml` / `DataInitializer` / 前端表单三处一致；快照写入刷新后仍在；`useCallback` 递归计数器用 `let`；PC/手机 `md:` 断点（P2-5 按钮）。

---

## 7. 回滚与风险

| 风险 | 缓解 |
|---|---|
| 档 A 长上下文导致 lost-in-the-middle、P95 上升 | `scale-routing-enabled=false` 一键回退；`small-book-ratio` 起步 0.6 保守；A/B 门槛含 P95 |
| 缓存收益不达预期 | provider 差异需分清：OpenAI 兼容（DeepSeek/Qwen）多为**自动前缀缓存**，关 nonce 即生效；**Anthropic 需显式 `cache_control` 断点**，当前 LangChain4j 封装未暴露，列为 P2；Ollama 本地无计费，收益主要是 TTFT。不要笼统承诺「关掉就省钱」 |
| 会话中途掉档破坏前缀 | `session-lock-enabled=true` 锁定档位 |
| `compressedSummary` 懒生成 / 动态字段让前缀漂移 | P0-2 + P0-7 |
| 档 A 无 chunk 导致评测与引用崩坏 | P1-7 / P1-8 / P1-9 三处同批改，见 2.5 |
| `full_text` EAGER 加载拖慢档 C | P1-5 投影查询 + 书级缓存 |
| EPUB ZIP 降级按 entry 顺序拼全文，章节可能乱序（`BookFullTextExtractor.java:344-366`） | 档 A 对这类书降级走 LARGE，或导入时先标记 `parse_quality` |
| P2-3 改变向量语义使旧阈值失效 | 与 P1 阈值标定错开，二选一顺序，见 §3 尾部提示 |
| `ai_retrieval_snapshots` 占位行被旧版 eval 当真实 chunk | runner 先升级再上线档 A |

所有阶段均可通过配置/环境变量即时回滚；迁移只加列，无需 down migration。

---

## 8. 实施顺序

```
P0 清障（缓存默认值 / 静态前缀 / maxTokens 数据 / 原 query / 分路归一化 / 摘要一致性 / 文档表述）
  └→ 用黄金集确认「关缓存 + 拆前缀」不劣化，并取得缓存命中的实测基线
       └→ P1 三档路由（只上 SMALL + LARGE，MEDIUM 留接口）
            └→ 评测适配 + 灰度开关 A/B 标定 small-book-ratio
                 └→ P2-3 合规改造（若近期做，应与 P1 阈值标定对调顺序）
                      └→ P2 档 B / 混合召回（并入桌面化）/ 反馈闭环
```

**建议第一步只做 P0-1、P0-2、P0-3 三项**（不碰检索链路），用现成黄金集跑一次 A/B，确认缓存前缀稳定、`maxTokens` 数值正确，再进入 P1。理由是：这三项是所有阈值判断与收益测算的前提，且改动面最小、回滚最直接。

---

## 9. 待决策项

1. **P2-3（payload 脱敏）何时做**——它决定 P1 阈值是否需要重标。建议先定时间，再排 P1/P2 顺序。
2. **`scale-routing-enabled` 默认是否开启**——本方案建议起步 `false`，灰度验证后再改默认。
3. **会话档位锁定的存储**——桌面形态优先本地缓存（Caffeine），web 形态用 Redis；取决于桌面化排期。
4. **档 A 是否禁用列表流水线**——本方案建议首版禁用并作为 A/B 观察项，需确认是否接受列表题回答形态变化。

---

## 10. 实施落地记录（2026-11-10）

### 10.1 阶段验收

| 阶段 | 状态 | 验证 |
|---|---|---|
| P0 清障八项 | 完成 | `mvn test` 723/723；黄金集 A/B 口径保持不变 |
| P1 三档路由主体 | 完成 | 新增 26 个单测；开关关闭时与改造前逐字节等价 |
| P2-1 章节结构落库 | 完成 | `books.structure_json` + V9；导入时预生成，MEDIUM 档不再每轮重扫全书 |
| P2-2 整章上下文 | 完成 | 并入 `FullTextContextBuilder.buildMediumCorpus/resolveChapter`，含 `books.toc` 回退 |
| P2-3 向量库脱敏 | 完成 | `PayloadTextCodec` + `text_redacted` 标记；**embedding 仍由原文算出，向量语义与阈值不变**，因此 §3 尾部「P2-3 会让阈值失效」的前提在实现层面被规避（仍建议重新标定） |
| P2-4 档 B 契约 | 完成 | `source="chapter"` 占位快照、`chunk_index=-1`；eval runner 与快照回放均已识别 |
| P2-5 反馈闭环 | 完成 | V10 `answer_feedback`、用户/批量/后台接口、前端「有用/没用」+ 原因、后台标注队列页 |
| P2-6 混合召回 | 完成（候选池形态） | `HybridLexicalScorer` 在 dense 候选池内做 BM25 重排 + 名次加权；全库倒排索引仍按原计划并入桌面化 Lucene 改造 |
| P2-7 引用依据展示 | 完成 | `/evidence` 前端接入；全文/整章档明确提示「无片段编号不是检索失败」 |
| 合规第 3 道防线 | 完成 | `AnswerOverlapGuard` 滚动哈希检测 ≥30 字逐字重合，落库前替换，主链路与列表流水线双路径覆盖 |

### 10.2 新增灰度开关（全部默认关闭 / 可环境变量即时回滚）

| 环境变量 | 默认 | 作用 |
|---|---|---|
| `BOOK_QA_SCALE_ROUTING_ENABLED` | `false` | 三档路由总开关 |
| `BOOK_QA_PAYLOAD_DESENSITIZATION` | `false` | 向量库 payload 脱敏；**开启后必须重建内容向量** |
| `BOOK_QA_HYBRID_RETRIEVAL` | `false` | 候选池内 BM25 词法重排 |
| `BOOK_QA_ANSWER_OVERLAP_GUARD` | `false` | 答案侧连续原文重合替换 |
| `KBOOK_AI_CACHE_BUST_ENABLED` | `false`（评测须 `true`） | 提示词前缀 nonce |

### 10.3 上线顺序建议

1. 保持全部开关关闭跑一次黄金集基线（与历史 26 份 A/B 报告对齐）；
2. 单独打开 `SCALE_ROUTING`，标定 `small-book-ratio`，核对 `pass^k(上下文)` 与 P95；
3. 打开 `HYBRID_RETRIEVAL` 做召回 A/B；
4. 最后在**重建内容向量**的前提下打开 `PAYLOAD_DESENSITIZATION`，再打开 `ANSWER_OVERLAP_GUARD`；
5. 每次只动一个开关，差评回流按 `context_mode` 分组比较。

### 10.4 已知边界

- 答案侧重合检测不做空白/标点归一化，改写过空白的照抄可能漏检；这是为保住答案可读性做的取舍。
- 候选池 BM25 只能重排 dense 已召回的块，无法召回 dense 完全漏掉的块；完整能力依赖 Lucene 倒排索引。
- 脱敏后的旧向量（payload 仍是原文）与新向量可以共存，`text_redacted` 标记让查询侧逐条判断，无需一次性全量重建。
- 反馈标注队列只提供「标记」动作，把样本写进 `docs/eval/golden-set.real.jsonl` 仍需人工编辑（接口不自动改评测资产，避免误伤基线）。
- **P2-3 的成立前提与运行形态无关**：还原发生在后端进程内（`PayloadTextCodec.restore()` → `BookRepository.findFullTextById()`），只要 Java 服务能读到 `books.full_text`、且向量里的 `startChar/endChar` 与之对齐即可；当前 Spring Boot + MySQL 同栈部署（Docker Compose / k8s）完全满足，Qdrant 是否在远端也不影响。桌面 APP 是 2026-06-20 开源定位里的**演进目标**（jpackage/Tauri + 依赖嵌入式化），不是当前代码形态。真正会失效的三种情况：① `books.full_text` 被清空或不再落库；② 全文重解析导致字符偏移变化却未重建内容向量（重建前需 `invalidate()` 清缓存）；③ 多租户改造后 `bookId` 不再唯一指向同一份全文。
- **会话档位锁是 JVM 内 `ConcurrentHashMap`**（`BookScaleRouter.sessionLocks`），单实例形态成立；但 `deploy/k8s/04-app.yaml` 配置 `replicas: 2`，同一会话跨实例时锁不共享，可能出现档位漂移、静态前缀变化、缓存收益下降。多实例部署前需把锁迁到 Redis（即 §9 待决策项 3 的 web 分支），或继续保持 `scale-routing-enabled=false`。
