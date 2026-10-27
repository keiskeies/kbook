# 图书问答准确率与事实边界优化计划

> 状态：草案 · 日期：2026-10-26
> 范围：`POST /api/books/{bookId}/chat/stream` 主链路（含列表型流水线）
> 前置文档：[`book-qa-iterative-generation-plan.md`](./book-qa-iterative-generation-plan.md) 解决的是"列表型回答漏条目"；本文解决 **事实错误、召回遗漏、过度发挥**，并让这三件事可度量、可回归。

---

## 0. 结论先行

当前链路不缺工程结构（多路召回、去重、邻域扩展、合并、重排、逐条配额、N 节流水线都已具备），缺的是 **事实边界的执行层**。三个根因，按"修复成本 / 收益"排序：

| 编号 | 根因 | 后果 | 修复成本 |
|---|---|---|---|
| **R1** | **生产实际使用的系统提示词不包含代码里的【回答铁律】**：外部 `ai-config.json` 的 `bookChat.styles[].prompt` 覆盖了 `BookChatPrompts` 常量，且外部提示词里没有"事实以参考内容为准 / 不在片段中就说未涉及 / 不许用训练数据编造书中情节"这段 | 幻觉与过度发挥的第一制度性来源，且代码里看着"有约束"，极难发现 | 小时级 |
| **R2** | **没有任何检索快照与引用落库**：`ai_conversations` 无 `citations` / `retrieved_chunks`，前端不展示来源 | 答错时无法区分"没召回 / 召回没用 / 模型编造"，任何 prompt 调优都是盲改 | 天级 |
| **R3** | **"无材料"没有独立分支**：检索为空 → 消息里直接没有参考内容；流水线缺材料 → 指令要求"基于你对其主题的理解谨慎展开" | 模型被制度性地鼓励在没有依据时继续作答 | 天级 |

已具备、不应推翻的部分：

- 列表流水线用 `for i=1..N` 保证"输出节数 = N"（`BookChatPipelineService.streamItemListPipeline`），逐条独立配额（`ItemListRetriever.PER_ITEM_MAX_CHARS`）——这是正确方向。
- 常规 RAG 的"去重 → 全局排序 → 软阈值 → 邻域扩展 → 合并 → 重排 → 字符预算"是完整管线（`BookChatRagService.doRagRetrieval`）。
- SSE 超时、重试、断开落库、输入注入审查等韧性措施齐全。

一句话：**先把"边界"补上，再把"证据"落下来，最后才谈排序与评测。**

> 二次可行性核验（详见 §7）结论：阶段 1~4 可行；**cross-encoder rerank 暂不可行**（无推理服务）；**ES 混合召回需另立计划**（ES 当前只索引书籍元数据）；**CI 门禁需先补 CI 基础设施**（仓库当前无 workflow）。

### 0.1 实施进度（2026-10-26）

| 项 | 状态 | 说明 |
|---|---|---|
| 3.1 提示词分层 | ✅ | `BookChatPrompts.withConstraints`：外部风格词强制注入事实边界；两份 `ai-config.json` 的"标注章节位置"已移除 |
| 3.2 空检索/低分拒答 | ✅（机制） | 空上下文预写拒答；新增可配置 `qdrant.absolute-min-score`（默认 0 关闭）+ topScore 诊断；列表型不使用余弦阈值。阈值需黄金集标定后开启 |
| maxOutputTokens 兜底 | ✅ | `BOOK_QA=16384` / `BOOK_QA_SEGMENT=2048`，显式配置优先。**运行时上调**：4096 实测在 25 条清单题处腰斩（`finishReason=LENGTH`，只出 12/25 条），网关探针实测接受 65536 |
| 流水线分段事实边界 | ✅ | 全篇条目清单 + markdown 标题 + 缺材料预写占位 |
| prefetch 逐条就绪 | ✅ | `prefetchAllAsync` + 单条 8s 超时；首节不再等全部条目 |
| 合并 overlap 去重 | ✅ | `RagPipelineComponents.stripOverlap`，合并后重复率目标 0 |
| 3.3 检索快照 | ✅ | 新表 `ai_retrieval_snapshots`（V5）+ 服务；主链路与流水线都记录**实际入选**的 chunk、score、hash/预览、prompt_version。**运行时修复两处真实缺陷**：预览截断 203 字符溢出 `VARCHAR(200)`（改为严格 ≤200）；快照写入失败会把事务标记 rollback-only，把「回答已成功生成」变成「AI 响应异常」（去掉事务注解 + 写入失败只记日志） |
| 3.5 引用契约 | ✅ | 主链路片段编号 + `[[N]]` 校验；流水线用 `PipelineFragmentRegistry` 做全局编号，聚合后统一校验并写 `citation_json`（V6） |
| 3.4 章节元数据 | ✅（部分） | `TextStructureExtractor` 标题+偏移；`chapterTitle/chapterIndex` 入 chunk 元数据并用于片段标签；页码未做 |
| 3.6 多轮 | ✅ | 压缩跳过最近一轮 + `getLastAiAnswer` 优先原始回答 + `regenerate` 删除旧回答/复用原用户消息（含快照清理） |
| 3.7 列表排序 | ✅ | `ChunkRanking`：保留 LLM 精筛顺序，按 rank 截断，再按 chunkIndex 输出上下文 |
| 3.8 rerank/查询收敛 | ✅（rerank 默认关闭） | `RagRerankService` + `AiScene.RAG_RERANK`；子查询上限 6、`qaPrefetchExecutor` 并行检索 + 单路 10s 超时、仅首条拼领域上下文 |
| 首轮列表题 outline（流水线） | ✅ | 无上轮回答时用"问题 + 当前检索材料"提取条目；超时/无条目回退单次流式。**运行时修复**：超时预算 3s → 12s（3s 预算下首轮条目化必然超时、流水线从未被触发；放宽到 8s 后实测仍卡在分布边缘 6.2s / >8s / ≤8s，约一半首轮列表题走不到流水线） |
| 列表型 scroll 分页 | ✅ | **运行时修复死循环**：Qdrant scroll 是游标语义，必须把上一页返回的 `next_page_offset` 写回请求（`setOffset`），原实现漏写 → 每轮重复取第一页、循环永不退出（列表型 TOC_RANGE 档请求挂死 >4 分钟）。两处 scroll 合并为共享 `scrollAll(filter)`，新增 5 例分页契约单测（变异验证：去掉 `setOffset` 即 2 例失败） |
| 列表型 LLM 精筛并行 | ✅ | **运行时修复首要延迟来源**：精筛分批原本串行（212 chunks → 15 批 × ~6.5s ≈ 97.5s，占首 token 等待的 95%）。改为分批并行（新增 `listRefineExecutor` 8 线程，与流水线预取池隔离）；结果顺序与串行版一致（按批次序拼接、批内保持 LLM 选择顺序）；新增整体 45s 硬超时，超时按「保守保留该批全部」处理不丢内容；3 例单测（变异验证：改回串行即并发度断言失败） |
| 超长列表（>20 条） | ✅ | 前 20 条逐节展开，其余由 `buildOverflowSection` 确定性清单节兜底；不再整轮退回单次流式 |
| 入库质量闸门 | ✅ | `ContentQualityGate`：空/极短/乱码/纯符号/重复块不嵌入；乱码 >10% 打警告 |
| 检索侧乱码过滤 | ✅ | 常规 RAG 与列表型 RAG 都在进上下文前丢弃乱码块（与入库同阈值）；原先只打日志称"丢弃"但实际未丢 |
| 条目化输入截断 | ✅ | `OutlineTextCompactor`：列表保留全部条目标题，散文退到句边界；不再 `substring(0,6000)` 硬切 |
| 评测层（§4） | ✅（模板） | `docs/eval/golden-set.sample.jsonl` + `scripts/eval-bookqa.mjs` + 纯指标单测；新增 `/evidence` 查询接口；需填真实书后运行 |
| 真实黄金集（book 24689） | ✅ | `docs/eval/golden-set.real.jsonl` 14 例（8 事实 / 3 列表 / 2 不可答 / 1 多轮）；`gold_chunks` 由人工通读该书 273 个 chunk 原文后标注，不用系统检索结果当基准（避免循环自证） |
| 合并跨度（§4.4-5） | ✅ | 合并片段把跨度写进元数据 `chunkStartIndex/chunkEndIndex`，快照落库为 `chunk_index=起始 / chunk_end_index=结束`。**此前只记组内最后一块**（12+13 合并只落 13），召回统计会把被合并的前一块算成没召回。评测侧同步按跨度展开 |
| 评测排序口径（修复） | ✅ | `/evidence` 按 chunkIndex 升序返回，而上下文是按 score 降序拼的；排序指标此前按 chunkIndex 顺序算，等于在问「gold 是不是全书最靠前的十块」。改为按 score 降序（与提示词实际顺序一致），列表型同分保持原序 |
| 评测报告有效性 | ✅ | 全部 run 都失败的用例不再被算成 0 分基线：报告标 `valid:false`、错误按 auth/timeout/network/other 分类、退出码 3 |
| 拒答判定（修复） | ✅ | 纯关键词表漏掉「这本书里没有量子计算」「量子纠缠？不存在的」这类同义否定 → 新增主题邻域否定词判定；`must_not_claim` 对不可答题同样校验（防「先说没找到、再补一段编造」） |
| 本地评测 token | ✅ | `scripts/eval-dev-token.mjs`：用 dev 配置的 `jwt.secret` 签长时效 token 并调 `/api/user/me` 自检。access token 2 小时过期会把 3 轮全量评测打到一半 401，报告作废 |
| 上下文召回口径 | ✅ | 新增 `ctxRecall`（gold 是否在**整份**检索结果里，不看 top-k、不看排序）+ 报告里的 `successContext` 第二判据。单看 Recall@10 会误判"没召回" |
| 快照离线回放 | ✅ | `scripts/eval-snapshot-coverage.mjs`：直接读 `ai_retrieval_snapshots` 重放到黄金集，**不调模型**。快照行 id 升序 = 提示词片段顺序 = `[N]` 编号顺序（`/evidence` 按 chunkIndex 升序返回，排序指标必须自己按 score 降序还原） |
| 上下文预算上限 | ✅（默认 0 关闭） | 新增 `qdrant.rag-max-chars`：常规管线取「模型窗口预算」与该上限的小者；列表型路径不适用。**A/B 结论：40,000 上限不能上线，维持默认 0**（见 §4.6.6） |
| A/B：上下文上限 | ✅（§4.6.6） | 同 runner / 同样本 / 仅 `QDRANT_RAG_MAX_CHARS` 一个变量：**无上限 ctxRecall 0.917 / P95 76.7s**，40,000 上限 **0.722 / 113.9s** —— 质量与延迟同时变差，上限从"质量换延迟"变成"两头亏" |
| 列表路径三处修复 | ✅（§4.6.7~4.6.8 已复跑） | ① 目录窗口**主题覆盖校验**（`TopicCoverageCheck`），覆盖不到就换档 3；② 精筛后**补回各章节锚点块**（`mergeAnchorHeads`）；③ `BOOK_QA` 输出上限 4096 → 16384，治"25 条只出 12 条"。复跑：①③ 生效，② 只是必要条件 —— "六辑只剩两辑"的真实根因是条目化输入截断，见 §4.6.9 |
| 条目化目录提示 | ✅（§4.6.9 已复跑） | `TocStructureHint`：从 `books.toc` 里用纯字符串匹配挑出「与问题相关的目录子树」喂给条目化。治"6000 字只装得下前两辑标题 → 条目化只提 2 条 → 答案结构上只有 2 节"。六辑 coverage 0.5 → **1.0**，单测 6 例 |
| 评测拒答假阳性（修复） | ✅ | 切题用例此前全文搜拒答词：答案写全 25 条后尾部一句"这部分材料不足"把整条判成 `refusal=yes`。现切题用例要求拒答词落在 `gold_items` 40 字邻域内；拒答用例（有 `refusal_topics`）口径不变。回测三份报告仅此一处 `true→false` |
| 列表型 TOC 锚点（关联计划） | ✅ | `TocAnchorResolver`：目录行 → chunkIndex 原用「总 chunks ÷ 目录行数」均分映射，实测 14/14 真实章节**全部漏头** → 25 条心理倾向清单（210..211）完全不在检索窗口内，模型直接拒答。改为「保留缩进解析层级 + 序数标记+标题在正文里定位真实起点」，实测 13/13 命中；单测 11 例 + 变异验证 |
| 真实基线与阈值标定 | ✅（§4.6） | 首轮基线快照回放（14 用例 / 15 次回答）：事实题 ctxRecall **0.958**、引用命中 gold **8/8**（gold 没丢也没被浪费）；列表题 ctxRecall **0.250**、引用命中 **1/4**；多轮题 **0/1** → 真正掉分的是列表/多轮的 TOC 窗口切错，不是事实题。topScore 切题 0.729 / 离题 0.691（差 0.038）→ `absolute-min-score` **维持 0 关闭** |
| 快照回放指标修复 | ✅ | `eval-snapshot-coverage.mjs` 用 `mysql --batch` 按行切分，**多行回答被截断成第一行** → 正文里的引用编号丢失，「引用命中 gold」被系统性低估（首轮误报 12/27 = 44%，修好后 13 例中 9 例）。改为：正文列 SQL 转义换行 + **引用编号优先取 `citation_json`**（`CitationValidator` 校验后的合法编号），正文正则兜底 |
| 入库净化/分块（关联计划） | ✅（部分） | `TextNormalizer`（HTML/PDF/OCR/TXT 编码 + 文本型 PDF 页眉页脚）+ `TextChunker`（结构感知分块、偏移/cutReason）；扫描版 OCR 页眉页脚待做 |
| A/B：主问答思考强度 | ✅（§4.6.11，**不上线**） | `BOOK_QA` 的 `reasoningEffort` high → low 跑同一份 14 例：P95 **175.1s → 106.5s**、首 token 均值 36.7s → 27.6s，证实 P95 离群来自 high 强度思考；但 ctxRecall 1.000 → 0.875、覆盖率 1.000 → 0.750 → **维持 `high` 不改**。附带发现：`BOOK_QA` 这一行同时是 `LIST_QUERY_REFINE`/`LIST_QUERY_CHAPTER_RANGE`/`ANSWER_OUTLINE`/`RAG_RERANK` 未绑定时的实际生效参数，改它会连检索一起变 |
| 首轮条目化主题闸门（修复） | ✅（§4.6.12） | A/B 暴露真缺陷：检索没命中读者指名的章节时，条目化会从材料里挑"它唯一看得见的清单"，流水线于是**答另一个主题的清单**（问"避免清单"答成"私人书单六辑"6 节，引用还全合法）。新增确定性闸门 `outlineMatchesQuestion`，口径同 `TopicCoverageCheck`，不命中即退回单次流式；单测 2 例（含事故复刻 + 黄金表） |
| 条目化 JSON 局部坏掉 | ✅（§4.6.15 已复跑） | `parseRaw` 单次整份解析会在一个坏字符处丢掉整份清单（漏冒号 / 未转义引号 / 末条截断，实测每轮 7 次）。改为按条目边界逐条 salvage，`topic` 一并救回（丢了会让首轮分段指令退成「上一条回答」）。单测 +7（含变异对照：完好输出仍走原路径） |
| 目录即枚举表 | ✅（§4.6.17 已复跑） | 目录匹配到节点且有子行时，条目直接取目录子行（`TocStructureHint.childTitles`），**不调模型**；清单不在目录里仍走模型条目化。**第一稿踩了坑**：条目没带 keywords/锚点 → 六节全空占位（850 字）却拿满分覆盖率，已修（条目带 keywords + 字面匹配去空白）并给评测补上占位节剔除；修后六辑 `有材料=6/6`、答案 8209 字、`pass^k(上下文) 3/3` |
| 长清单条目化预算 + 残缺闸门 | ✅（§4.6.18 已复跑） | 条目化耗时随输出长度增长（1291 tok 平均 23.5s），25 条清单在 12s 下两轮实测超时 4 次。改为"长清单 25s / 短清单 12s"（判据：材料片段 ≥40 或行首编号 ≥12 行）；另加 `declaredItemCount` 闸门——材料写明总数而条目数不足八成即退回单次流式（实测有一轮只抄 8/25 条 → coverage 0.32）。`--runs 3`：ctx 口径 3/3、coverage 1.000/1.000/0.960、ctxRecall 全 1.000；延迟 76~82s→38~96s |
| rerank 失败降级（修复） | ✅（§4.6.19） | `RAG_RERANK` 未绑定时 `buildForScene` 抛异常且未被捕获，异常冒泡让**整条检索变空、所有常规问答变拒答**（计划里写的「失败保持向量顺序」只覆盖了模型调用失败）。改为解析失败即返回空分数走向量顺序；运行时确认 `Recall@k 0→1`（逆向思考）。**rerank 本身的排序收益仍未验证**（该部署两臂实际都是纯向量顺序），开关维持 false |
| 引用编号与证据行数同源（修复） | ✅（§4.6.20） | registry 给无 chunkIndex 的片段编号，而快照跳过 `chunkIndex<0` 的行 → 编号比证据行多 1，最后一个编号永远非法（25 节答案 `[14]` vs 13 行、`citationValidity=0.949`）。改为无 chunkIndex 不编号（文本仍进材料）；流水线路径复验 `citationValidity=1.000` |
| 合并重叠重复率验收（§2.5） | ✅（§4.6.13） | 真实 273 chunks 复算 `stripOverlap`：272/272 相邻 pair 全部剥离，按偏移应剥 60,111 字、实剥 60,799 字，合并后残余重复 **0.0001**（目标 0）。此前我按 chunk 文本算出 24.9%「不达标」是**口径错**——分块 200 字重叠是设计意图，指标主语是检索侧合并之后 |

> 基线：后端 `mvn -o test` 全绿（用例数以输出为准，不写死）+ 脚本 `node --test scripts/eval-bookqa-metrics.test.mjs` 全绿（2026-10-27 复核）。
> 2026-10-27 新增单测：`AnswerOutlineServiceTest` salvage +7（含变异对照）、`TocStructureHintTest` +3、`BookChatPipelineServiceTest` +2（含 `verify(never())` / `verify()` 一对变异对照）。

---

## 1. 现状链路（代码级）

### 1.1 单轮主链路（非列表）

| # | 步骤 | 代码位置 | 关键行为 / 参数 | 失败时 |
|---|---|---|---|---|
| 1 | 入口与审查 | `BookChatController.java:46-82` | body 用 `Map`；`manual` 生效；`regenerate` **被注释掉**（`:56`）；输入截断到 5000 字（`CommonUtils.java:460,519-533`） | 直接 SSE error |
| 2 | 会话与上轮答案 | `BookChatService.java:183`、`BookChatSessionService.java:161-174` | `getLastAiAnswer()` 返回 **compressedContent** | 返回 null |
| 3 | 内容向量就绪 | `BookChatService.java:186,457-493` | 未嵌入则阻塞生成（最长 1h） | error 事件 |
| 4 | 列表型检测 | `BookChatRagService.java:213` → `ListQueryDetector.java:101-127` | 先规则快速预判，再 LLM；**失败/超时一律返回 `notList`**（fail-open，`:123-126`）；设计原则"宁可漏判"（`:144`） | 退化为常规 RAG |
| 5 | 查询扩展 | `BookChatRagService.java:440-484` | 原始问题 + LLM 扩展，**代码没有条数上限**（javadoc 写"最多 9"但未实现，`:469-475`） | 用原问题 |
| 6 | 向量召回 | `BookChatRagService.java:307-330` | 每个子查询 `topK×2` **串行**检索；`searchContent` 内部再拼标签+简介前 100 字（`EmbeddingSearchService.java:302-344`）；minScore 0.2 → 0 降级（`:238-243`） | 空 |
| 7 | 去重与排序 | `BookChatRagService.java:363-386` | 按 `chunkIndex` 去重留最高分 → score 降序 → topN（默认 100）→ **相对阈值 `maxScore×0.3`** | — |
| 8 | 邻域与合并 | `:389-396`；`RagPipelineComponents.java:34-40,117-249` | 前 1 后 2，邻域分 `hit×0.7`、下限 0.15；按 chunkIndex 合并，组分取 **max**；再按 bestScore 降序 | — |
| 9 | 预算截断 | `:401`；`RagPipelineComponents.java:328-339` | `ragMaxChars = maxTokens×1.5×0.6`（默认 32K → ≈29.5K 字）；`break` 语义 | 尾部丢弃 |
| 10 | 上下文拼装 | `BookChatService.java:583-595,611-620` | 仅加一句"【书籍参考内容】…不是全书完整内容"，**chunk 之间无编号、无章节、无页码** | — |
| 11 | 消息组装 | `BookChatService.java:369-421` | System(风格词) → 图书信息(目录+摘要) → 历史 → RAG → 画像 → 问题 | — |
| 12 | 生成 | `BookChatService.java:267-328`；`StreamingSseHandler.java` | `BOOK_QA` 场景；LENGTH 告警；最多重试 2 次 | error / 部分落库 |
| 13 | 落库 | `BookChatService.java:285-309`；`BookChatSessionService.java:221-241` | 只存 content / thinking / finish_reason / outline | — |

### 1.2 列表型路径

```
检测为列表型
  ├─ 档1 FULL_SCAN：全量 scroll(≤500) → LLM 精筛（每批 15 条，输出 chunkIndex 列表）
  ├─ 档2 TOC_RANGE：LLM 从目录推断章节范围 → 按“总 chunks / 章节数”均匀映射到 chunkIndex → 范围内 scroll → 可选精筛
  └─ 档3 CLUSTER_EXPAND：向量 top15 → 按 chunkIndex 分簇（±2 / 宽簇 ±4）→ scroll → LLM 精筛
        ↓
  精筛结果 score 统一置 1.0 → 按 chunkIndex 升序 → 按字符预算截断
        ↓
  outline（item 数 2..8）可得 → 流水线：逐条预取（锚点优先/关键词向量）→ N 节串行生成 → 聚合成一行落库
  outline 不可得 → 单次流式 + 覆盖度契约
```

代码：`ListQueryRetriever.java:87-266,341-374,519-531`；`ItemListRetriever.java:106-147`；`BookChatPipelineService.java:143-217,225-405,410-446`。

### 1.3 多轮与压缩

- 历史里的 assistant 回答以 `AiMessage` 原样回灌（`BookChatService.java:390-397`）。
- 历史总量超过上下文 80% 时，**一次性压缩所有未压缩的 assistant 消息**（`BookChatSessionService.java:330-418`），包括最近一轮。
- `getLastAiAnswer()` 读的是 `compressedContent`，被查询扩展与列表检测使用（`BookChatService.java:183,213,216`）。流水线在条目化时已修过这个坑（优先 `getContent()`，`BookChatPipelineService.java:167-168`），但检测与扩展没修。

### 1.4 参数与成本

| 参数 | 当前值 | 位置 |
|---|---|---|
| chunk-size / overlap | 800 / 200 字符 | `application.yml:256-258` |
| rag-top-k | **100**（代码默认 10） | `application.yml:264`；`BookChatService.java:191-204` |
| 每子查询取回 | `topK×2 = 200` | `BookChatRagService.java:310` |
| 软阈值 | `maxScore × 0.3` | `:374-386` |
| 邻域折扣 / 下限 | 0.7 / 0.15 | `RagPipelineComponents.java:34-36` |
| RAG 字符预算 | `maxTokens×1.5×0.6` | `BookChatRagService.java:147-160` |
| 列表流水线 | ≤8 节，120s/节，预取 15s | `BookChatPipelineService.java:116-125` |
| 每轮 LLM 调用 | 常规：扩展 1 + 检测 0/1 + 主答 1 + 追问 1 = **3~4 次**；列表：再加精筛 ⌈候选/15⌉ 次；流水线：再加 N 节 | — |

### 1.5 提示词实际生效路径（关键）

```
BookChatSessionService.getSystemPromptForStyle(style)          // :291-307
  └─ aiConfigProvider.getChatStylePrompt(style)                // :293
       └─ AiConfigProvider.java:76-94 → 直接返回 styles[].prompt（原样，不拼接任何前缀）
  └─ 仅当外部配置缺失/为空 → 才回退到 BookChatPrompts.BOOK_CHAT_STYLE_*
```

两个配置文件（`backend/src/main/resources/config/ai-config.json`、`deploy/ai-config.json`）的 `bookChat.styles[].prompt` 均非空，且**都不包含** `BookChatPrompts.BOOK_CHAT_CONSTRAINTS_PREFIX`（`BookChatPrompts.java:17-56`）中的：

- 事实层锁死：书中情节/人物/论点/数据以参考内容为准，不许用训练数据编造；
- 参考内容是片段，不涉及就说"未涉及"，不猜测；
- 信息不够就说不知道。

因此 **`BOOK_CHAT_CONSTRAINTS_PREFIX` 在生产是死代码**。同时外部 DEEP 提示词仍要求"标注章节位置"——而 chunk 元数据里没有章节（`EmbeddingIndexService.java:171-175` 只有 `bookId/chunkIndex/totalChunks` 和一个字符串写死的模型名键，无章节、无页码），模型只能编章节号。

---

## 2. 问题清单

严重度：**S1** = 直接导致事实错误/不可用；**S2** = 显著影响召回或体验；**S3** = 成本/诊断/一致性问题。

### 2.1 P0（根因与止血）

| 编号 | 问题 | 证据 | 影响 | 严重度 |
|---|---|---|---|---|
| **Q1** | 生产提示词缺【回答铁律】，代码常量被外部配置完全覆盖 | `BookChatSessionService.java:291-307`；`AiConfigProvider.java:76-94`；`ai-config.json` → `bookChat.styles[].prompt` | 错 / 过度 | S1 |
| **Q2** | 检索为空无独立分支：`buildRagMessage(null)` 返回空串，消息里没有"参考内容"，铁律失去锚点；thinking 还提示"凭印象回答你" | `BookChatService.java:251,406-408`；`:224`；`BookChatRagService.java:422-424` | 错 / 过度 | S1 |
| **Q3** | 流水线缺材料时，指令要求"基于你对其主题的理解谨慎展开" | `BookChatPipelineService.java:439-441` | 过度（制度性） | S1 |
| **Q4** | 要求"标注章节位置"，但 chunk 无章节元数据 | `ai-config.json` DEEP prompt；`EmbeddingIndexService.java:171-175` | 错（编造章节） | S1 |
| **Q5** | 无检索快照、无引用、无用户反馈；`ai_usage_log` 只有 token | `AiConversation.java:22-111`；`BookChatSheet.tsx:662-667` | 不可归因 / 不可评测 | S1 |
| **Q6** | 乱码检测只记 miss 不丢弃，日志却写"丢弃乱码上下文" | `BookChatRagService.java:415-429`（随后仍 `return ragContext`） | 错（乱码进上下文） | S2 |

### 2.2 P1（正确性与一致性）

| 编号 | 问题 | 证据 | 影响 | 严重度 |
|---|---|---|---|---|
| **Q7** | `regenerate` 前端会发（且重复追加用户消息），后端参数被注释，完全忽略 | `BookChatSheet.tsx:247,473`；`BookChatController.java:56` | 错（旧错误答案留在历史里，模型锚定自己） | S2 |
| **Q8** | 压缩会改写最近一轮回答；检测/查询扩展读 `compressedContent` | `BookChatSessionService.java:161-174,330-418`；`BookChatService.java:183,213` | 漏（"展开上一条"丢列表结构） | S2 |
| **Q9** | 列表精筛后 score 统一 1.0、按 chunkIndex 升序，再按字符预算**按文档顺序**截断 | `ListQueryRetriever.java:519-531` | 漏（选中但靠后的条目被整段砍掉） | S2 |
| **Q10** | 合并块分数取 max，metadata 复用首块——合并 12+13 后 chunkIndex 仍是 12，跨度丢失 | `RagPipelineComponents.java:203-259` | 错（引用/锚点指向不完整文本）；排序放大 | S2 |
| **Q11** | 软阈值是相对值。top1 弱时阈值极低，噪声全放行；top1 强时砍掉次优相关块 | `BookChatRagService.java:374-386` | 漏 / 噪声 | S2 |
| **Q12** | 档 2 章节 → chunkIndex 用"总 chunks / 章节数"均匀假设 | `ListQueryRetriever.java:480-486` | 漏（目录不均衡的书范围偏） | S2 |
| **Q13** | 返回给流水线的 `matches` 是截断前的 `merged`，锚点可能指向模型没看过的片段 | `BookChatRagService.java:401,429` | 一致性问题（引用快照时必须只记实际进上下文的） | S2 |

### 2.3 P2（检索质量与成本）

| 编号 | 问题 | 证据 | 影响 | 严重度 |
|---|---|---|---|---|
| **Q14** | 纯向量，无 BM25/混合召回，无 cross-encoder rerank；排序信号只有余弦分 | `BookChatRagService.java:307-401` | 错 / 漏（专名、原句、否定） | S2 |
| **Q15** | 查询扩展无条数上限；每个子查询串行 embed + 检索；每个子查询都被追加标签+简介前 100 字 | `BookChatRagService.java:310,313,469-475`；`EmbeddingSearchService.java:302-344` | 成本 / 延迟 / 噪声 | S3 |
| **Q16** | 列表检测 fail-open（失败即 notList） | `ListQueryDetector.java:99,123-126` | 漏（长列表退化为 topK） | S3 |
| **Q17** | 向量模型名元数据键不一致（写入 `storeManager.getEmbeddingModel()`，部分读取路径用 `embeddingModel`），低分诊断可能静默失效 | `EmbeddingIndexService.java:175,456-463`；`EmbeddingStoreManager.java:564-567` | 诊断盲区 | S3 |
| **Q18** | 前端声明了 `follow_up_questions` SSE 处理器，但后端从不发该事件 | `sse-request.ts:330`；后端全仓无发送点 | 死代码 / 误导 | S3 |

### 2.4 影响矩阵

| 现象 | 直接原因 |
|---|---|
| **答错（书中事实错/编造）** | Q1、Q2、Q4、Q6、Q10、Q14 |
| **漏（该答的没答/没召回到）** | Q8、Q9、Q11、Q12、Q16、Q14 |
| **过度（没依据也答/自由发挥）** | Q1、Q2、Q3、Q6 |
| **不可评测 / 不可归因** | Q5、Q13、Q17、Q18 |

---

## 3. 改造方案

### 3.1 P0-A 提示词分层与版本化（修 Q1、Q4）

**目标**：让"事实边界"成为不可被风格配置绕过的常驻层。

**改动**：

1. **合并为一条 SystemMessage**：`事实边界 + 安全边界 + 风格` 按固定顺序拼接，放在同一条消息里。
   - 不拆成两条：项目同时接 Ollama 与 OpenAI 兼容端点，部分后端的 chat template 只接受"第一条 system"；拆两条存在静默丢约束的风险。
   - 位置：`BookChatService.buildChatMessages:374`、`BookChatPipelineService.buildBaseMessages:481`。
   - 拼接顺序固定为"事实边界 → 风格"，保证风格配置无论怎么改都覆盖不了事实边界。
2. 事实边界来自**代码常量或独立配置文件**，不放进 `ai-config.json` 的 `styles[]`；`styles[]` 只保留语气/结构。
3. `ai-config.json` 的 4 个风格 prompt 删除所有与事实约束冲突的表述（"标注章节位置"等）。
4. 引用要求与章节要求二选一：
   - **短期**：删除"标注章节位置"，改为"不要声明章节号"；
   - **长期**：见 3.4 补章节元数据后再允许引用。
5. 给提示词生成 `prompt_version`（内容 hash），随每次请求写入 `ai_usage_log`（新增列），用于 A/B 与回归归因。
   - 实现：在 `AiUsageContext` 的 MDC 里加 `aiPromptVersion`，扩展 `AiUsageContext.Target` record；`UsageEvent` 只有两个构造点（`UsageRecordingChatModel:76`、`UsageRecordingStreamingChatModel:102`），改动面可控。
   - 外部配置热更新入口已存在：`AdminAiConfigController:161` → `AiConfigProvider.reload()`，不需要重启。

**验收**：

- 单测：`buildChatMessages` 产出的第一条 SystemMessage 必含事实边界关键字；外部配置为空/含冲突词时行为明确。
- 手工：把 `ai-config.json` 的 DEEP prompt 改成任意风格文本，事实边界仍在。
- 对比：同一组 20 个问题，改前/改后的"无依据书中断言"比例（人工抽样）下降。

**风险**：提示词变长会挤占上下文预算；事实边界应 ≤ 1200 字，并在 `buildChatMessages` 的 overhead 计算里用实际长度（当前用的是 `BOOK_CHAT_SYSTEM_PROMPT.length()`，`BookChatService.java:383`，与实际可能不一致）。

---

### 3.2 P0-B 可答性路由与拒答分支（修 Q2、Q3）

**目标**：把"能不能答"从模型自觉变成代码分支。

**问题类型三分类**（规则优先，避免再加一次 LLM）：

| 类型 | 判定 | 策略 |
|---|---|---|
| **书外元问题**（值得读吗/适合谁/讲了什么/作者是谁） | 关键词 + 目录/摘要可用 | 允许用图书信息回答，不要求片段；明确不编造具体情节 |
| **书内事实问题**（情节/人物/观点/第几章/原文怎么说） | 默认 | **必须有参考内容**，否则拒答 |
| **闲聊/读者困惑** | 寒暄、情绪表达、无书内实体 | 允许无参考，但不得引入书中事实 |

**拒答实现（A3：预写 > LLM）**：

- 新增 `AnswerGuard`：
  - `ragContext` 为空，或 `topScore < ABS_MIN_SCORE` → 不发主模型，直接返回预写话术：
    `这本书里我没找到关于「{问题关键词}」的内容。你可以换个说法，或者问我书里的其他部分。`
  - 仍落库（assistant 行 + `finish_reason=NO_CONTEXT`），便于统计拒答率。
- 绝对阈值需要标定：用现有 `[RAG诊断]` 日志里的 topScore 分布 + 黄金集，取"不可答问题"与"可答问题"的分离点（初始建议 0.35，随向量模型固定）。
  - **注意**：现有 `RagHitStatisticsService` 只把 topScore 分到 `0.3 / 0.6` 两个阈值桶（Redis 计数器），不保存逐次分数分布；`[RAG诊断/常规]` 日志也不打印 topScore。所以 0.35 **不能直接拍**，需先由 3.3 的快照或临时日志采集分布。
- 相对阈值 `maxScore×0.3` 保留为二次过滤，但不再作为唯一门槛。
- **必须与现存的 0.2 → 0 降级联动**：`EmbeddingSearchService.searchContentWithFilter` 在 0.2 无结果时会以 0 阈值重试（`:238-243`）。启用绝对阈值后，这条降级会把噪声重新放回来，需要加开关（如 `allowZeroThresholdFallback=false`）或在 Guard 层再判一次。
- **拒答分支仍要完成协议**：发送 `message` → `done` → `complete`，并落库 user/assistant 两行、发布用户信号；否则前端会停在 loading。
- 前端在收到任意非空回答后都会再调 `/chat/follow-up`（`BookChatSheet.tsx:298-337`），拒答也会触发一次额外 LLM 调用。可选加 `no_followup` 标记，非本阶段必须。

> 已落地机制（2026-10-26）：`qdrant.absolute-min-score`（默认 0 = 关闭）+ `[RAG诊断/常规] topScore` 日志；列表型跳过余弦阈值；`BookAnswerGuard.isLowScore` 单测覆盖。等黄金集数据再定具体阈值。

**流水线缺材料（Q3）**：

- 把"基于你对其主题的理解谨慎展开"替换为**预写占位节**（与现有失败占位一致）：
  `这一节的原书材料没检索到，我先不展开，避免说错。你可以单独问我这一条。`
- 不再让模型对"没有材料的条目"自由发挥。若产品坚持要有内容，则必须标注"书外补充"，并在评测中单独统计。

**验收**：

- 新增单测：空上下文 → 不调用主模型；低分 → 拒答；元问题 → 不拒答。
- 黄金集"不可答"子集：拒答正确率 ≥ 90%，误拒率 ≤ 10%。
- 流水线：缺材料节 100% 输出预写占位，不再出现"模型自述书中没有但继续讲"。

---

### 3.3 P0-C 检索快照与引用落库（修 Q5、Q13）

**目标**：任何一条回答都能回答"它基于哪几段原文"。

**表设计**（新表，避免把 `ai_conversations` 撑大）：

```sql
CREATE TABLE ai_retrieval_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,        -- ai_conversations.id
  book_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  chunk_end_index INT,                    -- 合并块的跨度终点
  score DOUBLE,                           -- 向量分
  rerank_score DOUBLE,                    -- 预留
  source VARCHAR(20),                     -- vector / neighbor / anchor / llm_refine / list
  text_hash CHAR(64),
  text_preview VARCHAR(200),
  prompt_version VARCHAR(64),
  created_at DATETIME,
  INDEX idx_conv (conversation_id),
  INDEX idx_book_chunk (book_id, chunk_index)
);
```

**写入点**：

- 常规路径：`BookChatRagService.doRagRetrieval` 返回前，记录**实际进入 `ragContext` 的** chunk（注意 `truncateToChars` 之后，见 Q13），而不是 `merged` 全量；
- 列表路径：`tryListQueryRagRetrieval` 记录精筛后的 chunk；
- 流水线：`ItemListRetriever.prefetchAll` 记录每条的锚点/关键词命中；
- 关联到 `conversation_id`：在 `onComplete` 拿到 `assistantRow.getId()` 后批量写（先暂存到上下文，再落库）。

**落地约束（已核验）**：

- 不要改 `RagPipelineComponents.truncateToChars` 的签名/语义：`RoundTableRagService:178` 也在用。新增 `selectWithinBudget(List, maxChars)` 返回"文本 + 入选 matches"，与既有 `truncateByQuota` 的做法一致。
- `RagRetrievalResult` 是 record，只有 6 个构造点（`BookChatRagService:171,197,223,229,248,266,429`），加一个 `includedMatches` 字段成本可控。
- `ItemListRetriever.prefetchAll` 当前返回 `Map<Integer,String>`，需改为 `Map<Integer, ItemMaterial(text, chunkIndices, source)>`；调用点只有流水线一处。
- 建表用 Flyway `V5__create_ai_retrieval_snapshots.sql`（现有 V1~V4），不要只依赖 `ddl-auto: update`；实体 + Repository 按现有 `BaseEntity` 约定。
- 快照写入是"回答完成后"的异步/同步尾部动作，失败只记日志，不影响 SSE；保留期复用 `AiUsageRetentionJob` 的清理思路。

**同时**：

- `ai_usage_log` 增 `prompt_version`、`rag_top_k`、`detector_result`、`top_score`、`context_chars`（已有 user/book/session 维度）。
- 前端暂不改也能用：快照先服务导出、后台与离线评测；引用 UI 放 P1。

**验收**：

- 任意一条历史回答，能通过 `conversation_id` 拉出完整依据片段（含 score/source）。
- 导出文件可选附带"依据片段"附录。
- 表增长可控：单条回答 ≤ 50 行，按 `created_at` 30~90 天滚动清理。

---

### 3.4 P0-D 章节元数据（修 Q4）

**目标**：让"第 X 章"从模型记忆变成可验证字段。

**方案**（按可用性分级）：

1. 建索引时，若 `Book.toc` 可解析出章节标题序列，用"顺序查找标题首次出现位置"把 chunk 起点映射到章节：
   - `chapterTitle`、`chapterIndex`、`charStart`、`charEnd` 写入 Qdrant payload 与快照；
   - TOC 无法定位（扫描版/OCR/标题与正文不一致）→ 写 `chapterTitle=null`，并在 prompt 中禁止声明章节。
2. `buildRagMessage` 给每个 chunk 加可读标签（不增加模型负担）：
   ```
   【片段 3｜第 4 章「xxx」】
   ...
   ```
   无章节时只写 `【片段 3】`。
3. 引用契约（P1）直接复用这套编号。

**落地约束（已核验）**：

- `Book.toc` 是**纯标题文本**（EPUB 递归标题、PDF/TXT 各自提取，最多 3 层），没有页码/偏移；`splitContent` 也只返回 `List<String>`，不返回起始偏移（`EmbeddingIndexService.java:802-847`）。
- 因此章节映射需要二选一：
  - 改 `splitContent` 返回 `(text, startOffset, endOffset)`（内部方法，调用点只有建索引）；
  - 或保持现状，在分块后用移动游标 `content.indexOf(chunk, cursor)` 反推偏移（chunk 是原文子串，trim 只影响边缘，可行）。
- 标题到正文的匹配是启发式：EPUB 目录标题常与正文标题不完全一致，需"顺序 + 前 8 字模糊匹配"兜底；TXT/PDF 的章节识别准确率单独验证。
- **元数据必须贯通两条读取路径**，否则加了也拿不到：`EmbeddingStoreManager.doFilterSearch:555-567`（重建 Metadata 时只拷 `bookId/chunkIndex/embeddingModel`）和 `EmbeddingSearchService.searchContentByChunkIndex:211-217`（只拷 `chapterIdx`）。两处都要补 `chapterTitle/chapterIndex/startChar/endChar`。
- 已索引书不需要重新 embedding：用 Qdrant `scroll + setPayload` 补字段；若客户端版本不支持批量 setPayload，再退化为重建该书向量。
- **上线闸门**：先在 20 本书上离线核对；映射准确率 < 90% 的书一律 `chapterTitle=null`，宁可不引用章节，不可引用错章节。

- 抽 20 本书，章节映射准确率（人工核对 3 个随机 chunk/本）≥ 90%；不可映射的书 100% 标记为 null。
- 回答中出现的章节号，必须能在快照里找到对应 `chapterTitle`，否则视为编造（进入评测指标）。

**成本**：一次建索引时计算，无运行时开销；已索引书可后台增量补齐。

---

### 3.5 P1-A 引用契约与校验（依赖 3.3 / 3.4）

**协议**：

- 上下文给每个片段编号：`【片段 1】…【片段 2】…`
- 系统提示要求：每个**书中事实**断言后标 `[[1]]` / `[[2,3]]`；个人解读不加标；书外补充必须以"书外："开头。
- 后端 `CitationValidator`：
  - 解析 `[[n]]`，剔除不存在的 n；
  - 若"事实句"（含人名/情节/数据的句子）无任何合法引用 → 标记 `ungrounded`（不阻断，先统计）；
  - 可选：一次性重写（"请仅保留有依据的断言"），失败则降级原答案。
- 落库：`citations` JSON（片段编号 + chunkIndex + 章节）+ `ungrounded_claim_count`。
- 前端：消息下方"依据（N）"折叠区，点击展示快照原文；**这一步可延后**，先用导出/后台验证。

**指标**：

- `citation_validity = 合法引用数 / 总引用数`（目标 ≥ 99%）；
- `grounded_fact_rate = 有引用的事实句 / 事实句总数`（先建立基线，再定目标）；
- `chapter_hallucination_rate`（章节号无法在快照中验证的比例，目标 → 0）。

**风险**：模型可能不遵守标记格式 → 需要解析容错（中文括号、全角）；连续两轮不合规则降级为"无引用模式"，但记录。

---

### 3.6 P1-B 多轮一致性（修 Q7、Q8）

1. **regenerate 真正实现**：
   - Controller 恢复 `regenerate` 解析；
   - 前端不重复追加用户消息（或后端按 `sessionId + question` 去重）；
   - 后端在构建历史时**排除**被重新生成的那一对 Q/A（`regenerate=true` 时取最后一条 assistant 之前的记录）；
   - 旧 assistant 行标记 `superseded=true` 或删除，避免"自己锚定自己"。
2. **压缩保护**：
   - `getLastAiAnswer` 返回原始 `content`（或同时返回压缩标记）；
   - 列表检测 / 查询扩展读原始回答；
   - 压缩时跳过"最近 1 轮 assistant"（下一轮可能要展开它）。
3. **历史回灌策略**：
   - 历史 assistant 回答进入 prompt 时，加前缀 `【上一轮回答（可能有不准确之处，不得当作书中事实依据）】`；
   - 或只在"指代消解"需要时注入最近 1 轮，其余用摘要。

**验收**：

- 单测：`regenerate=true` 时历史不含旧答案；重复问题不产生两条 user 行。
- 多轮黄金集：第 2 轮"展开第 3 条"的条目覆盖率与首轮一致（不因压缩下降）。

---

### 3.7 P1-C 列表路径排序与截断（修 Q9、Q10、Q11、Q12）

1. **精筛结果排序传递**：
   - `refineByLlm` 保留 LLM 输出顺序（当前解析后仅做 `contains` 过滤）；
   - `toMatches` 按该顺序赋 `rankScore = 1/(rank+1)`，截断按 rankScore，展示再按 chunkIndex；
   - 或直接复用 `truncateByQuota` 的逐条配额思路（流水线已证明有效）。
2. **合并块语义**：
   - 分数：`max` 保留，但记录 `startChunk/endChunk`；
   - 可选：组分为 `max + 0.7 × second + …`，避免"一命中带 2000 字"挤掉其他相关组；
   - metadata 必须写跨度，否则引用会指向不完整文本。
3. **绝对阈值**：
   - 在 3.2 的标定基础上，加入 `ABS_MIN_SCORE`；
   - 相对阈值只用于"同一轮内部砍噪声"。
4. **章节范围映射（档 2）**：
   - 优先用 3.4 的 `chapterTitle ↔ chunkIndex` 真实映射；
   - 无映射时，保留均匀假设但在日志标记 `rangeMapping=uniform`，并在评测中单独观察这类书。

**验收**：

- 黄金集列表子集：条目覆盖率（回答中出现的条目数 / 应有条目数）≥ 95%。
- 构造"列表后半段才出现关键条目"的书：修复后不丢尾项。

> 已落地（2026-10-26）：`ChunkRanking` 保留精筛顺序、按 rank 截断、按文档顺序输出；测试覆盖"精筛选中但靠后的条目不被砍掉"。

---

### 3.8 P2-A 检索质量：LLM rerank 与查询收敛（修 Q14、Q15、Q16）

**两阶段检索（推荐先做）**：

```
召回（宽）：topK 100 保持不变，甚至提高
  ↓
去重 / 邻域 / 合并（保留现状）
  ↓
rerank（top 30~40）：近期只做 LLM 打分（0~3），批量 10 条/次，场景 RAG_RERANK
  ↓
绝对分阈值 + 字符预算
```

- 新增 `AiScene.RAG_RERANK`，复用 `ChatModelFactory` 的场景路由；`AiScene.Category.TOOL` 已有"未绑定回退 roles=TOOL"的链路，加枚举即可。
- rerank 分数落快照（3.3 已预留 `rerank_score`）。
- 不在 rerank 阶段改变 chunk 文本，只改顺序与保留集合。
- **cross-encoder 暂不可行**：项目没有 ONNX/DJL/本地推理依赖，全仓也没有任何 rerank 客户端或模型配置；引入 bge-reranker 需要新增一个推理服务，不放进本计划。
- **LLM rerank 的成本要实测**：30~40 个 chunk × 300 字预览，按 10 条/批 = 3~4 次 TOOL 调用；能否守住 P95 +1.5s 取决于 TOOL 模型的实际吞吐，先做 20 题压测再定是否默认开启。

> 已落地（2026-10-26）：`RagRerankService` 按 0~3 分重排 topN 候选，向量分保留、`rerankScore` 写入快照；`qdrant.rerank-enabled` 默认 false，需先压测延迟/成本再开启。

**混合召回（不在本计划内）**：

- 项目虽引入 `spring-boot-starter-data-elasticsearch`，但**当前只索引书籍元数据**：`BookSearchRepository extends ElasticsearchRepository<BookDocument, Long>`，没有 chunk 索引。
- 做混合召回需要新增"chunk 写入 ES + 与 Qdrant 双写一致性 + BM25/向量 RRF 融合"三条链路，属于独立项目，建议单独立项，不与本计划的准确率止血混在一起。

**查询扩展（低成本改进）**：

- 子查询数上限 5~6（代码目前无上限）；
- 子查询用 `embedAll` 批量、并行检索（当前是 `for` 串行，`BookChatRagService.java:313`）；
- 去掉"每个子查询都拼标签 + 简介前 100 字"（只拼一次，或只在原始查询上拼）；
- 记录每个子查询的贡献（命中数/最终入选数），用于后续裁剪。

> 已落地（2026-10-26）：子查询上限 6；并行检索 + 单路 10s 超时；仅第一条拼书籍标签/简介，其余子查询不再重复拼。子查询贡献统计暂未做。

**列表检测（低成本改进）**：

- 复用 `BookChatPipelineService.hasEnumerativeStructure`（`:654`，当前 `private static`，改为包内可见）作为第二信号：上轮回答含枚举结构 + 本轮是追问 → 即便检测失败也走列表分支；
- **前提**：必须用上轮回答的原始 `content`，不能用压缩版（见 Q8）；
- 检测失败日志区分 `timeout / model_null / parse_fail`，便于判断 fail-open 的真实频率。

**验收**：

- 黄金集：Recall@100 不降；nDCG@10 / MRR 提升；
- P95 延迟增量先按 ≤ 1.5s 做目标（LLM rerank），以 20 题压测实测为准；超标则只对列表/长问题开启；
- 每轮 token 成本增幅 ≤ 20%。

---

## 4. 评测体系

### 4.1 黄金集（先做 3 本书 × 60 题）

| 子集 | 数量/书 | 标注内容 |
|---|---|---|
| 可答事实题 | 25 | `gold_chunks[]`、`gold_points[]`、`must_not_claim[]` |
| 不可答题 | 10 | 正确行为 = 拒答/说明未涉及 |
| 列表题 | 10 | `gold_items[]`、`must_cover_all=true` |
| 多轮题 | 10 | 首轮问题 + 追问（"展开第 3 条"/"刚才说的那个"） |
| 元问题 | 5 | 关于书本身，允许用目录/摘要回答 |

格式（JSONL）：

```json
{"id":"b1-q07","book_id":1,"type":"factual","question":"...","gold_chunks":[12,13],
 "gold_points":["..."],"must_not_claim":["..."],"rubric":"答案须包含 A 与 B，不得声称 C"}
```

来源优先级：真实日志中的高频问题 → 人工补充边界 case → 合成题只用于扩容，不单独作为验收依据。

> 已落地（2026-10-26）：模板 `docs/eval/golden-set.sample.jsonl`、runner `scripts/eval-bookqa.mjs`、指标单测 `scripts/eval-bookqa-metrics.test.mjs`，以及 `GET /api/books/{bookId}/chat/{conversationId}/evidence` 依据查询接口。模板用例 `book_id=0` 会被 runner 跳过，需填真实书。

### 4.2 指标

| 层 | 指标 | 计算方式 | 用途 |
|---|---|---|---|
| 检索 | Recall@k、nDCG@10、MRR | 与 `gold_chunks` 比对 | 排序/召回改动 |
| 生成 | 事实正确率 | LLM judge + 人工抽检 | 主指标 |
| 生成 | `grounded_fact_rate`、`citation_validity` | 规则 + 快照 | 幻觉 |
| 边界 | 拒答正确率 / 误拒率 | 不可答子集 / 可答子集 | 过度回答 |
| 覆盖 | 条目覆盖率 | 列表子集 | 漏 |
| 一致性 | pass^3（同题 3 次全对） | 重复运行 | 稳定性 |
| 效率 | P50/P95 延迟、每轮 LLM 调用数、token、成本 | usage log | 可行性 |

**Judge 校准**：固定 judge 模型与 rubric；先人工标 50 条对齐，再启用；judge 结论与人工一致率 < 80% 时不得作为门禁。

### 4.3 离线回放（不依赖 LLM 的部分先跑）

- **检索回放**：给定 `bookId + question`，直接调用 `EmbeddingService.searchContent` + 现有管线，比对 `gold_chunks`，输出 Recall/nDCG。纯本地，但**依赖 Qdrant/ES 实例**，当前仓库的 544 个单测都是无外部依赖的纯单测（无 `@SpringBootTest`），所以它不能直接作为现有 `mvn test` 门禁。
  - 形态选择：先做独立 runner（Node 脚本调 HTTP，或 Java `main` + profile），接入 CI 后再补基础设施；不要硬塞进单测。
- **生成回放**：走完整 SSE 接口，记录快照 + 答案，再离线评分。需要模型服务，放 nightly。

> runner 已落地：`scripts/eval-bookqa.mjs` 会走完整 SSE → history → `/evidence`，产出 Recall/MRR/nDCG/覆盖率/拒答/引用/延迟/pass^k 报告；运行方式见 `docs/eval/README.md`。
- **阈值标定的数据来源**：`RagHitStatisticsService` 只保存 `0.3 / 0.6` 两个桶的计数（Redis），不保存逐条 topScore；`[RAG诊断]` 日志也不打印 topScore。所以绝对阈值必须由 3.3 的快照（或临时采集日志）先攒 1~2 周数据再定，**不能直接写死 0.35 上线**。

### 4.4 确定性门禁（本地先跑，CI 后挂）

> 现状：仓库**没有 `.github/workflows` 或等价的 CI 配置**；`mvn test` 目前 544 用例全绿（0 failure / 0 error / 0 skip）。因此"PR 门禁"分两步：先让用例能在本地一条命令跑，CI 基础设施补齐后再挂上去。

**评测执行策略（2026-10-27 提速，避免每改一行跑全量）**

| 层级 | 样本 | 轮数 | 用途 | 成本 |
|---|---|---|---|---|
| L0 单测 | `mvn -o test` + `node --test scripts/eval-bookqa-metrics.test.mjs` | — | 每次改代码都跑 | ~1.5 分钟 |
| L1 定向 | 只含受影响的用例（如列表三条 → `/c/tmp/eval/list3.jsonl`） | 1 | 改完立刻验，看日志（`有材料=x/y`、`首轮触发`、`citationValidity`） | 2~4 分钟 |
| L2 目标 | 受影响 + 同路径对照（列表 + 多轮） | 2 | 判断是否稳定，算失败率 | 5~8 分钟 |
| L3 全量 | `golden-set.real.jsonl` 14 例 | 3 | 里程碑/上线前 | 20~25 分钟 |

规则：**一次代码改动只对应一次 L1**；只有跨路径的改动（评测器、检索、编号）才升到 L2；
L3 一整天最多一次。改完先看答案正文与结构日志，再看指标——指标（尤其覆盖率）会被标题凑数。
评测跑必须独占 18181 上的唯一后端（§4.6.10 待办 5）。

不调外部 LLM 的测试（可直接进 `mvn test`）：

1. 提示词组装：事实边界存在、风格不覆盖边界、章节禁用生效；
2. 空检索分支：不调用主模型、返回预写拒答、落库 `NO_CONTEXT`；
3. 引用解析与校验：非法编号剔除、全角括号兼容、无引用事实句计数；
4. 列表截断顺序：构造"后半段才有关键条目"的输入，断言不丢；
5. 合并跨度：12+13 合并后 `startChunk=12,endChunk=13`；
6. detector：超时/异常 → 明确降级且日志可区分；
7. regenerate：历史排除旧 Q/A、不重复 user 行。

LLM 相关评测放 nightly，结果写入报告，不做 PR 阻断（避免模型波动导致误伤）。

### 4.5 生产回流

- 前端每条回答加"有用 / 没用"，写 `answer_feedback(conversation_id, rating, reason)`；
- 差评样本自动进入"待标注队列"，标注后进黄金集；
- 看板：拒答率、引用合法率、P95、每轮成本、按书的空检索率（`RagHitStatisticsService` 已有 hit/miss + topScore）。

### 4.6 真实基线与阈值标定（2026-10-26）

用 `docs/eval/golden-set.real.jsonl`（book 24689《穷查理宝典》，`kbook_content` 273 chunks）在真实后端上跑出来的第一份基线。

#### 4.6.1 口径修正：召回有两个不同的"召回"

单看 Recall@10 会得出错误结论。检索到的材料有两种"没召回"：

| 口径 | 含义 | 实测（首轮基线：14 用例 / 15 次回答） |
|---|---|---|
| **上下文召回**（ctxRecall） | gold 是否在**整份检索结果**里（不看 top-k、不看排序） | 事实题 **0.958**；列表题 **0.250**；多轮题 **0.000** |
| **排序召回**（rankRecall@10） | gold 是否排进**上下文前 10 名** | 事实题均值 **0.583** |

结论分两层：

1. **事实题：gold 既没丢、也被用上了。** 8 次事实题回答的引用**全部**落在携带 gold 的片段上（引用命中 gold **8/8**），
   ctxRecall 0.958。gold 从来没丢过——它只是排在第 10 名之后（第 22 名的达尔文铁律、第 12~13 名的《如果》、第 10~11 名的逆向思考）。
2. **真正掉分的是列表题与多轮题**：列表题引用命中 gold 仅 **1/4**、ctxRecall 0.250；多轮题（展开第 14 条）**0/1**。
   这两类的失败不是"检索没召回"，而是 **TOC_RANGE 的章节→chunkIndex 均匀映射把窗口切到了错误位置**（见 §10.6 与关联计划），已用目录锚点法修复。

> **口径陷阱（必须一起记住）**：`pass^k` 用的是排序召回口径（gold 排进前 10 才算命中），
> 当上下文里塞了整本书时，**答对了也会被判 fail**——达尔文铁律的 gold 在第 22 段、答案正确引用了 `[22]`，
> 但 Recall@10 = 0 → 判 fail。**pass^k 必须与 pass^k(上下文) 一起看**，否则指标会把正确答案算成失败。

> **评测脚本自身的一个坑（已修）**：`eval-snapshot-coverage.mjs` 用 `mysql --batch` 的「按行切分」解析结果，
> 多行回答正文被截断成**第一行**——回答里的引用编号几乎全在第一行之后，于是「引用命中 gold」被系统性低估
> （首轮曾误报为 12/27 = 44%，修好后是 13 例里 9 例）。现在改为：正文列在 SQL 里转义换行、
> **引用编号优先取 `ai_conversations.citation_json`**（`CitationValidator` 校验后的合法编号，含 `included` 片段总数），
> 正文正则只作兜底。`citation_json.included` 与快照行数一致（实测 26/26），印证了「`[N]` 编号 = 快照第 N 行 = 提示词第 N 段」这条契约。

#### 4.6.2 根因：上下文预算形同虚设

`BookChatRagService.getRagMaxChars()` = `maxTokens × 1.5 × 0.6`。BOOK_QA 场景配置的 `maxTokens=524288` → 预算 **471,859 字符**，
而整本书合并后只有 **126,300 字符（84 段）** —— 预算比全书还大 3.7 倍，于是**每一轮都把整本书塞进提示词**（约 84K 输入 tokens/轮）。

后果不是"慢"，而是：gold 排在第 22 名，模型却要在前 20 段无关材料里找到它，然后还倾向于引用排在最前面的那几段。

#### 4.6.3 改法：常规管线的上下文注入上限（可配置，默认关闭）

新增 `qdrant.rag-max-chars`（`QdrantProperties#ragMaxChars`，默认 `0` = 不额外限制）。
`BookChatRagService.getRegularRagMaxChars()` 取「模型窗口推导预算」与「配置上限」的**小者**；列表型路径**不适用**（它的材料是逐条定向检索出来的，不是全量召回）。

曾打算取 **40,000 字符**（实测所有观察到的 gold 位置最远第 22 段，落在预算内）。**A/B 结论相反：40,000 上限不能上线**，
维持默认 `0`（不额外限制）。依据见 §4.6.6。

#### 4.6.4 绝对阈值标定：`absolute-min-score` 维持 0（关闭）

拿 `docs/eval/threshold-probe.jsonl`（4 条切题 + 4 条离题）量 topScore：

| | topScore |
|---|---|
| 切题 | 0.729 |
| 离题 | 0.691 |

**差距只有 0.038**，没有可用的切分点。原因是整本书都会被召回（预算过大 → 任何问题都能捞到相似度 ~0.69 的材料），
余弦分在这个场景下度量的是"和这本书整体的相似度"，不是"和答案的相似度"。

→ **不能用绝对分区分"这本书有没有讲这个"**。拒答只能靠提示词边界 + 模型自述（实测不可答题拒答率 100%），
`qdrant.absolute-min-score` 保持 0（关闭）。等 RAG 侧改成"只召回 top-K 而非全量"后，topScore 的分布才可能重新变得有区分度——**这是先做上下文上限的直接收益之一**。

#### 4.6.5 A/B 结果

见 §4.6.6（用同一 runner、同样本数、仅"上下文上限"这一个变量）。

### 4.6.6 A/B 记录表

**实验设计**：同一份代码、同一 runner、同一黄金集（`docs/eval/golden-set.real.jsonl`，14 用例 / book 24689），
唯一变量是环境变量 `QDRANT_RAG_MAX_CHARS`（40000 = 有上限 / 0 = 不额外限制，即出厂默认）。
报告：`docs/eval/eval-report-24689-cap.json`（有上限）、`docs/eval/eval-report-24689-nocap.json`（无上限）。

| 指标 | 无上限（默认 0） | 40,000 字符上限 |
|---|---|---|
| 上下文召回 ctxRecall 均值 | **0.917** | 0.722 |
| 排序召回@10 均值 | **0.639** | 0.556 |
| pass^k(上下文口径) 通过 | **11/14（0.786）** | 9/14（0.643） |
| pass^k(排序口径) 通过 | **8/14（0.571）** | 7/14（0.500） |
| 条目覆盖率均值（3 个列表题） | **0.667** | 0.495 |
| 引用有效率 | 1.000 | 1.000 |
| 拒答正确率 | 1.000 | 1.000 |
| P50 / P95 延迟 | **27.7s / 76.7s** | 35.5s / 113.9s |
| 证据段数（以「逆向思考」为例） | 93 段 | 25 段 |

**结论：40,000 字符上限不能上线，维持默认 `0`（不额外限制）。**
它不是"用质量换延迟"的取舍——**质量与延迟同时变差**，每一项指标都劣于不限。

**逐用例差异（无上限 → 有上限）**

| 用例 | 无上限 | 有上限 | 说明 |
|---|---|---|---|
| list-心理倾向25条 | 覆盖 **1.000** | 覆盖 0.480 | 上限砍掉材料 → 首轮条目化 12s 超时 → 退回单次流式 → 4096 输出上限处被腰斩（`finishReason=LENGTH`），25 条只出 12 条 |
| list-私人书单六辑 | ctxRecall 1.000 / 覆盖 0.667 | ctxRecall 0.000 / 覆盖 0.500 | gold 所在的第 0~1 段（书单简介）被挤出上下文 |
| factual-达尔文铁律 / 如果诗 / 多元思维模型 | ctxRecall 1.000 | ctxRecall 0.000 | gold 排在第 10~22 段，25 段上限只保留前 25 段里的部分 |
| multiturn-展开第14条 | ctxRecall 1.000 | ctxRecall 0.000 | gold（chunk 249）落在被砍掉的后段 |
| list-幸福与成功 | ctxRecall 0.000 | ctxRecall 0.000 | **两臂都失败**：TOC 目录没有这一行，档 2 窗口切到错误章节（已用目录锚点法 + 主题覆盖校验修复，见 §4.6.7） |

**为什么"砍材料"反而更慢**：材料变少 → 首轮"条目化"（LLM 抽条目）拿到的上下文变了、12s 超时 →
退回单次流式 → 答案写太长在 4096 输出 token 处被截断 → 尾部条目要硬写。P95 从 76.7s 涨到 113.9s 正是这条链。

> **口径与样本量声明**：本表是**单次运行**（每臂 n=1，14 用例 1 本书），且两臂在同一网关、间隔约 10 分钟，
> 存在运行间抖动。结论方向由多个用例同向变化 + 有日志因果链（`finishReason=LENGTH`、条目化超时）共同支撑，
> 但**上线前应在 ≥3 轮下复跑**（`--runs 3`）确认差值稳定，不要把单个差值当精确值引用。
> 结论的适用范围是"整本书 273 chunks、模型上下文 524K"这一体量；超大书（chunk 数 ≫ 273）需要重新标定上限。

---

## 5. 实施顺序与工期

| 阶段 | 内容 | 工期 | 验收 |
|---|---|---|---|
| **阶段 1（止血）** | 3.1 提示词分层 + 3.2 可答性/拒答 + Q6 乱码真丢弃 | 2~3 天 | 不可答题拒答率、无依据断言抽样下降 |
| **阶段 2（可观测）** | 3.3 快照表 + usage 字段 + 章节元数据（3.4） | 3~4 天 | 任意回答可回溯依据；章节可验证 |
| **阶段 3（引用）** | 3.5 引用契约 + 校验 + 导出附依据；前端折叠区可选 | 3~5 天 | 引用合法率 ≥ 99% |
| **阶段 4（多轮/列表）** | 3.6 + 3.7 | 3 天 | 多轮覆盖率不降；列表不丢尾 |
| **阶段 5（检索质量）** | 3.8 LLM rerank 试验 + 查询扩展收敛（混合召回另立计划） | 3~4 天 | nDCG@10 提升、延迟达标 |
| **阶段 6（评测工程）** | 4.1~4.5（CI 基础设施另计 1~2 天） | 与 1~4 并行 | 黄金集 + 确定性用例；CI 后挂 |

**依赖关系**：阶段 2 是阶段 3~6 的前提；阶段 5 的收益必须用阶段 6 的指标验证，否则不要改排序。

### 4.6.7 基线暴露的三个列表路径缺陷（2026-10-26 已修，待复跑验证）

基线（无上限臂）里列表题仍掉分，逐条定位到三个**互不相同**的根因，分别已修：

| # | 现象 | 根因 | 修法 | 涉及文件 |
|---|---|---|---|---|
| 1 | 「如何获得幸福和成功」ctxRecall **0.000** | 该标题**不在目录里**，LLM 只能挑一行"最像的" → 目录窗口切到第三讲（chunk 205~269） | 档 2 加**主题覆盖校验**：窗口文本里找不到主题短语（引号内短语 / 主题字面）→ 判定窗口无效，**交回上层换档 3**（向量召回） | `TopicCoverageCheck`（新）、`ListQueryRetriever#tocRangeRetrieve`、`BookChatRagService#tryListQueryRagRetrieval` |
| 2 | 「私人书单六辑」只覆盖 **4/6** 辑 | LLM 精筛只按主题挑块，把窗口里第一个子章节留下，其余五辑的章节开头块被丢弃 | 精筛后**补回窗口内各章节的锚点块**（`mergeAnchorHeads`），并给最高排名（预算截断时优先保留） | `ListQueryRetriever#mergeAnchorHeads`、`#resolveAnchoredRange`。**注意**：复跑（§4.6.8）显示六个锚点块都进了材料、条目化仍只提 2 条 —— 这条是必要条件但不是该用例的根因，真实根因见 §4.6.9 |
| 3 | 「25 条心理倾向」只出 **12 条**，答案尾部断在 `[[3` | 单次流式路径在 **4096 输出 token** 处被腰斩（`finishReason=LENGTH`，实测 `outputTokens=4096, contentChars=4406`） | `BOOK_QA` 输出上限兜底 **4096 → 16384**（写完 25 条需约 6.5k 可见 token + 1.3k 思考 token；网关实测接受 65536） | `ChatModelFactory#effectiveMaxOutputTokens` |

附带修：首轮"条目化"提示词补规则 1b（材料里出现明确枚举时**必须按枚举逐项列出**）——
实测「六辑」曾被抽成 2 项，导致流水线只展开 2 节。

#### 4.6.8 修复后复跑（2026-10-26 18:34，报告 `docs/eval/eval-report-24689-postfix.json`）

与 §4.6.6 的"无上限"臂同口径（同 runner、同 14 用例、`QDRANT_RAG_MAX_CHARS=0`），唯一变量是 §4.6.7 的三处修复。

| 指标 | 修复前（§4.6.6 无上限臂） | 修复后 |
|---|---|---|
| pass^k(上下文口径) 通过 | 11/14（0.786） | **13/14（0.929）** |
| pass^k(排序口径) 通过 | 8/14（0.571） | **9/14（0.643）** |
| 条目覆盖率均值（3 个列表题） | 0.667 | **0.875** |
| ctxRecall 均值 | **0.917** | 0.875 |
| 排序召回@10 均值 | **0.639** | 0.514 |
| nDCG@10 均值 | **0.366** | 0.322 |
| 引用有效率 / 拒答正确率 | 1.000 / 1.000 | 1.000 / 1.000 |
| P50 / P95 延迟 | 27.7s / 76.7s | 30.9s / 83.3s |

逐用例（上下文口径 pass / ctxRecall / coverage，修复前 → 修复后）：

| 用例 | pass(上下文) | ctxRecall | coverage | 结论 |
|---|---|---|---|---|
| list-心理倾向25条 | ✅→✅ | 1.000→**0.500** | 1.000→1.000 | 排序口径从 ❌ 翻成 ✅（25 条写全了）；ctxRecall 降是**标注口径**问题：gold 只有 210/211（清单概览块），本次精筛留下其中 1 块，逐条详解在 224~256（见 `docs/eval/README.md` §4） |
| list-幸福与成功避免清单 | ❌→**✅** | 0.000→**1.000** | 0.000→**1.000** | **修复 1 生效**：日志 `[档2-TocRange] 目录窗口未覆盖主题「芒格建议避免的事项」，交回上层换检索策略` → 档 3 跑通（召回 15→候选 46→精筛 22→输出 22），4 项避坑清单全中 |
| list-私人书单六辑 | ❌→❌ | 1.000→0.000 | 0.667→**0.500** | **未修好，反而退化**：根因不是精筛丢块（修复 2 已把六个锚点块都送进材料），而是条目化只看材料前 6000 字 → 见 §4.6.9 |
| unanswerable-郑和下西洋 | ❌→✅ | — | — | 拒答本来正确，修复前那轮是被排序口径牵连；两臂都有抖动 |
| factual-逆向思考 | ✅→❌(排序) | 1.000→1.000 | — | 排序口径抖动：gold 常排在第 10~11 位，查询扩展每次抽的词不同 → 必须同看 ctxRecall（见 §4.6.1 口径说明） |

**这轮复跑没有全绿**：三条列表题两条修好、一条退化。退化那条把真正的根因暴露出来了 —— 修的是"检索有没有拿到"，掉分却发生在"条目化看不看得到"。

#### 4.6.9 「六辑只剩两辑」的真实根因：条目化输入被截断（2026-10-26 已修，待复跑）

日志证据（同一个用例、两次运行）：

| 运行 | 档 2 窗口 | 送进精筛/上下文的块 | 条目化 items | 答案节数 | coverage |
|---|---|---|---|---|---|
| 修复前（18:22） | `[82-148]` | 63 块 / 54816 字（**无锚点补回**） | 2（"第一辑：自然""第二辑：人类"） | 2 | 0.667 |
| 修复后（18:40） | `[82-148]`，窗口内锚点 = `[83,88,99,112,123,136]` | 44 块 / 38495 字（**六个锚点块都在**） | 仍是 2 | 2 | 0.500 |

也就是说：六辑的正文与六个章节开头块**都在上下文里**，`chunk 83/88/99/112/123/136` 里也都写着辑名 ——
形式是 `第一辑` + 空行 + `自然` 这样的跨行标题（序数行在块内偏移 358~784 字），六辑名在正文里并非只有目录页 chunk 272 才有。
掉分发生在下一步：

- `BookChatPipelineService#buildFirstTurnOutline` 把 RAG 材料按**文档顺序**拼到 **6000 字**就 `break`；
- 用真实 chunk 文本按文档顺序拼接整个窗口（82~148，57592 字）复算各标题的**累计偏移**：
  第一辑 1173 → 第二辑 6101 → 第三辑 15549 → 第四辑 26501 → 第五辑 35870 → 第六辑 47071。
  6000 字只够装下**前两辑**的标题；
- 条目化只提出 2 条 → 流水线 `for i=1..2` → 答案结构上就只有 2 节（漏条在结构上不可能发生，所以"条目少"直接等于"答案少"）。

`OutlineTextCompactor` 的"其余条目仅标题"兜底也没接住：它只认 `1.`/`-`/`一、` 这类行首标记，
"第一辑"是正文中间的独立行，识别不到（这正是该组件的既定职责，见分块专项 §2.3）。

**修法（确定性，不额外调模型）**：目录本身就是这本书的"枚举表"，把它递给条目化。

| 位置 | 改动 |
|---|---|
| `TocStructureHint`（新增，`service/ai/rag`） | 纯字符串匹配从 `books.toc` 里挑出**与问题相关的目录子树**：读者引号里点名的章节名优先（命中即用它，不再看主题），其次用列表主题；匹配用最长公共子串（≥5 字且覆盖较短方一半）；**只有带子行的容器节点**才给提示（叶子节点给不出枚举）；上限 24 行 / 1200 字 |
| `AnswerOutlineService#buildFromContext` | 增加 `tocHint` 参数，在提示词里追加「本书目录（与问题相关的部分）」段；规则 1b 扩到"参考内容**或目录**给出明确枚举时必须逐项列出"，新增 1c"目录只用来确认所问部分的条目名与数量，不要把无关章节列进来" |
| `BookChatPipelineService#buildFirstTurnOutline` / `BookChatService` | 把 `book.getToc()` 传进 `resolvePipelineOutline`，现场算 hint；日志打 `[Pipeline] 首轮条目化带目录提示` |

**为什么不是别的做法**（各方案都试算过）：
- 提高 6000 字上限：按上面的复算，第六辑标题在累计 47071 字处 → 要把六条都装进条目化就得喂 4.7 万字，12s 预算必超时；
- 把"第 N 辑"加进 `OutlineTextCompactor` 的行首标记：只能给出 6 个"第 N 辑"标题，**名字（自然/人类/…）仍然进不去**，条目化拿不到可检索的关键词；
- 把目录块作为合成片段插进 RAG 上下文：它会被编号成【片段 1】被答案引用，而快照对 `chunkIndex<0` 的块不入库 → 引用与依据面板错位。

**代价与边界**：`TocStructureHint` 命中错误目录节点时会给出一段无关枚举，靠提示词规则 1c 抑制；
目录缺失（`books.toc` 为空）时 hint 为空，行为与改动前完全一致。单测 `TocStructureHintTest` 6 例
（真实目录：命中容器节点 / 无引号靠主题 / 问题不在目录里不给提示 / 命中叶子不给提示 / 目录缺失 / 短语过短）。

> **复跑状态**：以上改动已编译通过、后端单测全绿（553 例，0 失败）；
> 列表三条的复跑报告见 `docs/eval/eval-report-24689-tochint.json`（同配置、`--runs 1`，数字见下表）。
> §4.6.6 的表格仍是"上下文上限 A/B"那一轮的基线，不要把它当作"列表题已修好"的证据。

**列表三条复跑（nocap 基线 → postfix（仅修复 1~3）→ tochint（本次目录提示））**

| 用例 | coverage | pass(上下文) | 结论 |
|---|---|---|---|
| list-私人书单六辑 | 0.667 → 0.500 → **1.000** | ❌→❌→❌ | **根因判定成立**：日志 `hint=下篇 芒格私人书单 / 第一辑 自然 / … / 第六辑 处世` → `items=6` → `chunkAnchors 对齐 items=6, 有锚点=6/6` → 答案 6 节 3010 字，每辑各自带引用（`[2]`…`[27]`）。仍不 pass 的原因见下面第 3 条 |
| list-幸福与成功避免清单 | 0.000 → 1.000 → 1.000 | ❌→✅→✅ | 修复 1（主题覆盖校验）持续生效；ctxRecall 1.000→1.000→0.500 属精筛留块抖动 |
| list-心理倾向25条 | 1.000 → 1.000 → 1.000 | ✅→✅→✅ | 25 条齐全 + 总表；但 `refusal` 列本次为 **yes**，是评测口径缺陷（见下面第 1 条），不是模型拒答 |

**这轮复跑暴露的三个待办（不属于本次修复范围，别把它们记成已解决）**

1. **评测器假阳性拒答（已修，2026-10-26）**：`isRefusalAbout` 对切题用例只搜全文拒答词，25 条那条答案末尾写着
   "第11、18、19、20条…我按'这部分材料不足'如实标注了" —— 命中 `材料不足` → 整个切题用例被判 `refusal=yes`，
   汇总里 `falseRefusalRate=0.333` 全是这一条造成的。现切题用例走 `keywordRefusalNearAnyTopic`：
   拒答词必须出现在读者所问对象（gold_items）40 字邻域内才算拒答；拒答用例（有 refusal_topics）口径不变。
   回测三份报告：只有这一处 `true→false`，其余判定不变（该字段不参与 pass 门禁，纯指标修正）。
   修完 `falseRefusalRate` 才是可用指标。
2. **六辑 ctxRecall/recall 仍 0.000**：gold 标的是 chunk 0/1（前篇里"分自然、人类、经济、投资、人生和处世六辑呈现"那句概述），
   而回答依据的是六辑各自章节的正文与书名（档 2 窗口 [82,148]，实投 27 块，全是 82~138）。
   答案内容正确但依据块与 gold 不同 → 排序与上下文两个口径都判 0。要么把 83/88/99/112/123/136 补进该用例的 gold，
   要么承认这个用例只能靠 `coverage` 判分（`min_recall=0.5` 对它无效）。当前状态：**指标不绿但答案对**，
   属于评测标注问题而不是检索问题，改 gold 前不要动检索。
3. **样本量**：上面那张表只是 3 条列表用例的定向复跑（n=1）。14 条全量复跑见 §4.6.10。

#### 4.6.10 目录提示修复后的全量复跑（2026-10-26，14 用例 / n=1）

报告：`docs/eval/eval-report-24689-tochint-clean.json`（20:06–20:22，独占后端）。
与 §4.6.8 的 postfix 臂同代码，**唯一增量是 `TocStructureHint`**。

> **作废声明**：本节数字原先取自 `eval-report-24689-tochint-full.json`（19:48–20:00），那一次**被自我污染**：
> 我先用 `scripts/eval-postfix.sh` 起过一次评测并 `TaskStop` 掉包装脚本，脚本的 `stop_backend` 没执行，
> 它的 `mvn spring-boot:run`（18181）和 `node eval-bookqa.mjs` 都活着；随后我又对着同一个后端起了第二次评测。
> 证据：那一份日志里「25 条心理倾向」的 `question=` 出现 6 次（每问 3 次×2 次评测），
> `qaOutlineExecutor` 只有 2 线程 → 列表用例被并发挤爆，「首轮列表题条目化超时（12000ms）」出现两次。
> 该报告仍在仓库里（未删，供追溯），但**其聚合数字不再作为证据**。下面全部改用独占复跑那一次。

| 指标 | postfix | 本轮（含目录提示，独占） |
|---|---|---|
| pass^k（上下文口径） | 13/14（0.929） | **14/14（1.000）** |
| pass^k（排序口径） | 9/14（0.643） | 11/14（**0.786**） |
| ctxRecall 均值 | 0.875 | **1.000** |
| Recall@10 均值 | 0.514 | 0.722 |
| nDCG@10 均值 | 0.322 | 0.419 |
| 条目覆盖率均值（列表+多轮） | 0.875 | **1.000** |
| 引用有效率 / 拒答正确率 / 误拒率 | 1.000 / 1.000 / 0 | 1.000 / 1.000 / 0 |
| 首 token 中位数 | 21.4s | 28.6s |
| P50 / P95 延迟 | 30.9s / 83.3s | 35.0s / **175.1s**（P95 是单例离群，见待办 1） |

逐用例只看变化项（其余 11 例两臂同向、无回退）：

| 用例 | 变化 | 说明 |
|---|---|---|
| list-私人书单六辑 | coverage **0.500→1.000**、ctxRecall **0.000→1.000**、pass(上下文) **❌→✅** | 条目数 2→6，六辑各自锚定检索，材料里第一次同时含概述块与六辑正文（证据 22 段） |
| list-幸福与成功避免清单 | pass(排序) **❌→✅**（Recall@10 0.000→1.000，7 段证据） | 锚点块进了前 10 |
| factual-逆向思考 | pass(排序) **❌→✅** | gold 进到了前 10；该用例不走列表路径，属查询扩展抖动，不是本轮改动带来的 |

**仍未通过排序口径的 3 例，全部是同一个形状**：`ctxRecall=1.000` 但 `Recall@10=0`（mrr/nDCG 同时 0）——
gold 确实在材料里，只是排在提示词第 10 段之后。这 3 例的证据条数分别是
六辑 22 / 达尔文铁律 80 / 如果诗 83 段。整本书 273 chunks 不设上限（§4.6.6 的定案）
意味着 Recall@10 在这个体量下**结构性偏低**，不是排序坏了。要真正治它只有两条路：
(a) 给列表/长材料用例改用 `ctxRecall` 作为门禁（已在报告里并列输出 `pass^k(上下文)`）；
(b) 上 rerank（`qdrant.rerank-enabled`，默认关闭）把 gold 顶进前 10 —— 但 §4.6.6 已经证明"砍材料"两头亏，
rerank 必须先压 P95，见待办 1。

**本轮新增的待办（按优先级）**

1. **P95=175.1s 是单例离群，根因不是"条目变多"**（原先写错了，这里改正）。**→ 已跑完 A/B，见 §4.6.11：延迟假设成立，但因质量下降决定不上线。**
   175.1s 那条是 `factual-多元思维模型`——它**不走列表流水线**，条目数与它无关。
   日志：该次流式从 20:08:42 起、20:11:27 完成，**165 秒只产出 1909 字符**，
   且首个 token 直到 158.6s 才到达（`firstTokenMs=158589`）。
   同一轮里 `检测到模型思考 token（首个）` 出现 **14/14 次**——每条回答都在流思考 token。
   `AiScene.BOOK_QA` 的 `thinking` 默认是 `true`（`AiScene.java:23`），
   而 §3.3 的分节场景 `BOOK_QA_SEGMENT` 已特意用 `QA_WITHOUT_THINKING`（`thinking=false`）并写明
   "思考 token 成本不可接受"——同一份判断没有应用到主问答路径。
   **配置侧证据（本轮查到的具体值）**：`GET /api/admin/ai-scene-config` 显示
   `BOOK_QA` 绑定 `ai-gateway-agnes` 且 `thinkingEnabled=true, reasoningEffort=high`；
   `BOOK_QA_SEGMENT` 同一份配置但 `thinkingEnabled=false`。
   也就是说主问答每一次回答都在跑 **high 强度思考**，而流水线里的分节不思考。
   注意 `BOOK_QA` 不是靠枚举默认值在思考——它是**显式绑定**了 `reasoningEffort=high`，
   改它要走 `POST /api/admin/ai-scene-config/BOOK_QA/bind/{configId}`（会改本机 dev 的场景绑定，
   属于跨会话可见的配置改动，**我没有改**）。
   A/B 候选（按便宜程度排序）：
   (a) `BOOK_QA` 的 `reasoningEffort` 从 `high` 降到 `low`/空；
   (b) 完全关思考（`thinkingEnabled=false`）看答案质量是否掉；
   (c) 都不划算再考虑"合并 >6 节的生成"。
   门禁：跑同一份 14 例黄金集，比较 `firstTokenMs` 分布与 `pass^k(上下文)`，不能只看延迟。
2. **`replace` 事件 14/14 次全部触发（已定位，2026-10-26 已修日志）**（`BookChatService.java:370`）。
   它只在 `CitationValidator.validate` 改写过答案时才发，而 `[[N]] → [N]` 本身就是改写：
   模型写 `[[12]]`、校验器换成 `[12]`，字符串必然不等 → 每次都发 `replace` 并打 WARN。
   排除另一支：`sanitizeAiOutput` 命中会整段换成"抱歉，我无法回答这个问题"（`CommonUtils.java:548`），
   评测里 14 条答案都不是这句话；`citationValidity=1.000` 也说明 `invalidCount=0`。
   所以那 14 条 WARN 是**正常规范化**，不是问题信号——但它把真正的"非法引用编号"埋在了噪声里。
   修法：`replace` 事件照发，日志按 `citationResult.invalidCount()` 分支，
   `>0` 才 WARN（附 invalid 计数与 included 总数），否则 DEBUG。
   前端行为不变（`replace` 事件语义未改）。
3. **`absolute-min-score` 仍为 0（关闭）**：topScore 切题/离题差值 0.035（0.726 vs 0.691），与 §4.6 基线一致，维持关闭。
4. **样本量**：本轮仍 n=1。上线前需 `--runs 3` 复跑确认 14/14 稳定（§4.6.6 已声明过同一风险）。
5. **复跑纪律**（本轮新增，评测器侧）：一次评测必须独占一个后端。
   `TaskStop` 只终止包装脚本，不终止它的 `mvn spring-boot:run` 与 `node` 子进程；
   复跑前先确认 18181 上没有其它 java 进程，并核对日志里每个 `question=` 出现次数 == `--runs`。

---

#### 4.6.11 待办 1 的 A/B 已跑完：`reasoningEffort=high → low`（2026-10-26 22:54–23:05）

**做法**：经用户确认后，用 `POST /api/admin/ai-scene-config/BOOK_QA/bind/35` 把 `reasoningEffort`
从 `high` 改成 `low`（脚本 `/tmp/eval-low.sh`，`trap restore EXIT` 跑完立即还原），
跑同一份 14 例黄金集 n=1，报告 `docs/eval/eval-report-24689-thinking-low.json`。
**跑完已核验还原**：`GET /api/admin/ai-scene-config` → `BOOK_QA thinkingEnabled=true, reasoningEffort=high`。
两份报告的每条例试次数都是 1（无重试污染），对照可比。

| 指标 | high（§4.6.10 基线） | low |
|---|---|---|
| pass^k(上下文) | **14/14（1.000）** | 13/14（0.929） |
| pass^k(排序) | **11/14（0.786）** | 10/14（0.714） |
| ctxRecall 均值 | **1.000** | 0.875 |
| Recall@10 均值 | **0.722** | 0.597 |
| 列表覆盖率均值 | **1.000** | 0.750 |
| 引用有效率 | 1.000 | 1.000 |
| 拒答正确率 | 1.000 | 1.000 |
| 首 token 中位数 | 28.6s | **25.2s** |
| 首 token 均值 | 36.7s | **27.6s** |
| 总耗时 P50 / P95 | 35.0s / 175.1s | **34.3s / 106.5s** |

**结论：不因延迟改场景绑定；`BOOK_QA` 维持 `reasoningEffort=high`。**
延迟确实下降（P95 175.1s → 106.5s，175s 那条离群随 `firstTokenMs` 从 158.6s 降到 24.5s 一起消失），
证实 §4.6.10 待办 1 的判断：P95 离群来自主问答的 high 强度思考，不是"条目变多"。
但代价是 ctxRecall / 覆盖率同时下降，因此**不能上线**。

**更重要的发现：改 `BOOK_QA` 会同时改掉检索质量。**
`BOOK_QA_SEGMENT`（分节生成）本来就 `thinking=false`，列表题的分节在两次运行里完全一样
（`[图书问答-列表分节] … thinking=OFF(REASONING_EFFORT,effort=null)`），
可检索结果却变了：心理倾向 25 条的证据从 11 段变 57 段（档 2 精筛 `chunks=72→精筛=57` vs `→51`），
避免清单从 7 段变 5 段。原因是列表路径上的 LLM 步骤没有独立场景绑定，回退到了 `BOOK_QA`
（`ListQueryRetriever.java:481` `LIST_QUERY_REFINE`、`:545` `LIST_QUERY_CHAPTER_RANGE`、
`AnswerOutlineService.java:89` `ANSWER_OUTLINE`、`RagRerankService.java:86` `RAG_RERANK`）。
**含义**：`ai_scene_config` 里 `BOOK_QA` 这一行不只管最终回答，它还是这些步骤的实际生效模型参数；
任何针对它的 A/B 都必须同时看检索指标，不能只看延迟。若要单独调它们，需先给这四个场景做独立绑定。

#### 4.6.12 A/B 暴露的真缺陷：流水线会答"另一个主题"的清单（2026-10-26 已修）

**现象**（`eval-report-24689-thinking-low.json` 的 `list-幸福与成功避免清单`，coverage 1.000→0.000）：
读者问「在“如何获得幸福和成功”一节里，芒格说要避免哪些事情？请逐条列出」，
回答却是「### 第 1 节 · 自然 / 第 2 节 · 人类 / 第 3 节 · 经济 …」——**这是「芒格私人书单六辑」那道题的清单**。
答案里每条都有合法引用（`citationValidity=1.000`），文本长度 2508 字，看起来完全可信。

**链路**（`kbook-boot-v7.log` 23:00:54–23:01:07，`[kbook-sse-2]`）：
1. 档 2 覆盖校验正常拒绝：`[档2-TocRange] 目录窗口未覆盖主题「芒格避免的不幸事」`；
2. 档 3 兜底检索**也没有命中该章节**：召回 15 → 候选 46 → 精筛 26，入选 chunkIndex 是 `0,1,2,3,4`（书前记/书单简介）；
3. `buildFirstTurnOutline` 于是拿到一份"只有前记"的材料做条目化，
   LLM 从材料里挑了**它唯一能看到的清单**：`[Outline] 条目化成功: topic=芒格获得的40本私人书单 items=6`；
4. 流水线按这 6 条逐节生成 → 一篇主题错误但引用合法的答案。

**根因**：首轮条目化只校验了"条目数 ≥ 2"，从不校验"条目还在回答读者问的那个主题"。
材料取错时，条目化的输出必然是一份材料里存在的清单——所以它总能通过条数校验。
`TopicCoverageCheck` 只保护了检索窗口（第 1 步），没有保护条目化（第 3 步），中间缺一环（R2 教训）。

**为什么它比"答得短"更危险**：错误答案最长、结构最规整、引用编号全部合法，
`citationValidity` 和 `refusal` 两项指标都是满分——单看指标完全发现不了，只有和 `gold_items` 对齐才露馅。

**修法**（`BookChatPipelineService.buildFirstTurnOutline`，在调用条目化**之前**）：
先确认"喂给条目化的那份材料里，真的有读者指名的那一节"——用 `TopicCoverageCheck.quotedPhrases(question)`
取引号里的章节名，再用新增的 `TopicCoverageCheck.coveredPhrases(phrases, context)` 在材料里做确定性匹配；
不含就 `log.warn` 并 `return null`，退回单次流式。为此把 `quotedPhrases` / `normalize` 放宽为 public（只改可见性，语义不变）。

第一版我写的是"条目化之后再校验 outline 的 topic/条目名与问题字符串是否吻合"，**已废弃**：
它在真实数据上有假阴性 —— 六辑那道题材料是**对**的，只是 LLM 把 topic 写成「芒格跨学科书单六大辑」，
字符串不吻合就被误退回单次流式。换成"材料里有没有这个章节名"之后，
用两份真实报告里的入选 chunkIndex 复算（273 chunks 原文）：
`list-幸福与成功避免清单`（low，材料 = chunk 0~4）不含「如何获得幸福和成功」→ 拦住；
同一用例在 high（材料含 chunk 56/57）含该章节名 → 放行；
`私人书单六辑`（high 与 low 两次材料都含「芒格私人书单」）→ 放行；
`25 条心理倾向`（材料含「人类误判心理学」）→ 放行。即**只拦事故、不伤正常用例**。
另外闸门不用 `listTopic` 兜底：它是泛化措辞（「芒格避免的不幸事」正文里就没有），拿它判定会把正常用例误退回。

**取舍**：闸门会让"检索没命中但材料里恰好有可用清单"的情况也退回单次流式——这是故意的，
宁可答案短、缺条目，也不要一篇主题错误、引用逼真的长答案。

**单测**（`BookChatPipelineServiceTest`，+3 例）：
`firstTurnOutlineOffTopicFallsBackToSingleStream` 复刻事故输入（问避免清单、材料只有书前记）断言 `null`；
`firstTurnGuardPassesWhenMaterialCoversChapter` 同一问题换成含该章节的材料，断言流水线照常触发（成对断言，防假阴性）；
`quotedPhrasesDriveTheGuard` 钉住闸门输入口径（有引号取章节名、无引号为空即不参与）。
`TopicCoverageCheckTest` 7 例复验可见性放宽没改语义。

**运行时验证状态（未做，别当成已验收）**：本节改动只在本地单测层证明（10 例全绿）。
18181 上的评测跑到一半被我主动停掉——那一次用的是已废弃的第一版闸门，数字不能描述最终代码。
最终版的运行时确认需要：重启后端 → 独占跑 14 例 → 核对日志出现
`[Pipeline] 首轮检索材料未覆盖提问指名的章节` 且 `list-幸福与成功避免清单` 不再输出六辑内容。

#### 4.6.14 检索侧的真根因：那一节不在目录里 → 正文标题定位（2026-10-26 已修）

§4.6.12 的闸门只是止血：**答案错误的源头是检索**。档 2 覆盖校验正确拒绝后直接跳到档 3 向量召回，
而档 3 用的是泛化主题「芒格避免的不幸事」——它压根不是书里的措辞，于是入选的是 chunk 0~4（书前记），
gold（chunk 56/57）连材料都没进。

**关键事实**：该节标题在正文里是原样存在的。真实 chunk 文本：
- chunk 57 **开头就是**「如何获得幸福和成功 如果你在生活中唯一的成功就是通过买股票发财…」；
- chunk 56 里这个小节名出现在该块**中段**（去空白后偏移 571），它只是列了一次小节名。
- 全书 273 chunks 里，「如何获得幸福和成功」只出现在 56、57 两块。

所以缺的不是"更强的语义检索"，而是**一条确定性的正文标题定位**：读者引号里的章节名，
若在某个块的开头出现，那个块就是这一节的起点。

**改法**（`ListQueryRetriever.tocRangeRetrieve`）：档 2 的覆盖校验失败后，不再直接放弃，
先调 `findChapterRangeByTitle(book, question, …)`——取引号里的章节名，在全量 scroll 的正文块里找
**出现在块开头 40 字以内**的那个块（新增 `TopicCoverageCheck.startsWithTitle`，
与 `covered` 的区别是它看位置：`covered` 只问"提没提到"，会命中正文中的引用与小结）。
命中则以该块为中心开窗（前 1 块、后 3 块），把该块同时登记为锚点（走 `mergeAnchorHeads` 保证不被精筛丢掉）。
两条确定性路径（目录窗口 + 正文标题）都不覆盖主题，才返回空让上层换档 3。
超过 `MAX_ANCHOR_SCAN_CHUNKS=3000` chunks 的书不做正文定位（与既有锚点法同一上限）。

**为什么不用 LLM 或向量补这一刀**：这一节的标题是读者原文引用的，字符串匹配就能定准；
用模型会引入新的抖动，且已经证明"让 LLM 从目录里挑最像的行"正是切错窗口的来源。

**用真实数据复算的定位精度**（273 chunks，规则=标题出现在去空白后前 40 字）：
「如何获得幸福和成功」→ 只命中 **chunk 57**（56 不命中，因为它在中段）；
「芒格私人书单」→ chunk 1、83；「人类误判心理学」→ 无块开头命中（该节标题在书里不是这样写的，
定位不会误开窗口，仍走目录档 2 的既有路径）。

**单测**（`TopicCoverageCheckTest` +4，共 11 例）：用两段**真实 chunk 文本**做黄金值——
chunk 57 判为起点 true；chunk 56 判起点 **false**，而同一块 `covered()` 为 **true**（成对断言，
钉住"定位必须看位置、不能沿用覆盖判定"这条区别）；「第三讲 + 换行 + 标题」仍算起点；跨行标题算起点。
全量 `mvn -o test` 见 §0.1 基线行（用例数以输出为准）。

**运行时验证（已确认，2026-10-26 23:55 报告 `eval-report-24689-titleanchor.json`）**：
判据全部命中。日志两次出现
`[档2-TocRange] bookId=24689 正文标题定位命中: title=如何获得幸福和成功, 起始chunkIndex=57, 窗口=[56-60]`，
检索输出 `[档2-TocRange] … range=[56-60], chunks=5→精筛=5→输出=5`（此前该用例走档 3，材料是 chunk 1/56/57/189/269/270/272 的拼盘）。
该用例 `ctxRecall` 0.875→**1.000**、`Recall@10` 0→**1.000**、`pass^k` **false→true**；
流水线条目化只出 3 条（`topic=芒格建议避免的失败原因` / `如何获得幸福和成功：芒格建议避免的事项`），
答案里不再出现「自然/人类/经济…」六辑条目 —— §4.6.12 的闸门在这一轮没有被触发（材料已经对，闸门不再需要兜底）。

**同一轮的两个反例（不是本次改动造成，见 §4.6.15）**：`心理倾向25条` 的 ctxRecall 从 1.0 落到 0.5、
`私人书单六辑` 从 1.0 落到 0 —— 两次日志对照显示这两个用例的档 2 窗口**完全相同**（`[201-272]` / `[82-148]`），
差别只在条目化：`心理倾向25条` 这次条目化超时 12s 退回单次流式（上一轮条目化成功）。

#### 4.6.15 条目化 JSON 局部坏掉导致整份清单被丢弃（2026-10-27 已修）

对照两轮后端日志（`kbook-boot-clean.log` 20:13–20:19 与 `kbook-boot-v7.log` 23:41–23:56）找列表用例的波动源，
结果不是检索、是**条目化输出的解析**：一轮 14 例里有 **7 次** `[Outline] JSON 解析失败` +
`条目化无结果（答案可能无列表结构）`，全部退回单次流式。真实报错只有三类，都是模型写坏一两个字符：

| 报错 | 真实样本（日志 head） |
|---|---|
| `was expecting comma to separate Object entries` | `{"id":2,"title" "第二辑 人类",…}`（字段名后冒号漏写） |
| `was expecting a colon to separate field name and value` | `{"keywords"…}` 后续字段错位，源头是值里有未转义的英文引号 |
| `Unexpected end-of-input: expected close marker for Array` | 25 条清单写到第 24 条被截断，数组没闭合 |

原实现 `parseRaw` 只做整体 `readTree`，**一处坏字符就把已经提取好的整份清单丢掉** ——
日志里那条 25 条心理倾向（`chars=1559`）标题全对，只是第 N 条的摘要里少写一个转义。
丢掉之后的代价是流水线整体退回单次流式，列表用例的 `pass^k` 直接受摆布，
这就是列表用例逐个跑之间上下跳的一个主因（另一个是 12s 条目化超时，见 §0.1 的 outline 行）。

**改法**（`AnswerOutlineService.parseRaw` 失败分支）：新增 `salvageItems` ——
按「条目对象起点」（`{"id"|"title"|"gist"|"keywords"`，冒号可选）切段，每段单独 `readTree`；
坏段退化为正则取 `title`/`id`/`keywords`（流水线展开时真正用到的就是标题）。
`salvage ≥ 2` 条才当清单（与 `normalize` 的 ≥2 硬约束一致，宁缺勿假清单），
`topic` 置空（整体头已不可信）。日志改为打印「salvage 保留 N/M 条」。

**不用大括号配对切段**：未转义引号正是让「引号内外」判定错位的东西，
在最需要 salvage 的输出上必坏 —— 第一版这样写，测试当场失败后改掉。

**单测**（`AnswerOutlineServiceTest$Salvage` +6，共 25 例）：三类真实坏输出各一例
（未转义引号 / 缺冒号 / 末条截断），断言**标题序列完整**而不是只断言非 null；
`salvage` 只剩 1 条仍返回 null；`items:[]` 不会被误当成清单；
再加一例变异对照——完好输出走的仍是原路径，`topic`/`gist`/`keywords` 都保留
（证明 salvage 没有变成第二套语义）。

**单测**（`AnswerOutlineServiceTest$Salvage` +7，类内共 26 例）：三类真实坏输出各一例
（未转义引号 / 缺冒号 / 末条截断），断言**标题序列完整**而不是只断言非 null；
`salvage` 只剩 1 条仍返回 null；`items:[]` 不会被误当成清单；`topic` 在 salvage 路径一并保留；
再加一例变异对照——完好输出走的仍是原路径，`topic`/`gist`/`keywords` 都保留
（证明 salvage 没有变成第二套语义）。全量 `mvn -o test` 见 §0.1 基线行（用例数以输出为准）。

**运行时结果（报告 `eval-report-24689-salvage.json`，2026-10-27 00:43–00:52，14 例 n=1）**：
判据成立。日志里 `[Outline] JSON 整体解析失败，逐条 salvage 保留 10/10 · 8/8 · 5/5 条` 三次，
`条目化成功` 从 6 次升到 **8 次**，salvage 的三条清单全部进入流水线（不再退回单次流式）。
`pass^k(上下文口径)` 14/14（**1.000**，上一轮 12/14）。
另一次 `salvage 仅剩 1 条` 是模型真的只输出一条（topic=「吉卜林《如果》在书中的引用与位置安排」），
按 ≥2 硬约束判 null 是正确行为——不是漏救。
但 **排序口径 `pass^k` 仍 9/14、`私人书单六辑` 仍 fail**：这一例的 fail 不是流水线，
是评测基准自身标错，见 §4.6.16。

**顺带查到的规模事实**（`ai_usage_log` 全量聚合，非单次样本）：
`ANSWER_OUTLINE` 平均输入 1,609 tok、输出 392 tok，**平均 10.95s、最长 69.3s**；
同期 `LIST_QUERY_REFINE` 输入 2,599 tok 只要 3.30s。条目化是纯 JSON 抽取，
输入比精筛还小却慢 3.3 倍——因为它走 `ANSWER_OUTLINE` 场景，未显式绑定时**继承 `BOOK_QA` 的
`reasoningEffort=high`**（`ai_scene_config` 里 `ANSWER_OUTLINE` 的 `thinking_enabled` 为空、
`reasoning_effort` 为 NULL，见 §4.6.11 的场景回退说明）。这就是 12s 预算下首轮条目化必然处在超时边缘的原因。

#### 4.6.16 评测基准自身的错标：私人书单六辑 gold 标成 [0,1]（2026-10-27 已修）

`b24689-list-私人书单六辑` 连续几轮 `pass^k=false`，我先怀疑流水线、又怀疑档 2 窗口，
去 Qdrant 逐块读原文才发现**是 gold 标错**：
- 问题问「分成哪几辑」，答案要六辑齐全；但 `gold_chunks=[0,1]`。
- chunk 1 里确实有那句枚举（「下篇"芒格私人书单"…分自然、人类、经济、投资、人生和处世六辑」）；
  **chunk 0 只有书名页、著译者与序言开头**，不含辑目，也不含「40 本」。
- 六辑标题在正文的实际落点（去空白后位置，273 chunks 全扫）：
  第一辑=83(@301)、第二辑=88(@751)/89(@239)、第三辑=99(@735)/100(@44)、
  第四辑=112(@613)/113(@43)、第五辑=123(@529)、第六辑=136(@513)。
  chunk 272 是书末自动生成的 Table of Contents，六辑标题挤在一起（@165..@200），不作为 gold。

**改法**：gold 改为 `[1,83,88,89,99,100,112,113,123,136]`，并在该用例里加 `gold_source` 字段
写清每辑落点与「chunk 0 错标」的原因（防止后来者再按旧标注去找原因）。

**为什么这条必须改而不是"容忍"**：`min_recall=0.5` 在错误的 gold 上算，等于要求系统召回
一本前记的碎片。改完后同一轮报告里该用例 `ctxRecall=0.900`（10 块里命中 9 块）、`Recall@k=0.300`
——**答案本身是 6/6 辑齐全的流水线回答**（`coverage=1.000`、引用合法），
fail 的残留原因是档 2 窗口只给了 `[82-148]`（67 chunks→精筛 27），
第五辑尾（123）之后与第六辑（136）只进了一部分。这是检索窗口问题，不是基准问题，
两者必须分开记账。

**同一形状的另一组**（不打算改基准，只记账）：`逆向思考`/`如果诗`/`达尔文铁律`
三例 `ctxRecall=1.000`、`Recall@k=0`、`pass^k(上下文)=✅` 但排序口径 ❌。
按跨度展开后重算 gold 名次（`retrievedChunks` 是 `[start,end]` 跨度，必须展开再数）：
gold 分别在 score 序第 **22 / 24 / 43** 位，而提示词里实际拼了 91 / 84 / 79 段。
即 **Recall@10 在 top-k=100 的材料体量下结构性偏低**，三例答案内容都对（例如
`如果诗` 摘出了诗句、`达尔文铁律` 两条铁律都给全）。§0.1 已记的「pass^k 必须与 pass^k(上下文) 一起看」
在这一轮再次生效：**没有把这三例算成系统缺陷**。


#### 4.6.17 目录已经是枚举表时不再让模型抄一遍（2026-10-27）

§4.6.9 的 `TocStructureHint` 只是把目录当**提示**递给条目化模型，条目仍要模型生成。
本轮量了这条生成的成本（`ai_usage_log`，scene=`ANSWER_OUTLINE`）：平均 **10.95s**、
最长 69.3s，且**输出越长越慢**（1291 输出 tokens 平均 23.5s，67 tokens 只要 3.3s）。
首轮条目化预算是 12s（§0.1 已从 3s 放宽过一次）——25 条清单在这个预算下经常撞线
（同一后端两轮评测里超时 4 次），撞了就退回单次流式，流水线根本不触发。而目录子行本身就是书里写好的枚举，
模型做的事就是「把目录行抄一遍 + 编 gist」。

**改法**：目录匹配到节点且有直接子行时，条目直接取目录子行（`TocStructureHint.childTitles`
→ `BookChatPipelineService.outlineFromTocChildren`），完全不调模型；条目顺序＝目录顺序，
标题原样保留（含「第一辑」这类序数前缀）。子行上限 60 只用于防异常目录，不参与正常清单截断
——`MAX_HINT_LINES=24` 是「喂给模型的提示」的体积上限，条目本身要全（25 条心理倾向截到 24 就少一条）。
闸门顺序不变：先做 §4.6.12 的引号章节覆盖校验，材料不覆盖提问指名的章节仍退回单次流式，
目录路径不能绕过它（否则「问 A 章节答 B 章节枚举」的事故会借道复活）。

**边界**：清单不在目录里时（「人类误判心理学」25 条不在该书目录中）目录路径给不出条目，
仍走模型条目化——目录不是唯一来源，这条由单测固定。

**单测**：`TocStructureHintTest` +3（六辑一个不少 / 清单不在目录里返回空 / 25 条子行不被截到 24），
`BookChatPipelineServiceTest` +2（目录有枚举时 `verify(never())` 断言模型条目化没被调用；
目录无枚举时 `verify()` 断言仍调用模型）。第二例是变异对照：如果哪天目录路径变成无条件生效，
它会当场失败。

**运行时（报告 `docs/eval/eval-report-24689-tocchildren.json`，1 轮 / 3 条列表用例）**：
```
[Pipeline] 首轮条目化带目录提示: topic=芒格私人书单的书籍辑录, hint=下篇 芒格私人书单 /   第一辑 自然 / ...
[Pipeline] 首轮条目化直接取目录子行（不调模型）: items=6, topic=芒格私人书单的书籍辑录, alignedAnchors=true
```
另外两条没有被目录路径接管：`心理倾向25条` 与 `幸福与成功避免清单` 的条目化仍由模型产出
（日志 `[Outline] 条目化成功: items=25` / `items=6`），即新路径**没有变成唯一来源**。

**这一版第一稿是坏的，而且指标没抓到（2026-10-27 当天修复）**：
同一条用例延迟看着降到 **11156ms**、`coverage=1.000`、`citation=1.000`，但答案只有 **850 字**，
六节全是预写占位「这一节的原书材料没检索到，我先不展开」——三份报告对应的三条回答
（id 4868/4870/4872）长度、开头完全一致。日志证据：`分节完成: sections=6/6, 有材料=0/6`。
两个原因叠在一起才发生：
- **条目没有检索依据**：目录路径造出的条目 `gist=null`、`keywords=[]`、`chunkAnchors=null`。
  逐条取材料先按锚点、再按 keywords 向量召回，两者都空 → 直接 `Material.empty()`，整节不调模型。
- **锚点对齐也落空**：正文里的辑标题是「第一辑」换行「自然」，`literalHit` 用的字面 `contains`
  对「第一辑 自然」永远不命中 → `chunkAnchors` 一格没对齐。

**改法（两处都在产品侧）**：① 目录条目把标题同时当关键词（`keywords=[title]`），
保证关键词兜底可用；② `literalHit` 比较前统一去空白——只影响"能不能找到材料"，不改条目内容。
单测改成用**跨行**的正文标题造材料，并断言 `chunkAnchors` 非空、每条都有 keywords：
如果检索依据再次丢失，这条测试当场失败。

**评测侧同时补了两个盲点（否则这类空洞答案会一直拿满分）**：
- `itemCoverage` 只做 `answer.includes(item)`，**标题里带条目名就算覆盖** → 六节占位答案 `coverage=1.000`。
  新增 `stripPlaceholderSections`：正文只剩预写占位文案的分节（标题一并）不计入覆盖率。单测 +3。
- gold 写「糟糕的婚姻」、答案小标题写「避免糟糕婚姻」→ 覆盖率误判 0.75（报告 `...-materialfix.json`），
  这是**假阴性**不是内容缺失。改法：只差结构助词（的/地/得）时视为同一条，去助词后短于 2 字不参与匹配
  （否则空串命中任何答案）。单测 +1。

**修好后的运行时（报告 `docs/eval/eval-report-24689-tocchildren-materialfix2.json`，1 轮 / 3 条列表用例）**：
`pass^k(上下文) 3/3`、`coverage 1.000`、`citation 1.000`、`false refusal 0.000`。
六辑用例 `分节完成: sections=6/6, 有材料=6/6`，答案 **8209 字**（首节「第一辑 自然」有正文），
延迟 34933ms、`ctxRecall=0.900`；排序口径仍 ❌（`Recall@k=0.400`）——那是 §4.6.16 记过的
档 2 窗口只覆盖到第六辑一部分的老问题，不是这次改动引入的。

**同一轮的另一处记账**：`心理倾向25条` 的 `ctxRecall=0.500`（§4.6.15 那轮同一用例是 1.0 与 0.5 抖动），
答案本身 25/25 齐全、`coverage=1.000`。掉分发生在检索窗口（档 2 窗口只覆盖部分条目的原文块），
不是条目化——条目来源换成目录后，这条的 ctxRecall 没有变好也没变坏，符合预期：
它压根不走目录路径。**长清单的条目化预算问题另见 §4.6.18**（12s 超时 → 长清单 25s）。

**全量 14 例最终验收（报告 `docs/eval/eval-report-24689-tocchildren-final.json`，1 轮，跑在修复版上）**：
`pass^k(上下文) 13/14`、`coverage 1.000`、`citation 1.000`、`refusal 1.000`、`false refusal 0.000`、
`ctxRecall 0.950`、`Recall@k 0.622`、`P50/P95 23.3s / 76.1s`。
- `私人书单六辑`：`ctxRecall=0.900`、`coverage=1.000`、延误 41055ms、答案有正文（§4.6.16 的排序口径仍 ❌，老问题）。
- 排序口径 ❌ 的仍是 §4.6.16 记过的三例（`逆向思考` / `达尔文铁律` / `如果诗`，`ctxRecall=1.000`）。
- `量子计算` 本轮 ❌ 且 **上下文口径也 ❌**：`refusal=yes`（确实拒答了），但 `must_not_claim` 命中「量子叠加」。
  看原文是反问句「你拿量子叠加态去评估一家保险公司…」——**评测器的假阳性**，不是答案在编书里的内容。
  现有否定窗口只有 ±10 字，管不到这种反事实修辞；这属于评测口径待改，**不要靠放宽窗口把这条弄绿**。

> 早先那份 `eval-report-24689-tocchildren-full.json`（14/14 ctx）跑在**空答案版本**上，
> 其中 `私人书单六辑` 的 `coverage=1.000` 是靠占位节标题骗到的，只能当"其余用例未受影响"的旁证，不作验收。

**当时的形状（已由 §4.6.18 处理）**：`心理倾向25条` 的 25 条不在目录里，仍要模型条目化，
实测某次首轮条目化 **12s 超时退回单次流式**（日志 `首轮列表题条目化超时（12000ms）`），
单次流式这次碰巧 25/25 齐全。同一后端本次两轮评测里 `首轮列表题条目化超时` 出现 **4 次**、
`条目化成功` 12 次、`JSON salvage` 5 次 —— 超时不是孤例。
条目化的耗时与输出长度正相关（`ai_usage_log` scene=ANSWER_OUTLINE 平均 10.95s、最长 69.3s）。
处理方式见 §4.6.18：长清单放宽到 25s，短清单仍 12s。



#### 4.6.18 长清单的条目化预算：只给"确实是长清单"的题放宽（2026-10-27）

**问题**：目录路径只能覆盖「目录里写了枚举」的清单。「人类误判心理学」的 25 条不在目录里，
仍要模型条目化；而条目化耗时与输出长度正相关（`ai_usage_log` scene=ANSWER_OUTLINE：
1291 输出 tokens 平均 23.5s、67 tokens 只要 3.3s），12s 预算下同一后端两轮评测实测超时 4 次。
超时就退回单次流式：`ctxRecall` 在 0.5~1.0 之间抖，而且拿不到逐条引用。

**改法**：`FIRST_TURN_OUTLINE_LONG_MS = 25s`，只在 `looksLikeLongList(context, matchCount)`
为真时使用。判据是确定性的两条（任一成立）：
- 材料片段数 ≥ 40（实测 25 条清单的档 2 窗口出 49 段；短清单只有 5~7 段）；
- 材料里行首编号行 ≥ 12（`1.` / `第N条` 这类，片段少但条目密的情况）。

短清单仍是 12s。判错的代价只是"多等一会儿"，不改变内容——这一点写进注释，避免后来者
把它当成质量判定往上加语义。

**单测**：`BookChatPipelineServiceTest#longListDetection`（49 段 → 长；5 段 → 短；
12 行编号 → 长；11 行 → 短；null 材料 → 短）。

**运行时（报告 `docs/eval/eval-report-24689-longlist-budget.json`，1 轮）**：
```
[Outline] 条目化成功: topic=查理·芒格的人类误判心理学（…）items=25
[Pipeline] 首轮触发: items=25, ...
[Pipeline] 分节完成: bookId=24689, sections=21/20, 有材料=20/20
```
该用例 `pass^k=✅`、`Recall@k=1.000`、`ctxRecall=1.000`（上一轮 0.5）、`coverage=1.000`、
`citation=1.000`，延迟 96.5s（前一条走单次流式的同题是 76~82s）。
**用延迟换到了检索覆盖与逐条引用**，这是本轮的取舍；25 节的流水线本来就长，
要不要再压延迟（比如 21 节里只展开前 12 节）另开一轮用数据决定，别拍。

**没证明的部分**：25s 预算下 25 条是否**每次都**生成完整——`--runs 3` 已经跑过，结论是"预算够用，
但清单完整性还要另加一道闸"：某一轮模型只给出 **8 条**（`items=8`），产出 8 节、`coverage=0.32`，
首轮直接 fail。也就是说**超时不是唯一失败形态，模型会主动少抄**。

**追加的确定性闸门**：材料本身写明总数时（`declaredItemCount`：`(共|一共|总共)? N 条`），
条目数不足总数的八成即判残缺，退回单次流式——半份清单（8/25 节）比散文答案更糟。
材料没写总数时不判（8 条也可以是完整清单）。单测 3 例（8/25 → 退回；25/25 → 放行；解析口径）。
`--runs 3` 复跑（报告 `docs/eval/eval-report-24689-longlist-guard-runs3.json`）：
`pass^k(上下文) 3/3`、coverage `1.000 / 1.000 / 0.960`、ctxRecall 全 1.000。
这三轮里闸门**没有触发**（模型每轮都给了 25 条），所以它的运行时证据只有单测层面；
上一轮那个 8 条样本是触发条件的真实来源。

> **这个闸门对本书基本空转（2026-10-27 复核，别把它当已解决）**：全书 273 个 chunk 里
> 没有任何「25 条 / 25 种」这样的总数声明（`\d{2}\s*[条种项]` 只命中「100 种模型」「20 种或 30 种」），
> 所以它从未触发。随后 `--runs 3` 全量里 `心理倾向25条` 有一轮只出 **10 条**
> （`sections=10/10`、覆盖率 0.4），闸门毫无反应——**清单完整性在这个数据集上仍没有确定性约束**。
> 可选的收敛路径：① outline 拆两段（先只出标题、再按节补 gist/keywords，标题输出短、不易被截断）；
> ② 接受"模型给几条就展开几条"。两条落地前，不要把这条用例的失败归因成检索问题。

**稳定性汇总（列表三条 + 量子计算，n=5：全量 `--runs 3` 报告 + 子集 `--runs 2` 报告
`docs/eval/eval-report-24689-sub4-runs2.json`）**：

| 用例 | ctx 口径 | coverage 分布 | 失败形态 |
|---|---|---|---|
| 心理倾向25条 | 4/5 | 1.000 / 0.400 / 1.000 / 0.960 / 1.000 | 那一轮 0.400 = outline 只出 **10 条**（`sections=10/10`），闸门因无总数声明未触发 |
| 幸福与成功避免清单 | 5/5 | 0.750 / 1 / 1 / 1 / 1 | 0.750 那一轮是 3/4 条 |
| 私人书单六辑 | 5/5（ctx）/ 0/5（排序） | 1.000（5/5） | 恒定的排序口径失败（gold 名次在 10 名外），内容不缺 |
| 量子计算 | 4/5 | — | 1 轮 `must_not_claim` 命中反问句修辞（评测器假阳性） |

**结论**：列表路径的"答案内容"已经稳定（coverage 除一次截断外都满）；剩下的失败集中在
**outline 条目数不稳**、**排序口径**、**评测器否定/反事实识别**三处，都不是检索缺材料。

**仍未解决**：排序口径 `Recall@k` 在这条用例上会掉到 0（`ctxRecall=1.000`）——
25 个条目的 gold 分散在窗口里，top-10 装不下，与 §4.6.16 记的结构性问题同源。

#### 4.6.19 A/B rerank 时发现的真缺陷：`RAG_RERANK` 未绑定会让检索整条变空（2026-10-27 已修）

本轮想验证「LLM rerank 能否把 gold 从第 22/24/43 名拉进 top-10」，用 `QDRANT_RERANK_ENABLED=true`
起了后端，跑排序口径失败的那 5 个用例（3 事实 + 2 列表）。

**A/B 直接打出产品缺陷**：3 条事实题的答案是「这本书里我没找到…」，`evidenceCount=0`、延迟 3s。
日志：
```
RAG 检索异常: bookId=24689 - 场景 [RAG_RERANK] 回退到 TOOL 默认配置失败，请添加 roles=TOOL 的启用配置
[AnswerGuard] 检索为空，拒答: ...
```
链路：`RagRerankService.score()` 里 `chatModelFactory.buildForScene(AiScene.RAG_RERANK)` **在 try 之外**，
场景没绑定 + 没有 TOOL 兜底配置时直接抛 → 异常冒泡到 RAG 主流程 → 上下文为空 → 预写拒答。
也就是说这个"可选增强"一旦打开，会让**所有常规问答**变成拒答；计划里写的「失败保持向量顺序」
（§3.8）只在"模型调用/解析失败"这一层成立，**没有覆盖"模型根本拿不到"**。

**改法**：`buildForScene` 包进 try/catch，解析失败记 WARN 并返回空分数 → `rerank()` 走既有的
"无有效分数，保持向量顺序"分支。单测 +1（`buildForScene` 抛 `IllegalStateException` → 返回原列表，
`isSameAs(input)`）。

**运行时确认**（报告 `docs/eval/eval-report-24689-rerank-failopen.json`）：
日志每轮出现 `[Rerank] 场景模型解析失败，跳过重排（保持向量顺序）` + `[Rerank] 无有效分数，保持向量顺序: candidates=30`，
检索不再为空；`逆向思考` 的 `Recall@k 0→1`（通过），另两条事实题仍是 `Recall@k=0 / ctxRecall=1.000`（§4.6.16 的结构性口径）。

**A/B 结论（关于 rerank 本身）**：**这次没能验证 rerank 的排序收益**，因为该部署里 `RAG_RERANK`
根本没有可用的模型配置，两臂实际都是纯向量顺序。`qdrant.rerank-enabled` **维持默认 false**。
要真正做这个 A/B，得先给 `RAG_RERANK` 绑定一个 TOOL 模型配置（会改本机 dev 的场景绑定，属跨会话可见的配置改动，未做）。

**同轮附带发现**：`心理倾向25条` 在 25 节长答案下 `citationValidity=0.949`（<0.99 判 fail）——
长答案的引用编号越界比短答案更容易发生，是引用契约在长清单上的独立问题，记下来，未修。

#### 4.6.20 长答案 `citationValidity=0.949` 的根因：编号上界与证据行数不是同一个口径（2026-10-27 已修，运行时待复验）

追到具体数字：该次回答引用编号最大到 **14**，`/evidence` 只有 **13 行**，评测按 13 判，
于是 `[14]` 计为非法 → 37/39 = 0.949。两处口径分别是：
- 产品侧校验上界 = `PipelineFragmentRegistry.size()`（分配过编号的片段数），
- 评测/证据侧上界 = `ai_retrieval_snapshots` 行数，而 `AiRetrievalSnapshotService.save()`
  用 `seen.add(chunkIndex)` **按 chunkIndex 去重**，并且 `chunkIndex < 0` 的行**直接跳过**。

所以只要流水线里出现一个"没有 chunkIndex"的片段（`getChunkIndex` 返回 -1），registry 仍会给它
编号、却不落证据行 → 编号数比证据行数多 1 → 最后一个编号永远"非法"。

**改法（产品侧，让两个口径同源）**：`PipelineFragmentRegistry.label()` 对 `chunkIndex < 0` 的片段
**不编号**（文本仍进材料供参考，但不可被引用），并删掉随之失效的 `fallbackKey` 兜底编号。
这样"编号 1..N"与"证据行 1..N"一一对应。单测改写成新契约
（`unnumberedWhenChunkIndexMissing`：无 index → 不出现「片段 N」、`size()=0`；随后有 index 的片段仍从 1 连续编号）。

**运行时状态（已复验）**：第一次复跑（`docs/eval/eval-report-24689-citation-contract.json`）该用例走了单次流式，
`citationValidity=1.000` 不能算证据。再跑一次流水线确实触发
（日志 `首轮触发: items=25` / `分节完成: sections=21/20, 有材料=20/20` / `流水线完成: sections=20, totalChars=6025`），
同一用例 **`citationValidity=1.000`、`Recall@k=1.000`、`coverage=1.000`**（报告
`docs/eval/eval-report-24689-citation-contract2.json`）——编号与证据行数同源后，最后一个编号不再越界。

#### 4.6.13 入库计划 §2.5「合并后重叠重复率 = 0」在真实数据上的口径澄清（2026-10-26）

我第一次把这个指标算成 **24.9%「不达标」，那是我算错了口径**，记录以免再犯：
分块阶段 `chunk-overlap: 200`（`chunk-size: 800`，配置注释写明"建议为 chunk-size 的 15%~25%"）
是**设计意图**，`TextChunker.split` 里 `next = end - overlapSize` 就是它的实现——
按 chunk 文本算相邻重复必然约 25%，它不是缺陷，也不该是 0。

计划 §5 那行"重叠重复率 = 0"的主语是**检索侧合并之后**（§2.2 指出的
`mergeAdjacentChunks` 直接拼接导致上下文里出现约 200 字重复）。用真实 273 chunks（book 24689）复算，
复刻 `RagPipelineComponents.stripOverlap`（`RagPipelineComponents.java:272`）：

| 观测 | 值 |
|---|---|
| 相邻 pair 数 | 272 |
| 按 `endChar/startChar` 算的窗口重叠 | 272/272 pair 有重叠，共 60,111 字，均值 **221 字** |
| `stripOverlap` 实际剥离 | **272/272 pair**，共 60,799 字 |
| 偏移上重叠却没剥掉的 pair | **0** |
| 合并后残余重复（60 字探针查二次出现） | **1 pair / 60 字** |
| 合并后重叠重复率（残余口径） | **0.0001** |

即 §2.5 / §5 的这一项**达标**，且已有单测钉住（`RagPipelineComponentsTest`：
`mergeAdjacentChunksStripsOverlap` + `stripOverlapOnlyRemovesRealOverlap` 等）。
残余的 1 pair 60 字来自 `stripOverlap` 只找"prev 后缀 == next 前缀"的最长单次匹配，
若重叠区在中间还有一段重复就剥不掉——它是 `MAX_OVERLAP_CHARS=400` 单次匹配的设计边界，不是 bug。

**剩下的 §2.5 项**（未做）：抽样 100 chunk/格式的边界质量与起始质量、`--runs 3`、多格式（EPUB/PDF）抽样。


---

## 6. 明确不做

- ❌ 不重做分块（800/200）与向量模型：与当前问题相关性低，重向量化成本高；先补元数据。
- ❌ 不做通用 Agent 循环 / 工具调用路由：图书问答场景不需要，增加不可控性。
- ❌ 不做微调：数据量和评测基础都不够，先做提示词分层与引用。
- ❌ 不在没有黄金集的情况下调整 prompt：无法判断"变好"还是"变长"。
- ❌ 不把 LLM-judge 单次分数当唯一门禁：先人工校准。
- ❌ 不一次性改前端协议：引用 UI 可后置，快照先服务后台与导出。

---

## 7. 可行性核验（二次验证）

### 7.1 核验方式与基线

| 检查 | 结果 |
|---|---|
| `mvn -q -DskipTests compile` | 通过 |
| `mvn -q test` | **544 用例，0 failure / 0 error / 0 skip** |
| Flyway | 已启用，V1~V4 存在，新增 V5 可行 |
| ES | 仅 `BookSearchRepository extends ElasticsearchRepository<BookDocument, Long>`，**无 chunk 索引** |
| 本地推理 | 无 ONNX/DJL 依赖；全仓无 rerank 客户端/模型配置 |
| CI | 仓库内**无 workflow 配置** |
| 调用面 | GitNexus MCP 当前未连接，改用 grep 枚举；正式改造前仍应按 R1 跑一次 impact |

### 7.2 方案可行性矩阵

| 方案 | 结论 | 关键依据 / 约束 | 需要的调整 |
|---|---|---|---|
| 3.1 提示词分层 | **可行** | 外部配置优先级在 `getSystemPromptForStyle`；热更新入口存在 | 改为**单条合并 SystemMessage**，避免多 system 兼容性风险 |
| 3.2 拒答分支 | **可行** | `ragContext=null` 分支清晰；SSE 协议可复用 | 必须联动 `0.2→0` 降级；补齐 `done/complete/落库`；接受一次多余 follow-up 调用 |
| 3.3 检索快照 | **可行** | record 仅 6 个构造点；`truncateToChars` 与圆桌共用 | 新增 `selectWithinBudget`，不改旧函数；`prefetchAll` 返回类型需扩展；Flyway V5 |
| 3.4 章节元数据 | **条件可行** | TOC 只有标题、无偏移；`splitContent` 不返回偏移；两条 metadata 重建路径遗漏字段 | 先做 20 本离线校验；<90% 一律置 null；补 `doFilterSearch` / `searchContentByChunkIndex` |
| 3.5 引用契约 | **可行** | `replace` 事件已有；MarkdownRenderer 可扩展 | 流式期间标签会短暂可见；不阻断主流程，先统计 |
| 3.6 regenerate / 压缩 | **可行** | Controller 参数被注释、前端重复追加用户消息；压缩逻辑集中 | 前后端都要改；压缩跳过最近一轮 assistant |
| 3.7 列表排序/截断 | **可行** | `refineByLlm` 目前丢 LLM 顺序；`toMatches` 按 index 排 | 保序 + rankScore；或复用 `truncateByQuota` |
| 3.8 LLM rerank | **可行，成本待测** | TOOL 场景回退链存在 | 先 20 题压测；默认开关可配置 |
| 3.8 cross-encoder | **暂不可行** | 无推理服务/依赖 | 移出本计划 |
| 3.8 ES 混合召回 | **不在本计划** | ES 只有书籍元数据 | 单独立项（chunk 索引 + 双写一致性 + RRF） |
| 3.8 查询扩展收敛 | **可行** | 当前串行、无上限 | `embedAll` + 并行；上限 5~6 |
| 3.8 检测第二信号 | **可行** | `hasEnumerativeStructure` 已存在 | 改可见性；必须用原始回答 |
| 4.1 黄金集 | **可行（人力项）** | 无代码阻塞 | 需 2~3 天标注 |
| 4.3 检索回放 | **条件可行** | 依赖 Qdrant，现有单测无外部依赖 | 先独立 runner，不进 `mvn test` |
| 4.4 CI 门禁 | **当前不可行** | 无 workflow | 先补 CI，再挂确定性用例 |
| prompt_version | **可行** | `Target` + 2 个 wrapper + entity + migration | MDC 加键，异步写入 |
| `RAG_RERANK` 场景 | **可行** | `Category.TOOL` 有回退 | 加枚举 + 绑定配置 |
| 流水线缺材料占位 | **可行** | 单节循环已有失败占位路径 | 加 `material.isBlank()` 分支，不调模型 |
| 绝对阈值 0.35 | **需数据** | 现有统计只有 0.3/0.6 分桶 | 先攒快照/日志，再标定 |

### 7.3 实施前需确认的外部条件

1. 选定 3 本书并安排标注人力（黄金集是阶段 1 验收的前提）。
2. TOOL 模型的上下文长度与 P95 吞吐（决定 LLM rerank 是否默认开启）。
3. 生产发布窗口是否允许 Flyway V5 + 新表（快照表）。
4. 已索引书的 payload 补字段方式：Qdrant `setPayload` 是否可用；不可用则重建该书向量。
5. 是否有可用的评测环境（独立数据库/Qdrant，避免污染生产收藏与日志）。

### 7.4 工期修正

- 阶段 1~4：按 §5 原估算，可行。
- 阶段 5：从"rerank + 混合召回 5~7 天"下调为"LLM rerank 试验 3~4 天"；混合召回另立计划。
- 阶段 6：黄金集与快照可并行；CI 门禁需额外 1~2 天搭基础设施，否则先落"本地一条命令"。

### 7.5 核验结论

**阶段 1~4 可按原计划推进；阶段 5 降级为 LLM rerank 试验；混合召回与 CI 基础设施不阻塞主线，单独排期。** 原文档中"cross-encoder 首选"与"检索回放可直接进 CI"两处已按核验结果修正。

> 关联：`book-qa-iterative-generation-plan.md`（列表多段流水线）的二次核验见其 §10；入库净化、章节结构与分块/截断见 `rag-ingestion-and-chunking-plan.md`。本文的检索快照与引用方案可直接作为该流水线的逐轮轨迹与忠实度验收数据源；三份文档的 P0 项有交集（提示词事实边界、maxOutputTokens、章节元数据），实施时应合并排期。

---

## 8. 附录：关键证据索引

| 主题 | 位置 |
|---|---|
| 入口 / 参数 | `BookChatController.java:46-82`（`regenerate` 注释于 `:56`） |
| 前端发送 | `BookChatSheet.tsx:247,282,473`；`sse-request.ts:330` |
| 主流程 | `BookChatService.java:145-349` |
| 消息组装 | `BookChatService.java:369-421` |
| RAG 上下文包装 | `BookChatService.java:583-595,611-620` |
| 常规检索 | `BookChatRagService.java:277-430` |
| 列表检索 | `BookChatRagService.java:192-267`；`ListQueryRetriever.java:87-266,341-420,480-492,519-531` |
| 排序组件 | `RagPipelineComponents.java:117-176,208-259,328-363` |
| 流水线 | `BookChatPipelineService.java:143-217,225-405,410-446,477-533,600-649` |
| 逐条检索 | `ItemListRetriever.java:64-177` |
| 会话/压缩 | `BookChatSessionService.java:161-174,221-241,291-307,330-418` |
| 提示词 | `BookChatPrompts.java:17-56,87`；`RagPrompts.java:16-46`；`ai-config.json` → `bookChat.styles[].prompt` |
| 提示词生效链 | `BookChatSessionService.java:291-307` → `AiConfigProvider.java:76-94` |
| 分块与元数据 | `EmbeddingIndexService.java:165-176,798-847` |
| 检索阈值/降级 | `EmbeddingSearchService.java:232-288,302-344` |
| 数据模型 | `AiConversation.java:22-111` |
| 既有测试 | `ListQueryDetectorTest`、`AnswerOutlineServiceTest`、`ThoughtTagParserTest` |
| 既有计划 | `docs/plans/book-qa-iterative-generation-plan.md`（Q7 已确认主链路不接 `RagAnswerCache`） |

---

## 9. 一句话总结

**先把"不知道就说不知道"变成代码分支，再把"依据哪几段原文"变成数据库字段，最后才是让排序更准。** 顺序反了，任何优化都无法证明有效。
