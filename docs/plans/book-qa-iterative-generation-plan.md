# 图书问答：多次检索 + 多段流内生成 改造计划

> 状态：**v2.4 · 优化实施（2026-10-26）**——§10.7 列出的分段约束、prefetch、长列表、逐条依据、分段引用、首轮列表题已落地；后端 544 用例全绿。§7 运行时验收已用真实书 + 真实模型部分执行（见「运行时实测记录」），仍缺长列表 / regenerate / 追问轮 / 黄金集回归。
> 范围：`BookChatService` 图书问答链路（SSE 流式 + RAG）
> 触发问题：一次问答 → 多次搜索 → 一次回答，导致"把 5 条内容讲详细些"只答了前 3 条
>
> **修订记录**
> - v2.3（优化实施）：§10.7 的分段约束、prefetch 逐条就绪、长列表 20 条上限、逐条依据、全局引用、首轮列表题已落地；后端 544 用例全绿。
> - v2.2（二次核验）：§10 追加代码对照与评测指南差距；确认 6 处实现偏差 + 1 处计划取舍，阶段 A~E 重新排序。
> - v2.1（代码完成）：§4.4/§4.5/§4.6/§4.7 全部实现；§4.2 SSE 超时 3.6e6 + `maxOutputTokens` 可选输出上限已接线；
>   §3.1④ 二级条目化已实现；Q7 陈旧注释已订正。
> - v2：采纳"流内多段流水线"架构——后端多次检索 + 逐段生成，各段以现有 `message` 事件连续推流；
>   **前端协议零改动**。砍掉 v1 的"双分支生成 + 覆盖校验 + 补写"与"outline SSE 事件 + 前端 chip"。
> - v1：初稿（一次连贯生成 + 校验补写 与 逐项 map-reduce 双分支）。

---

## 0. 结论先行

当前"一问 → 检索 → 一答"的骨架**不是这个 bug 的根因**。真正的原因是三件事叠加：

| # | 主因 | 强度 | 一句话说明 |
|---|---|---|---|
| H1 | **输出 token 上限从未被设置** | 强 | `ChatModelFactory` 所有 builder 不设 `maxTokens`，吃 provider 默认（多为 4096）。而 `BOOK_QA` 场景 `thinking=true`，推理 token 计入输出预算，**可见回答实际能写的字数远小于 4096**。5 条 × 详细必然溢出，且系统完全不知道被腰斩（全仓库 `finishReason` 0 命中）。 |
| H2 | **风格提示词在主动反对列 5 条** | 强（确定） | DEEP 锚点写"一句话结论 → **2~3 条**书内证据 → 一句升华"；CASUAL 写"**不要列 12345**"。用户要 5 条详细，system prompt 说 2~3 条，模型听 system 的。 |
| H4 | **检索按全局 score 排序，天然把材料堆在最相似的一处** | 强 | `topN = 按 score 降序 limit 10`。5 条内容在书里语义相距很远，一次"主题级"检索只把 10 个坑位给了最相近的那一片区域，**后两条的原著片段根本没进上下文**。 |
| H3 | 追问时清单在跨轮传递中被降级为散文 | 中 | `ListQueryDetector` 只截上轮回答 **500 字符**，且 `hasListSignal` 硬编码了某本书的词。清单靠模型"记得"，而不是结构化数据。 |
| H5 | `truncateToChars` 用 `break` 而非逐条配额 | 低-中 | 仅当 merged 总量超过 ragMaxChars 时才咬人。 |

**关键修正（支持"不是靠搜索数据量决定"的直觉）**：
`ragMaxChars = maxTokens(32768) × 1.5 × 0.6 ≈ 29491` 字符，而常规路径典型只填充 8~15K 字符。
**检索预算非常宽裕，从来没塞满过。** 丢条目发生在**生成侧（H1/H2）**和**检索覆盖的分布侧（H4）**，不在检索量侧。

**v2 架构判断**：
把"逐条检索"与"逐条生成"都做出来，但**生成结果不另开对话轮、不加新前端协议**——
各段按序写入同一条 SSE 流的 `message` 事件，前端看到的仍是"一次回答、连续打字流"。
这同时消灭了 v1 方案里最重的两块：覆盖校验/补写（循环天然覆盖）与输出腰斩止血
（单节 300~500 字，默认上限打不到；截断只需续写该节）。

---

## 1. 现状链路全景（含代码定位）

```
BookChatController.streamBookChat  (controller/BookChatController.java:47)
  └ withSseLimit(userId, …)                                    :81
     └ BookChatService.streamBookChat                            :247
        ├ new SseEmitter(180_000L)                               :255  ← 单请求 180s
        ├ emitter.send("session_id")                             :258
        ├ getBookById / getStreamingModelForScene(BOOK_QA)       :263-273
        └ sseExecutor.submit(() -> {
             ├ getLastAiAnswer(userId, sessionId)                :283 / :551-564
             │    └ 取的是 compressedContent，不是原文            :557
             ├ ensureContentEmbedded(book…)                      :286 → :1285（最长阻塞 1h）
             ├ ensureCompressedSummary(book, emitter)            :289
             ├ ragTopK ← 场景配置 ?? qdrant.ragTopK(默认10)       :291-304
             ├ ragMaxChars = getRagMaxChars() ≈ 29491            :305 → :926-939
             ├ doRagRetrieval(book, question, lastAiAnswer,…)    :309 → :1132-1278
             │   ├ tryListQueryRagRetrieval                      :1141 → :955-1022
             │   │   ├ ListQueryDetector.detect(question, lastAiAnswer)
             │   │   │     hasListSignal 硬编码词表               ListQueryDetector.java:133-144
             │   │   │     truncate(lastAiAnswer, 500)            :150
             │   │   ├ ListQueryStrategySelector.selectStrategy(book)
             │   │   └ ListQueryRetriever.{fullScan,tocRange,clusterExpand}
             │   │         MAX_FULL_SCAN=500 / BATCH=15 / EXPAND=50 / TOP_N=15
             │   │                                                 ListQueryRetriever.java:39-45
             │   ├ expandQuery → 最多 9 个子查询                   :1147 → :1405-1449
             │   │     extractKeywords 把 >15 字问题压成 ≤15 字    :1496-1515
             │   ├ 每子查询 searchContent(perQueryLimit=20)        :1165-1185
             │   ├ 按 chunkIndex 去重                              :1179
             │   ├ ★ 全局 score 降序 → limit ragTopK(10)           :1224-1226  ← H4
             │   ├ 软阈值 score < max×0.3 过滤                     :1228-1241
             │   ├ adaptiveNeighborExpand(prev1,next2)             :1244-1246
             │   ├ mergeAdjacentChunks + 按 bestScore 重排          :1249-1251
             │   └ truncateToChars(merged, ragMaxChars)  ★break    :1256 → RagPipelineComponents.java:328-339  ← H5
             ├ buildBookInfoPrompt(book)  静态前缀（KV Cache）     :324 → :605-653
             ├ buildRagMessage(ragContext)                        :326 → :664-676
             ├ buildQuestionMessage(question)  仅"请用中文回答"     :327 → :687-689  ← H2 无覆盖契约
             ├ buildChatMessages(...)                              :334 → :490-542
             │     System(风格提示词) → User(bookInfo) → History → User(RAG) → User(画像) → User(问题)
             │     历史用 compressedContent                        :512
             │     currentOverhead 给 AI 回复只预留 +2000 字符      :508  ← 预算倾斜给检索
             └ StreamingSseHandler.stream(model, msgs, emitter, cb, 2, logCtx)  :338
                   ├ onPartialThinking → "thinking_content"        StreamingSseHandler.java:182
                   ├ onPartialResponse → ThoughtTagParser → "message" :201
                   ├ onCompleteResponse → callback.onComplete      :237   ★从不读 finishReason ← H1
                   └ cb.onComplete → sanitize → saveMessage(assistant) :351-368
        emitter.onTimeout → aiFuture.cancel(true)                  :402-405  ← 超时=腰斩
```

**模型构建侧的关键事实**（`config/ChatModelFactory.java`）：

- `buildOpenAiChat` :441-475 / `buildOpenAiStreaming` :477-505 / `buildOllamaChat` :410-424 / `buildOllamaStreaming` :426-439
  → 四个 builder **全部只设 `temperature` + `timeout` + 思考参数，没有任何地方设输出上限**。
- `entity/AiProviderConfig.java:97` 的 `maxTokens` 语义是**模型上下文长度**（注释与 `getRagMaxChars()`/`loadAndCompressHistory()` 的用法都印证），**不能**直接当输出上限用。
- `entity/AiScene.java:23` `BOOK_QA("图书问答", Category.QA, true, true)` → **thinking=true**。
  思考 token 挤占输出额度，是单次长回答腰斩（H1）的放大器；v2 流水线里每节调用应走**无思考**路径（见 §3.3）。
- `service/ai/streaming/StreamingSseHandler.java:192` 已有告警"检测到模型思考 token（首个）"——思考链路确实在跑。

---

## 2. 为什么"一次回答"会丢条目：机制拆解

### 2.1 腰斩是静默的
全仓库 `grep finishReason` → **0 命中**。`onCompleteResponse` 只把 `fullResponse` 当"完整答案"落库。
模型被 `LENGTH` 截断时，前端看到"答案自然结束在第 3 条中间"，DB 存的也是残缺文本。

### 2.2 提示词与用户意图直接冲突
```
用户：把这 5 条分别详细说说
DEEP：每一轮回答遵循三段式：一句话结论 → 2~3 条书内证据 → 一句升华
CASUAL：不要写论文，不要列 12345
```
提示词在 `constants/AiPromptConstants.java:222-236` 和 **`deploy/ai-config.json` 的 `bookChat.styles[].prompt`**
两处都有；`getSystemPromptForStyle()`（`BookChatService.java:794-810`）**优先读外部配置**。改时两处必须同步 + `POST /api/admin/ai-config/reload`。

### 2.3 检索覆盖的"集中化"是结构性的
```java
// BookChatService.java:1224-1226
List<EmbeddingMatch<TextSegment>> topN = sortedByScore.stream()
        .limit(ragTopK)   // 默认 10
```
追问"这些内容再详细一些"经 `extractKeywords` 压成 ≤15 字后**没有主题信息量** → 9 个子查询语义几乎相同 →
10 个坑位全落在书里最相近的一片 → 后两条对应章节的片段**根本没被召回**。

`ListQueryRetriever` 三档策略本是为此设计，但只在 `hasListSignal` 命中时启动，而它（`ListQueryDetector.java:133-144`）
硬编码了 `"11项" "14种" "项能力" "种方法" "类技巧"`——**某本具体书的书名特例泄漏进了通用路径**。
换一本书就漏判 → 退回常规 RAG → 撞上 2.3 的集中化。

### 2.4 清单跨轮传递是易碎的
- `getLastAiAnswer` 取 `compressedContent`（:557）；长会话中批量压缩提示词（`ChatHistoryCompressor.java:236-251`）
  **没有"枚举项必须 1:1 保留"的约束**，"5 条主要内容"易被压成"A、B、C"。
- 即使没压缩，清单也只是散文；`ListQueryDetector` 只喂 500 字（:150），第 4、5 条在检测阶段就被切掉。

### 2.5 预算倾斜（次要）
`currentOverhead` 里给"AI 回复"只预留 2000 字符（:508），RAG 独享 2.9 万。方向上"重检索轻生成"，需纠正但不致命。

---

## 3. 目标形态（v2：流内多段流水线）

### 3.1 总体流程

```
┌─ 第 1 轮：「这本书讲了什么」 —— 完全走现有单次流式路径，不改
│   ① 主模型流式生成（现状不变）
│   ② 流式结束后【异步】：TOOL 小模型把答案条目化 → outline
│      [{id, title, gist, keywords[], chunkAnchors[]}] → 写 AiConversation.answer_outline
│      （纯服务端状态，不发 SSE、不进前端协议、不参与历史压缩）
│   ③ outline 的 chunkAnchors 由条目化模型对参考内容做 chunkIndex 对齐（有 RAG 上下文时），
│      无法对齐时留空，交给第 2 轮的 keywords 向量检索兜底
│
└─ 第 2 轮：「把这 5 条详细说说」 —— 命中"列表展开"条件时进入流水线
    ① 取 outline：DB answer_outline；无（旧会话/异步未落库）→ 现场对 lastAiAnswer 条目化
    ② 【并行 prefetch】对 N 条各自独立检索：
         锚点 range（searchByChunkIndexRange）→ 命中即用；
         锚点缺失 → keywords 向量检索（topN=4，阈值放宽）
         每条独立配额 ≤ 5000 字符
       —— 各条互不依赖，全部提前并发跑完，延迟 ≈ 一次检索的往返
    ③ 【串行生成】for i in 1..N（严格按序）:
         组装该节消息（见 §3.3）
         调流式模型 → 增量以现有 "message" 事件推给前端（不 done、不 complete）
         该节完成后才启动下一节（衔接需要前文）
    ④ 全部 N 节推完：
         聚合完整答案 → sanitize → saveMessage（一行 assistant）
         → 再异步条目化一次（第 2 轮的 outline，支持"第 3 条再详细点"三级钻取）
         → 发 done → emitter.complete()
```

**对前端**：从头到尾就是一条打字流，事件只有 `message` / `thinking_content`（复用现有，做进度提示）/ `done` /
`error` / `replace`（泄露兜底，前端已支持，`sse-request.ts:331`）。**`sse-request.ts`、`bookChat.ts`、
`BookChatSheet.tsx`、`types/ai.ts` 均零改动。**

### 3.2 为什么这个形态同时消灭了 v1 的两块复杂度

| v1 的机制 | v2 里去哪了 | 原因 |
|---|---|---|
| 覆盖校验 + 缺失定点补写 | **删除** | 循环由 Java 驱动，N 是已知常量，每条必然生成——"漏条"不再可能发生 |
| 一次连贯 vs 逐项的双分支 | **删除** | 统一逐节；连贯性靠 §3.3 的衔接上下文达成 |
| maxOutputTokens + 整篇续写 | **降级为可选** | 每节只写 300~500 字，4096 默认上限打不到；截断只需**该节**续写一轮，爆炸半径从整篇缩到单节 |
| outline SSE 事件 + 前端 chip | **删除**（chip 留作远期 P3） | 追问"第 3 条再详细点"用自然语言即可，流水线第 ① 步能从 outline 精确定位 |

### 3.3 每节消息组装与衔接（语感的关键）

割裂感来自"5 段各写各的"。解法是把**全篇结构 + 前文尾巴**放进每节 prompt：

```
SystemMessage   = 风格提示词（含【列表展开豁免】句，§4.3）
UserMessage     = bookInfoPrompt（静态前缀，跨节共享 KV Cache）
UserMessage     = 历史消息（现状不变，走 loadAndCompressHistory）
UserMessage     = 【全篇任务】读者要求逐条展开以下 5 条：1)…2)…3)…4)…5)…
                  （只有标题 + gist，让模型始终知道自己在写全篇的第几节）
UserMessage     = 【前文】第 1 节《…》已输出，结尾句："……"
                  （第 2..N 节才有；第 1 节没有此项）
UserMessage     = 【本节参考资料】第 i 条的 prefetch 检索结果（≤5K 字符，带【条目i：标题】标记）
UserMessage     = 【本节任务】写第 i 节，标题「{title}」，300~500 字。
                  第 1 节：以一句全篇开场引出（不要客套）；
                  第 i>1 节：开头用一句话自然承接上一节（"再看{上一节标题}……"式，忌"其次/然后"）；
                  第 N 节：结尾加一两句全篇收束。
                  铁律不变：事实以本节参考资料为准，参考资料未涉及就明说，不编造。
```

要点：
- **每节生成前只带"上一节的标题 + 结尾 1~2 句"**，不带全文——衔接够用，token 不膨胀。
- 节与节的输出直接首尾相接推流，每节开头带 markdown 标题（`### 一、…`），拼接即成文。
- 每节走**无思考**模型（`QA_WITHOUT_THINKING` 分类或场景单独绑定）：N 节 × 思考 token 成本不可接受，
  且"按材料写一节"不需要深推理。`AiScene.Category.QA_WITHOUT_THINKING` 已存在（`AiScene.java:104`），
  **场景绑定是管理后台配置改动，零代码**。
- CONCISE 风格用户：每节预算降为 100~200 字（契约里带风格约束即可，与 200 字天花板冲突时按条数×每节短预算处理）。

### 3.4 触发条件与回退

进入流水线的条件（全部满足）：
1. `ListQueryDetector.detect` 判定列表展开型（`hasListSignal` 先去硬编码，§4.3-3）；
2. outline 可得（DB 或现场条目化），且 `items.size() ≥ 2`；
3. `items.size() ≤ MAX_PIPELINE_ITEMS`（默认 8；超过则每节预算降为 200~300 字，N>12 罕见，兜底分组）。

任何一条不满足 → **完全退回现有单次流式路径**（含 `ListQueryRetriever` 三档检索）。
即流水线是"增强分支"，不是替换主干；检测漏判的最坏结果 = 现状。

---

## 4. 改造项清单

### 4.1 【P0·观测】诊断埋点（先确认主因，纯加日志）

1. `StreamingSseHandler.onCompleteResponse`（:237）读取 `completeResponse.finishReason()`：
   `LENGTH` 打 WARN（附 outputTokens、已输出字符数），其他值 INFO 记录。
2. `Callback` 新增 **default 方法** `onFinishReason(String)`（不动现有签名，5 处实现不受影响）。
3. RAG 组装后结构化日志：`chunksIn / charsIn / 填充率 / 命中 chunkIndex 分布（min/max/跨度）`
   ——跨度小 = H4 集中化的直接证据。
4. `maxOutputTokens` 为空时打"输出上限=provider默认"日志，避免再次静默。

**验收**：复现"5 条详细"追问，日志能明确区分 (a) LENGTH 中止 / (b) chunkIndex 分布集中 / (c) 两者皆无。

### 4.2 【P0·止血】（对现状路径的保护，与流水线并行存在）

| 项 | 文件:行 | 改动 |
|---|---|---|
| SSE 超时 | `BookChatService.java:255` | `180_000L → 3_600_000L`，对齐 `application.yml` `spring.async.request-timeout: 3600000`。流水线 N 节串行生成，180s 必被腰斩，**此项在 v2 是硬前提**。 |
| 输出上限字段（可选） | `entity/AiProviderConfig.java`（:97 后） | 新增 `maxOutputTokens`，`ChatModelFactory` 四个 builder 在 `!= null` 时设上限（Ollama `numPredict`）。⚠️ :459 的 Qwen3 `enable_thinking` 分支已占用 `customParameters(Map)`，与 `thinking_budget` 透传互斥覆盖，需合并赋值。字段为空完全等价现状（灰度安全）。 |
| 单节截断续写 | `StreamingSseHandler`（新方法内实现，§4.4） | 某节 `LENGTH` → 该节以已输出为前缀续写一轮（最多 2 次），只影响该节。 |

### 4.3 【P0·止血】提示词与检测器修正（主干路径与流水线共用）

1. **`hasListSignal` 去硬编码**（`ListQueryDetector.java:133-144`）：
   删除 `"11项" "14种" "项能力" "种方法" "类技巧"` 书名特例；改通用信号集：
   `\d+\s*[项种类条个]` 模式、`第N`、`分别/逐一/逐条/展开/详细/具体/一个个/都讲讲`、`这些/那几个/上面/刚才` 指代。
   **这是当前唯一确定是 bug 的一项。**
2. **风格提示词加豁免句**（两处同步：`AiPromptConstants.java:201-236` + `deploy/ai-config.json` `bookChat.styles[]`）：
   ```
   【列表展开豁免】当读者明确要求逐条展开 N 项内容时：忽略"2~3 条证据"三段式与"不要列 12345"，
   按条目逐节输出。
   ```
3. **非流水线回退路径的问题消息加契约**（`buildQuestionMessage` :687-689）：
   命中列表型但走回退（outline 不可得等）时，问题消息末尾列出条目名 + "必须输出 N 个小节，禁止合并省略"。
4. **压缩提示词加枚举保护**（`ChatHistoryCompressor.buildBatchSystemPrompt` :236-251）：
   "原文若为编号列表，条目数量与标题必须原样保留，不得合并省略"（防御旧会话）。
5. `ListQueryDetector` 传上下文不再截 500 字（:150）：有 outline 时直接传标题列表；无则放宽到 2000。

### 4.4 【P1·核心】StreamingSseHandler 多段流式支持

现状：`stream()` 是"一次调用 → 推流 → onComplete 落库 → done → complete()"的原子操作
（`StreamingSseHandler.java:152-330`），且 langchain4j 的 streaming `chat()` 是**非阻塞回调式**——
现有代码靠 `while + errorRef` 处理重试段内同步，但 N 节之间需要"**当前节完成后再启动下一节**"。

新增（不改现有方法与 Callback 签名）：

```java
/** 单段流式：推 message/thinking_content，不发 done、不 complete、不调 onComplete。
 *  阻塞至该段完成（onCompleteResponse/onError/连接断开/中断），返回本段结果。 */
public static SegmentResult streamSegment(
        StreamingChatModel model, List<ChatMessage> messages, SseEmitter emitter,
        Callback callback, AiCallLogContext logContext, long segmentTimeoutMs);

record SegmentResult(String text, String finishReason, boolean connectionClosed) {}
```

实现要点：
- 内部 `CompletableFuture<String>` 桥接回调 → 同步返回；带超时。
- 每段仍走 `ThoughtTagParser`（`<thought>` 标签分离）与 `safeSendEvent`（false → 置 `connectionClosed`，供外层中断流水线）。
- 每段开始前检查 `Thread.currentThread().isInterrupted()`——`emitter.onTimeout → aiFuture.cancel(true)`
  必须能打断**节与节之间的等待**，并把当前段 `streamingHandle.cancel()`。
- 日志按段各打一条摘要（复用 `logAiStreamingSummary`）。

`BookChatService` 侧新增私有方法 `streamItemListPipeline(...)`：prefetch → 循环调 `streamSegment`
→ 聚合 → `sanitizeAiOutput`（若与拼接原文不同 → 发 `replace` 事件，前端已支持覆盖显示）→
`saveMessage(assistant, 聚合全文)` → done/complete。**一行 assistant 消息，历史/导出/压缩全兼容。**
中途断开 → 已推部分聚合落库（对齐现有 `onConnectionClosed` 语义，:375-385）。

### 4.5 【P1·结构】outline 清单对象化（纯服务端）

**数据**：`entity/AiConversation.java`（:79 `followUpQuestions` 后）新增：

```java
/** 回答的结构化条目清单（JSON）。供后续"逐条展开"追问做定向检索与串行生成的任务骨架。
 *  服务端内部状态：不下发前端、不参与历史压缩。 */
@Column(name = "answer_outline", columnDefinition = "TEXT")
private String answerOutline;

/** 最近一次流式结束原因 STOP/LENGTH/…（诊断依据） */
@Column(name = "finish_reason", length = 20)
private String finishReason;
```

`ddl-auto: update` 自动加列。`getBookChatHistory` 返回整个实体（controller 直接 `Result.ok(list)`），
新字段会出现在响应 JSON 里——**前端不读就不影响**，无需同步 TS interface（R2 核查过消费点：
`BookChatSheet.tsx:170-180` 按字段名取值，多余字段无害）。

**outline schema**：

```json
{ "topic": "本书主要内容",
  "items": [ { "id": 1, "title": "应变能力培养", "gist": "一句话摘要",
               "keywords": ["应变能力", "突发状况"], "chunkAnchors": [12, 13] } ] }
```

**生成**：新增 `AiScene.ANSWER_OUTLINE("回答条目化", Category.TOOL, false, false)`
（`AiScene.java` RAG/检索域内加一行；管理后台自动渲染）。
`BookChatService.saveMessage(assistant)` 之后，若回答为概述/列表型 → **异步**（新 `qaOutlineExecutor`，
与 sseExecutor 隔离）调 TOOL 模型条目化 → 写回本条 `answerOutline`。失败不影响主流程。

**并发时序陷阱（必须处理）**：异步 outline 未落库时用户已发起追问 →
第 2 轮取不到 → **现场条目化**（同步 TOOL 调用，输入 = lastAiAnswer，~1s，可接受）。
两级获取：DB 优先，缺失即现场生成；现场结果也写回 DB（回填）。

### 4.6 【P1·核心】逐条定向检索 `ItemListRetriever`

放 `service/ai/`，与 `ListQueryRetriever` 并列（后者保留给非流水线回退路径）：

```
输入：outline.items[], book
对每个 item 独立（并行执行，走 prefetchExecutor）：
  1. 锚点优先：chunkAnchors 非空 → searchByChunkIndexRange(bookId, min-1, max+2)
       （EmbeddingService 已有 :2053 scrollByChunkIndexRange、:806 searchContentByChunkIndex）
  2. 锚点空/无结果 → item.keywords 向量检索（topN=4，阈值放宽到常规值 ×0.6）
  3. 邻域扩展沿用 RagPipelineComponents.adaptiveNeighborExpand（前1后2）
  4. 该条独立配额 ≤ 5000 字符（逐条配额，不与其他条争全局坑位——这是治 H4 的关键）
输出：Map<itemId, String>，供 §3.3 每节消息组装
```

**为何逐条配额**：全局 score 排序正是 H4 病根；逐条配额使"每条都有材料"从概率变成代码不变量。
检索是纯向量查询无 LLM 调用，5~8 条并发成本可忽略。

`truncateToChars` 的 `break`（`RagPipelineComponents.java:334`）：
新增 `truncateByQuota(List, maxChars, perItemCap)` 供 `ItemListRetriever` 使用；
旧 `truncateToChars`（`BookChatService:1256` + `RoundTableService:2459` 共用，**R3**）不动，
`break→continue` 修复作为独立小改动另行评估 + 圆桌派回归。

### 4.7 线程池配置

- 新增 `qaPrefetchExecutor`（检索并行，~8 线程）与 `qaOutlineExecutor`（异步条目化，2 线程），
  `config/` 线程池配置类中声明；与 `sseExecutor` 隔离——`waitForContentEmbedding` 里有 `Thread.sleep`（:584），
  池隔离防止占满导致问答整体排队。
- 流水线主循环仍在原 `sseExecutor` 任务内（保持取消语义），仅 prefetch/outline 外派。

---

## 5. 遗留的开放问题

| 编号 | 问题 | 我的倾向 |
|---|---|---|
| Q1 | 每节预算 300~500 字是否随风格变 | 是。DEEP 400~500、CASUAL 250~350（口语段落）、CONCISE 100~200、WITTY 300~400。写进契约文本，不加新配置项。 |
| Q2 | 第 1 轮（概述）是否也流水线 | **否**。概述本来就要一次成形（它只是列标题），outline 异步条目化已覆盖需求。改动面最小。 |
| Q3 | `MAX_PIPELINE_ITEMS` | 8。超过则每节降预算；>12 分组为"先答前 8 + 末尾列出剩余条目提示再问"。 |
| Q4 | 旧会话兼容 | outline 取不到 → 现场条目化；再失败（答案不像列表）→ 回退现有路径。行为不比现在差。 |
| Q5 | 节间失败策略 | 单节模型报错 → 重试 1 次 → 仍失败则该节输出"这一节暂时没法展开，你可以单独再问我"并继续循环。**不整轮失败。** |
| Q6 | `thinking` 在 BOOK_QA 的去留 | 主干问答保留现状；**流水线每节走无思考配置**（场景绑定层面解决，零代码）。 |
| Q7 | `RagAnswerCache` | 主链路本来就没接它（仅 Admin/Parser 失效），流水线也不接；订正 `:76` 陈旧注释。 |

---

## 6. 影响面与回归点（R1/R2/R3）

| 改动符号 | 上游引用 | 风险 | 缓解 |
|---|---|---|---|
| `StreamingSseHandler` | `BookChatService` / `AiChatService` / `DebateService` / `RoundTableService` / `ChatModelManager`（5 处） | **高（R3）** | 只**新增** `streamSegment` + Callback 的 default 方法；现有 `stream()` 一行不动 |
| `ChatModelFactory.build*` | 全站所有 LLM 调用 | **高** | `maxOutputTokens` 为空 = 完全等价现状；`customParameters` 合并赋值防覆盖 :459 分支 |
| `AiConversation` 新列 | `saveMessage`、history 接口（整实体下发） | 中 | 前端按键取值，多余字段无害；导出用 `getContent()` 不受影响 |
| `AiPromptConstants.BOOK_CHAT_STYLE_*` | 4 风格系统词 | 低 | **必须与 `deploy/ai-config.json` 同步** + reload 接口生效 |
| `ListQueryDetector.hasListSignal` | `tryListQueryRagRetrieval` | 低 | 命中变多 → 更多问题进列表分支/流水线，注意日志观察检索开销 |
| `RagPipelineComponents.truncateToChars` | `RoundTableService:2459` | 中 | 不动旧函数，新增 `truncateByQuota` |
| `BookChatService.streamBookChat` 主流程 | 前端 `BookChatSheet.tsx` | 中 | **协议零改动**（只多用了既有事件）；主干路径行为不变，仅新增分支 |
| 新增 `AiScene.ANSWER_OUTLINE` | `AiSceneConfigService.resolveConfig` 回退链 | 低 | TOOL 分类回退已有兜底 |

**自检（R4）**：`mvn compile` + `npm run build` 双零错；追问后刷新页面 outline/聚合答案仍在（A1）；
流水线循环计数用 `for (int i…)` 无闭包 state（A2）；前端未改一行但 `md:` 断点回归看一眼（本次不涉及 UI，跳过）。

---

## 7. 分阶段落地与验收

### 阶段 0：诊断（1 天）
§4.1 全部。
**验收**：复现"5 条详细"，日志判定主因占比（LENGTH / chunkIndex 跨度 / 皆无）。

### 阶段 1：止血（2~3 天）
§4.2（SSE 超时 + 可选输出上限）+ §4.3（去硬编码 / 豁免句 / 契约 / 压缩保护）。
**验收**：不改架构的前提下，换任意一本书的"5 条详细"追问，条目完整率显著提升；
`[ListQuery] 检测到列表型问题` 日志在非特例书上也能出现。

### 阶段 2：流水线（4~6 天）★ 核心
§4.4 `streamSegment` + §4.5 outline 落库（含现场回退）+ §4.6 `ItemListRetriever` + §4.7 线程池
+ `BookChatService.streamItemListPipeline`。
**验收**：
- N∈{2,5,8} 的展开追问：输出节数 = N **恒成立**（循环保证，非校验保证）；
- 前端显示为单条连贯回答，DB 单行 assistant，刷新/导出/继续追问全部正常；
- 首节可见时间 ≈ 旧路径首字时间（prefetch 并行抵消串行开销）；
- 中途断开：部分聚合落库，无重复行、无 done 前的 complete；
- `emitter.onTimeout` 打断后无僵尸节。

### 阶段 3：打磨（2~3 天）
衔接语感微调（前文尾巴长度、开场/收束契约）、CONCISE 预算、Q5 节间失败话术、灰度对比。
**验收**：盲测对比流水线回答 vs 一次生成回答，"拼盘感"评分不劣于旧回答。

### 远期（不在本计划）
条目 chip / 点击展开（需要前端协议时才做）；三级钻取的锚点再对齐。

### 运行时实测记录（2026-10-26）

真实书 24689《穷查理宝典》+ 网关模型（`http://192.168.1.10:1994/v1`，model `agnes`），
问题「这本书分为几个部分？请把所有部分逐条列出来。」。

| 项 | 实测 | 结论 |
|---|---|---|
| TOC_RANGE 范围 scroll | 273 chunks / **38ms** | Qdrant scroll 分页 `setOffset` 修复后正常；**修复前每轮重复取第一页、循环永不退出，请求挂死 >4 分钟** |
| LLM 精筛 | 273 chunks / 19 批：**串行 97.5s → 并行 17.3s**（5.6×） | 分批并行生效；结果顺序与串行版一致（按批次序拼接、批内保持 LLM 选择顺序） |
| 首 token 延迟 | **106.1s → 38.3s** | 精筛并行化是主要贡献；首轮条目化预算 8s → 12s 次之 |
| 首轮流水线 | 触发 `items=2~6`、`alignedAnchors=true`；`sections=6, totalChars=2858, finishReasons=STOP` | 逐条展开链路生效 |
| 聚合落库 | 单行 assistant（909 字符）+ `answer_outline`（items+keywords）+ `citation_json={"valid":[6,1,2],"invalid":0,"included":6}` | 聚合落库与引用契约生效，引用编号全部落在入选片段范围内 |
| 依据快照 | `/evidence` 返回 6 行（chunkIndex / score / hash / preview），预览严格 ≤200 字符 | 快照链路生效（此前 `VARCHAR(200)` 溢出 + rollback-only 两个缺陷已修） |
| 首轮条目化耗时 | 单次 **6.2s / ~11.9s / >12s**（网关波动大） | 12s 预算覆盖多数情况；仍超时则退回单次流式，**回答完整不丢**，只是没有逐条展开 |
| 章节范围推断 | `LLM 未找到章节范围` 偶发（约 1/3） | 触发时自动退化为常规 RAG，不影响回答完整性 |

**未覆盖**：>20 条长列表、regenerate 路径、追问轮（第二轮）流水线、LLM judge、黄金集回归。

### 运行时实测记录（2026-10-26 补充：黄金集首轮 + TOC_RANGE 映射缺陷）

用 `docs/eval/golden-set.real.jsonl`（book 24689，14 例：8 事实 / 3 列表 / 2 不可答 / 1 多轮）跑出来的第一批结果。

**① 黄金集标签本身是对的**（逐条对着 `kbook_content` 原文核验过，不是拿系统检索结果当基准）：

| gold_chunk | 原文首句 | 判定 |
|---|---|---|
| 7 | 「查理思考问题总是从逆向开始」 | ✅ 逆向思考 |
| 9 | 两分钟效应 | ✅ |
| 30 | 「1975年底的收市价是38美元」 | ✅ 伯克希尔股价 |
| 60~62 / 68 / 69 | 多元思维模型 / 坐失良机 / 能力圈 | ✅ 均在第四章（真实 53..82） |
| 83 | 「如果你能保持冷静……——《如果》」+ `第一辑 自然` | ✅ |
| 88 | 「芒格说……我有一条'铁律'……达尔文」 | ✅ 达尔文铁律（第二辑 人类 88..98） |
| 210~211 | 「惩罚超级反应倾向」等 25 条清单 | ✅ |
| 249 | 被剥夺超级反应倾向（第 14 条） | ✅ |

**② TOC_RANGE 的「章节 → chunkIndex」均匀映射是错的**（`ListQueryRetriever.java:528-534`）

代码假设目录里每一行都对应等长的 chunk 区间：`chunksPerChapter = totalChunks / tocLines.size()`。
但 book 24689 的目录共 **22 行**，其中只有 **14 行是真实内容章节**（4 章 + 6 辑 + 3 讲 + 序言），
其余 8 行是**书名行 ×2、扉页 ×2、版权页 ×2、上篇/下篇容器 ×2**——它们不占 chunk，却被当成了章节占位。
再加上真实章节长度极不均匀（第三章 12 段 vs 第四章 30 段 vs 第三讲 70 段），预测窗口整体后移：

| 目录行 | 真实 chunk 范围 | 均匀映射预测窗口 | 结果 |
|---|---|---|---|
| 第一辑 自然 | 83..87 | [106, 130] | ❌ 完全漏掉 |
| 第二辑 人类 | 88..98 | [118, 143] | ❌ 完全漏掉 |
| 第三章 忆念 | 41..52 | [68, 93] | ❌ 漏掉开头 41..67 |
| 第四章 芒格主义 | 53..82 | [81, 105] | ❌ 漏掉开头 53..80 |
| 第一讲 | 149..189 | [217, 242] | ❌ 漏掉开头 149..216 |
| 第三讲 | 202..271 | [242, 267] | ❌ 漏掉开头 202..241 |

**14/14 个真实章节的开头都被跳过（100%）**。直接后果：

- 「芒格私人书单分哪六辑」→ 预测窗口 [93,192] → **第一辑 自然（83..87）在窗口外**，「自然」这一辑取不到；
- 「25 条心理倾向」→ 清单正文在 210..211（第三讲开头）→ 预测窗口 [242,267] → **清单本身取不到**，第一条「惩罚超级反应倾向」直接丢。

> 根因：目录行 ≠ 章节，且没有章节 → chunk 的真实锚点。
> 修法方向（未实施）：**用目录标题在 chunk 正文里做锚点定位**——实测每章标题在 chunk 原文里逐字出现
> （chunk 17 含「第一章」、83 含「第一辑 自然」、149 含「第一讲」…），据此定位真实起始 chunk，而不是按行数均分。

**③ 列表型用例的 `gold_chunks` 标注口径要单独看**

三个列表用例的 `gold_chunks` 指向的是**概览块**（私人书单 → 0..1；避免清单 → 56..57；25 倾向 → 210..211），
而**逐条材料在别处**（六辑正文在 83..148，倾向详解在 224..256）。
所以列表型用例的 `ctxRecall`/`recall@10` 天然失真（≈0），**列表覆盖只能用 `gold_items` 覆盖率判**——
这一点已写进 `docs/eval/README.md` §4，避免以后把标注口径缺陷误读成检索缺陷。

> **2026-10-26 补充核对（已被 chunk 原文推翻，保留原结论供追溯）**：原先写"六辑的名称在正文各辑开头块里没有，
> 完整枚举只在 chunk 272（书本自带目录页）"。按 `chunks24689.json` 原文复核：83/88/99/112/123/136 各自都带名称，
> 只是形式是 `第一辑` + 空行 + `自然` 的**跨行标题**，用单行正则找不到而已。
> → 结论修正为：**信息在正文里就有**，"六辑只剩两辑"不是"名称拿不到"，而是首轮条目化只看材料前 6000 字
> （六辑标题的累计偏移 1173 / 6101 / 15549 / 26501 / 35870 / 47071）→ 见准确率计划 §4.6.9。
> 黄金标签 [0,1] 本身核对无误（chunk 1 有「分自然、人类、经济、投资、人生和处世六辑呈现」）。

**④ TOC_RANGE 均匀映射的修复：目录锚点法（2026-10-26 已落地）**

运行时日志把上面的静态推演坐实了（同一轮评测的三条列表题）：

| 用例 | 均匀映射窗口 | 真实位置 | 后果 |
|---|---|---|---|
| 25 条心理倾向 | `[18-43]` | 清单在 210..211 | 精筛只剩 1 块 → **模型直接拒答**（"参考内容里并没有覆盖到"） |
| 幸福与成功避免清单 | `[31-55]` | 清单在 56..57 | 边界刚好切在清单前面 → 清单整段没进上下文 |
| 私人书单六辑 | `[56-142]` | 六辑在 83..148 | 第一辑 83..87 侥幸在内，但档位仍错位 |

**修法**：新增 `TocAnchorResolver`（纯函数，可单测）——
1. **保留目录缩进**解析层级：每个目录行的「内容块」= 到自己或同级行为止（`上篇` → 第一章~第四章）；
2. **锚点定位**：目录标题在正文里逐字出现（「第一辑 自然」在 chunk 83），
   用「序数标记 + 标题」连续匹配定位每行真实起点；书末尾的内嵌目录（Table of Contents）会让每行都命中，
   因此含 ≥3 个目录行的 chunk 一律排除；
3. **区间计算**：起点 = 范围内首个锚点，终点 = 下一个锚点的前一块；选容器行时按内容块展开。

实测（book 24689，13 个真实章节）：**锚点法 13/13 命中真实起点，均分映射 14/14 漏头**。
新增 `TocAnchorResolverTest` 11 例（含回归对照：均分映射漏掉「第一辑 自然」而锚点法命中）；
变异验证：去掉「内嵌目录块排除」即 1 例失败。
LLM 侧的提示词同步改为**带行号编号的目录**（`[10] 第一辑 自然`），消除「章序号 vs 行序号」的歧义；
锚点不可用时（正文里找不到标题、书太大超过扫描上限）自动回退均分映射，不影响原有行为。

---

### 运行时实测记录（2026-10-27：目录即枚举 + 长清单条目化预算）

明细（含踩坑与指标口径）见 `docs/plans/book-qa-accuracy-grounding-and-eval-plan.md` §4.6.17 / §4.6.18，这里只记对本计划有影响的结论：

| 项 | 实测 | 结论 |
|---|---|---|
| 目录子行作条目 | 六辑用例 `items=6`、**不调模型条目化**、`有材料=6/6`、答案 8209 字 | 目录是现成枚举时，条目化这一步可以整段省掉；**清单不在目录里（25 条心理倾向）仍必须调模型** |
| 空答案事故 | 第一稿条目没带 keywords/锚点 → `有材料=0/6`、六节全占位（850 字），延迟却只有 11.2s | 「更快」是少干了活；逐条检索的两条通道（锚点 / keywords）**至少要有其一**，否则整节直接落占位 |
| 长清单预算 | 25 条条目化 12s 下两轮实测超时 4 次；改 25s 后 `items=25`、`有材料=20/20`、`ctxRecall 0.5→1.0`、延迟 76~82s→96.5s | 长章节本来就该多等；判据用确定性两条（片段 ≥40 / 行首编号 ≥12 行），别让它变成质量判定 |
| 预写占位的可观测性 | 占位节标题里带条目名，评测按标题统计覆盖率时会被当成"已覆盖" | 占位节必须能被识别：产品侧日志 `有材料=x/y`，评测侧 `stripPlaceholderSections` |
| 全局编号与证据行同源 | 25 节答案里引用到 `[14]`，而快照只有 13 行（`chunkIndex<0` 的片段被编号但落不了行）→ `citationValidity=0.949` | 编号上界必须等于证据行数：无 chunkIndex 的片段不编号（文本仍进材料）；修后流水线路径 `citationValidity=1.000` |

---

## 8. 明确不做的事

- ❌ **不改前端任何代码**：无新 SSE 事件、无 chip、`types/ai.ts` 不动。多段全部走既有 `message` 流。
- ❌ **不做"多轮交互式回答"**（AI 反问"要继续吗"）：对用户仍是"一次问答一篇回答"。
- ❌ **不做覆盖校验/缺失补写**：v2 循环结构下不需要。
- ❌ 不改分块策略（`QdrantProperties.java:31-33` chunk 800/200）：与现象无关，重向量化代价大。
- ❌ 不引入新依赖、不做通用 Agent 循环。
- ❌ 不重构 `ListQueryRetriever` 三档策略（保留为回退路径）。
- ❌ 不动 `RoundTableService` RAG 行为。

---

## 9. 一句话总结

把"哪些条目必须被回答"从模型的临场记忆变成 **Java 循环的已知常量**：
清单对象化（outline，服务端内部）→ 逐条配额检索（治覆盖集中化）→ 逐段生成、
按序写入**同一条既有 SSE 流**（治腰斩与漏条，且不惊动前端）→ 聚合成一行消息落库。

---

## 10. 二次核验与优化（2026-10-26）

> 核验依据：当前代码（`mvn -q -DskipTests compile` 通过；`mvn -q test` 544 用例 0 failure / 0 error / 0 skip）、
> 实际链路走查、`Agent评测与测试全景指南.md` 的五阶段评测体系与指标框架。

### 10.1 核验结论

**架构可行，代码基本落地；但"验收层"不成立，且有 6 处计划与实现的偏差 + 1 处计划取舍缺口会直接影响计划目标。**

| 维度 | 结论 |
|---|---|
| 架构 | ✅ 保留。"Java 驱动 N 节循环 + 逐条配额检索"是针对漏条的正确结构性解法，不建议重写 |
| 代码落地 | ✅ 大部分 P0/P1 已实现（见 10.2） |
| 运行时验收 | ⚠️ 已部分执行（2026-10-26，真实书 + 真实模型，见 §7 运行时实测记录）：流水线/落库/引用/快照链路已跑通；仍缺流水线/分段流的自动化测试，§7 的验收仍以手工项为主 |
| 评测体系 | ❌ 无黄金集、无离线回放、无 CI、无轨迹数据、无 pass^k / 成本 / 延迟指标 |
| 计划目标达成度 | ⚠️ 常规 2~8 条列表可达；**9 条以上直接禁用流水线**（10.3-1），恰是最难场景 |

### 10.2 计划项 vs 实现对照

| 计划项 | 状态 | 证据 | 偏差 |
|---|---|---|---|
| §4.1-1 读取 `finishReason`、LENGTH 告警 | ✅ | `StreamingSseHandler.java:260-275` | — |
| §4.1-2 `Callback.onFinishReason` default | ✅ | `StreamingSseHandler.java:107-114` | — |
| §4.1-3 RAG 结构化诊断日志 | ✅ | `BookChatRagService.java:409-413` | 只有 span/fillRate，无逐 chunk 分布 |
| §4.1-4 输出上限未设日志 | ✅ | `ChatModelFactory.java:567,597,627,682` | — |
| §4.2 SSE 超时 1h | ✅ | `BookChatService.java:155` | — |
| §4.2 `maxOutputTokens` 接线 | ⚠️ | `AiProviderConfig.java:99-103`；`ChatModelFactory.java:538-552` | **可选**：deploy/资源里无默认值，生产不配则 H1 仍未修 |
| §4.2 单节 LENGTH 续写 | ✅ | `StreamingSseHandler.java:433-470` | — |
| §4.3-1 `hasListSignal` 去硬编码 | ✅ | `ListQueryDetector.java:146-157` | — |
| §4.3-2 风格提示词加豁免句 | ✅ | 代码常量 + `ai-config.json` 4 个风格均有 | — |
| §4.3-3 回退路径覆盖契约 | ⚠️ | `BookChatService.java:611-619` | 只有 topic，**没有条目名** |
| §4.3-4 压缩提示词枚举保护 | ✅ | `ChatHistoryCompressor.java:244-246` | — |
| §4.3-5 检测上下文放宽到 2000 | ✅ | `ListQueryDetector.java:174-184` | — |
| §4.4 `streamSegment` | ✅ | `StreamingSseHandler.java:433` | 段前未显式 `isInterrupted`，取消时会多起一次调用 |
| §4.5 outline 列 / 场景 / 异步 / 现场回退 | ✅ | `AiConversation.java:88-93`；`AnswerOutlineService.java`；`BookChatPipelineService.java:143-217` | — |
| §4.6 `ItemListRetriever` 逐条配额 | ✅ | `ItemListRetriever.java:64-147` | 计划说"阈值放宽 ×0.6"，实现仍用 `searchContent` 的 0.2 |
| §4.7 `qaPrefetchExecutor` / `qaOutlineExecutor` | ✅ | `AsyncSchedulingConfig.java:99,120` | — |

### 10.3 必须修正的偏差与取舍（按影响排序）

**1. 超过 8 条：流水线曾被直接禁用（已修复）**
历史实现 `MAX_PIPELINE_ITEMS = 8`，`outline.size() > 8 → return null`（`BookChatPipelineService.java:198-201`）。
2026-10-26 已修复：上限提到 **20**；9~20 条走流水线，>16 条每节预算压到 150~250 字、单节超时 60s；>20 条仍回退单次流式。

**2. 分段指令缺少"全篇条目清单"和"事实铁律"**
计划 §3.3 要求每节 prompt 带：
```
【全篇任务】1)…2)…3)…4)…5)…
铁律：事实以本节参考资料为准，未涉及就明说，不编造
```
实现（`BookChatPipelineService.java:410-446`）只有"总任务：把「topic」逐条展开" + 本节标题 + 上一节尾巴，**既没有全篇清单，本节指令里也没有事实约束**。
叠加另一个事实——生产系统提示词来自外部 `ai-config.json`，而外部风格词里只有 DEEP/WITTY 各有一句较弱的"扎根参考内容/事实要有支撑"，CASUAL/CONCISE 没有，统一的【回答铁律】前缀并未生效（`AiConfigProvider.java:76-94`）——**流水线的约束强度完全取决于用户选的风格**。这是"过度回答"在流水线里的直接来源。

**3. 分段没有 markdown 标题契约**
计划 §3.3 要求每节开头带 `### 一、…` 以便拼接成文。实现未要求标题，模型可能输出连续段落，N 节拼起来结构感弱、也难做"第几条"的视觉定位。

**4. prefetch 是全局屏障，首节延迟不达标**
`prefetchAll` 内部 `CompletableFuture.allOf(...).join()`（`ItemListRetriever.java:84`），外层再 `.get(15s)`（`BookChatPipelineService.java:251-259`）。
**第一节要等全部 N 条材料检索完才开始生成**，§7 验收里的"首节可见时间 ≈ 旧路径首字时间"大概率不成立（N=8、每条多次 Qdrant 查询时更明显）。

**5. prefetch 超时是"全有或全无"**
外层 15s 超时 → `fetched = Map.of()` → 所有条目的材料都变成空（`BookChatPipelineService.java:256-263`），整轮退化为"每节都说材料不足"。一条慢查询可以拖垮整篇。

**6. 条目化输入截断 6000 字**
`AnswerOutlineService.java:65` 对上一轮回答只取前 6000 字。长列表回答的尾部条目可能还没进入条目化就被截掉，随后 outline 少项 → 流水线节数变少。与第 1 条叠加时，长列表的漏条风险更高。

> 2026-10-26 已修复：改为 `OutlineTextCompactor.compact`。列表型输入保留"尽量多整条正文 + 剩余条目标题清单"，散文型输入退到段落 → 换行 → 句末 → 分句边界；上限仍是 6000 字符，但不再句中硬切、不再丢尾部条目标题（`OutlineTextCompactorTest` 5 例）。

**7.（计划有意为之，但评测上是缺口）首轮列表题不触发流水线**
`resolvePipelineOutline` 要求 `lastAnswer != null`（`BookChatPipelineService.java:149-151`）；用户第一问就是"详细介绍这 11 项能力"时没有上轮回答 → 永远走单次流式。
计划 Q2 明确选择了"第 1 轮不走流水线"，但这类首轮列表题恰恰是覆盖度最需要保证的场景。优化方向：检测到列表型且问题本身可枚举时，直接用"问题 + 当前列表检索结果"构建 outline，不依赖上一轮回答。
同理，首轮概述若是纯散文，`scheduleOutline` 会跳过（`hasEnumerativeStructure=false`），后续"详细说说"也可能拿不到 outline。

> 2026-10-26 已修复首轮列表题：`buildFirstTurnOutline` 用"问题 + 当前检索材料"调用 `AnswerOutlineService.buildFromContext`；超时/无条目/超上限仍回退单次流式。首轮概述为纯散文时仍可能拿不到 outline，未改。

### 10.4 对照评测指南的差距

指南的五阶段与本计划的对应关系：

| 指南阶段 | 本计划现状 | 差距 |
|---|---|---|
| 1. 构建评测集 | 无 | 无黄金集；"5 条详细"只是口头 case |
| 2. 离线评测 | §7 手工验收 | 无成功率、pass^k、成本、延迟分布 |
| 3. CI/CD 回归门禁 | 无 | 无流水线测试；无触发矩阵；无 >8 条用例 |
| 4. 预发布仿真 | §7 阶段 3 盲测（未做） | 无 rubric、无样本量、无 judge 校准 |
| 5. 生产监控与回流 | 有 `ai_usage_log` + RAG 命中统计 | 无逐轮轨迹、无用户反馈、无质量漂移监控 |

指南强调的指标里，本计划目前能回答的只有"节数 = N"这一个结构性指标；**任务成功率、条目覆盖率、忠实度、pass^k、P95、单位成本都没有度量**。而"节数 = N"只能证明每节非空，不能证明每节讲对了、讲全了——这正是指南提醒的"只测结果/只看结构，掩盖蒙对"。

### 10.5 优化方案

#### P0（直接补计划目标，1~2 天）

1. **分段指令补三件事**（`buildSegmentInstruction`）：
   - 全篇条目清单：`1) 标题 2) 标题 … N) 标题`（只有标题 + gist，控制 token）；
   - 每节开头强制 `### {序号}、{标题}`；
   - 事实铁律：`事实以【本节专属参考材料】为准；材料未涉及就说"这部分材料不足"，不引入书外情节`。
   同时把"材料为空 → 基于你对其主题的理解谨慎展开"（`:439-441`）改成**预写占位**或明确的"材料不足，不展开"，不要邀请模型外推。
   这是单文件、最低成本、对"过度回答 + 条目漂移"最直接的一改。
2. **长列表支持**：`MAX_PIPELINE_ITEMS` 已提到 20；9~20 条走单次流水线，>16 条压缩预算与单节超时；**>20 条不再整轮回退**——前 20 条逐节展开，剩余条目由 `buildOverflowSection` 生成确定性清单节（预写、不调模型），并在文末提示可按条目名追问。
3. **prefetch 改为逐条就绪**：
   - `prefetchAll` 每条独立 `orTimeout(perItemMs)`，返回部分结果；
   - 第 1 节材料就绪即开始生成，其余条目后台继续预取（把"全局屏障"改成"逐条 future"）；
   - 单条超时只影响该条，不再整篇变空。
4. **强制输出上限**：给 `BOOK_QA` 与 `BOOK_QA_SEGMENT` 设置默认 `maxOutputTokens`，或在启动时对 null 打 WARN。否则 H1 只是"可选修复"。
   **运行时定案（2026-10-26）**：`BOOK_QA` 兜底 **16384**、`BOOK_QA_SEGMENT` 兜底 **2048**，显式配置优先。
   兜底取 4096 时实测「25 条心理倾向」清单题在 `finishReason=LENGTH` 处被腰斩（`outputTokens=4096, contentChars=4406`），
   只输出前 12 条；写完 25 条需约 6.5k 可见 token + 1.3k 思考 token。网关探针实测接受 65536。
5. **事实边界进入实际 system prompt**：外部 `ai-config.json` 的风格 prompt 不含铁律，必须在组装层合并（或同步到外部配置），否则主链路和流水线的"事实以参考内容为准"都只是代码里的死常量。

#### P1（评测与回归，2~3 天）

6. **确定性测试**（不调外部 LLM）：
   - 触发矩阵：非列表 / 列表无 outline / items=0,1,2,8,9 / outline 超时；
   - N 节 = N：把"段生成器"抽成可注入接口（或对 `streamSegment` 做 fake），断言节数、占位节、聚合文本；
   - 断开：中途 `safeSendEvent` 失败 → 部分落库、无重复行；
   - prefetch 单条超时 → 只有该条占位；
   - 锚点字面命中 ≥0.3 的对齐逻辑；
   - 条目化 6000 字截断的边界。
7. **逐轮轨迹落库**：`pipeline_trace`（或复用准确率计划里的 `ai_retrieval_snapshots`）记录：触发判定、outline 来源、N、每条材料命中/锚点、每节 finishReason/字符数/耗时、总 token。
8. **列表黄金集**：5 本书 × 10 个列表问题，标注 `gold_items[]`；指标：
   - 节数 = N（确定性）；
   - 条目覆盖率（标题/关键词命中）；
   - 每条材料命中率；
   - 每节忠实度（有引用或与材料重叠）；
   - pass^3、P50/P95、每轮 LLM 调用数与 token。
9. **回退契约补条目名**：`buildQuestionMessage` 在 outline 可得时列出条目名，而不是只给 topic。
10. **首轮列表题的 outline**：检测到列表型且无上轮回答时，用"问题 + 当前列表检索结果"构建 outline，让首轮"详细介绍这 11 项"也能进流水线；首轮概述为散文时，条目化至少尝试一次而不是直接跳过。

#### P2（小修，随 P0 一起做）

11. 段间预检查 `Thread.currentThread().isInterrupted()`，避免取消后多起一次模型调用。
12. 更新计划里的陈旧数字：`ragTopK` 默认已从 10 改为 **100**（`application.yml:264`），H4 的"10 个坑位集中"描述需按现状重写；其余行号已漂移。
13. `ItemListRetriever` 的"关键词阈值放宽 ×0.6"要么实现，要么从计划里删掉，避免文档与代码不一致。

### 10.6 修订后的阶段计划

| 阶段 | 内容 | 工期 | 验收 |
|---|---|---|---|
| **A 补计划目标** | 10.5-1/2/4/5 | 1~2 天 | 9 条以上列表也进流水线；每节有标题与事实约束 |
| **B 延迟与韧性** | 10.5-3 + 10.5-11 | 2~3 天 | 首节首字 ≤ 旧路径 + 20%；单条超时只影响该条 |
| **C 自动化验收** | 10.5-6 + 10.5-7 | 2~3 天 | `mvn test` 新增用例全绿；每轮有轨迹 |
| **D 评测集** | 10.5-8 + 10.5-9 | 2~3 天（可并行） | 黄金集报告：覆盖率/pass^3/成本/延迟 |
| **E 盲测** | 原 §7 阶段 3 | 0.5~1 天 | 盲测评分不劣于旧路径，且条目完整性显著提升 |

### 10.7 已落地（2026-10-26）

- **分段指令**：全篇条目清单 + 每节 markdown 标题契约 + 事实边界；缺材料节点改为预写占位，不再调用模型。
- **prefetch**：`prefetchAllAsync` 逐条 future + 单条 8s 超时；首节只等自己那一条，单条失败不再清空整篇。
- **逐条依据**：`ItemListRetriever.Material` 同时回传文本与真正入选的 chunk；流水线快照记录精确依据，`selectByQuota` 不切 chunk 内部。
- **分段引用**：`PipelineFragmentRegistry` 跨条目分配全局片段编号；每节材料带 `【片段 N】`，聚合后统一走 `CitationValidator` 并写 `citation_json`。
- **多轮保护**：压缩跳过最近一轮 assistant；`getLastAiAnswer` 优先原始回答（6000 字上限），列表检测/查询扩展不再读压缩版。
- **首轮列表题**：无上轮回答时用"问题 + 当前检索材料"调用 `buildFromContext` 提取条目；超时/无条目/超上限回退单次流式。**超时 3s → 12s**（运行时实测同网关查询扩展单次约 3.7s，3s 预算下首轮条目化必然超时、流水线从未被触发；条目化本身实测 6.2s / >8s / ≤8s，8s 预算卡在分布边缘，约一半首轮列表题走不到流水线）。
- **长列表**：上限 8→20；9~20 条走流水线，>16 条每节预算 150~250 字、单节超时 60s，避免顶穿 1h SSE；**>20 条不再整轮回退**，前 20 条逐节展开、其余由确定性清单节兜底。
- **条目化输入**：6000 字上限改为 `OutlineTextCompactor.compact` 的边界感知压缩，列表保留全部条目标题。
- **提示词**：事实边界在组装层强制注入；外部 `ai-config.json` 的"标注章节位置"已移除。
- **TOC_RANGE 目录锚点**：新增 `TocAnchorResolver`（保留缩进解析层级 + 「序数标记+标题」在正文里定位真实起点 + 排除书末尾内嵌目录），替代原先「总 chunks ÷ 目录行数」的均分映射；LLM 侧提示词改为带行号编号的目录；锚点不可用时回退均分映射。`TocAnchorResolverTest` 11 例（变异验证通过）。
- **测试**：新增提示词组装、分段指令、可答性守卫、输出上限、合并去重、质量闸门、条目化压缩等用例；`mvn test` 544 全绿。

### 10.8 结论

**不需要重写架构，需要补"边界 + 验收"两层。**
原计划的判断（问题在生成侧与检索分布侧，不在检索量）仍然成立；代码也确实把"N 节 = N"做成了结构性保证。
但"节数对"不等于"内容对"：当前分段指令缺全篇清单与事实约束、9 条以上禁用流水线、prefetch 屏障拖慢首节且一损俱损。这四项修完，计划才算真正落地；再补轨迹与黄金集，才能证明"落地有效"而不是"看起来跑通了"。
