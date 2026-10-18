# 图书问答：多次检索 + 多段流内生成 改造计划

> 状态：**v2 · 代码完成（全部 P0/P1 已实现，后端编译 BUILD-OK-EXIT0；§7 运行时验收待用户执行）**
> 范围：`BookChatService` 图书问答链路（SSE 流式 + RAG）
> 触发问题：一次问答 → 多次搜索 → 一次回答，导致"把 5 条内容讲详细些"只答了前 3 条
>
> **修订记录**
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
