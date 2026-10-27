# RAG 入库质量与分块计划

> 状态：草案 · 日期：2026-10-26
> 范围：图书解析 → 文本净化 → 结构提取 → 分块 → 向量入库（Qdrant `kbook_content`），以及检索时的上下文拼接/截断。
> 关联：
> - [`book-qa-accuracy-grounding-and-eval-plan.md`](./book-qa-accuracy-grounding-and-eval-plan.md)（问答准确率、引用、评测）
> - [`book-qa-iterative-generation-plan.md`](./book-qa-iterative-generation-plan.md)（列表多段流水线）
>
> 一句话：**现在缺的不是"要不要加工"，而是"只做了去 HTML 标签"。** 入库前的文本质量决定了后面检索、排序、引用能到的上限。

---

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 入库前要不要加工？ | **要，而且当前几乎没做。** EPUB 只去标签，PDF/OCR/TXT 基本裸入库 |
| 分块要不要处理截断？ | **要。** 当前"尽量"避段/避句，但没有保证；且合并时重复 overlap、两处硬 `substring` 会切句 |
| 先做哪层？ | 文本净化 + 质量闸门 → 章节/偏移结构 → 分块器重写 → 语义增强 |
| 代价 | 改文本必须重嵌入；只改元数据可 `setPayload` 增量。必须做版本化与灰度 |

### 0.1 实施进度（2026-10-26）

| 项 | 状态 | 说明 |
|---|---|---|
| §2.4 结构感知分块器 | ✅ | 新增 `TextChunker`：段落/句子优先、句子优先于换行、单句超长才硬切并标记、min/max、短尾合并、偏移保留 |
| §4.2 Qdrant 元数据 | ✅（部分） | 写入并打通 `startChar/endChar/cutReason/embeddingModel`；章节字段待结构提取 |
| §3.1 文本净化 | ✅（部分） | TXT BOM/UTF-8/GB18030；EPUB 块级分隔/实体/剔除 script/style/nav；PDF/OCR 页码行、断词、空白归一；**文字型 PDF 与扫描版 OCR 都按页清理重复页眉/页脚**（OCR 产出现在保留分页结构）；OCR 中文间空格/形近误识别未做 |
| §3.1 第 5 条 入库质量闸门 | ✅ | 新增 `ContentQualityGate`：空块/极短块/乱码块/纯符号块/完全重复块不嵌入；乱码块 >10% 打警告日志；纯函数、8 例单测 |
| §3.1 第 5 条 后半 + §4.1 `books.parse_quality`（解析报告可查） | ✅ | 新增 `ParseQualityReport`（纯函数统计 + JSON）+ `ParseQualityReportService`（单列 UPDATE，自带事务）；`EmbeddingIndexService.generateContentEmbeddingWithCount` 在切分/闸门处统计并落库；`GET /api/admin/books/parse-quality/{bookId}` 可查；迁移 `V7__add_book_parse_quality.sql`（information_schema 幂等守卫）；单测 `ParseQualityReportTest` |
| §2.3 展示截断 `AnswerOutlineService` | ✅ | 新增 `OutlineTextCompactor`：列表输入按「整条正文 + 剩余条目标题清单」压缩，散文退到段落/换行/句末/分句边界；条目化输入不再句中硬切、不再丢尾部条目标题 |
| §3.2 章节结构 | ✅（部分） | `TextStructureExtractor` 识别中英文标题行 + 偏移；`chapterTitle/chapterIndex` 写入 chunk 元数据并贯通读取路径；片段标签带真实章节。页码未做 |
| §3.3 语义增强 | ⬜ | 待分块稳定后 |
| §4.3 索引版本/灰度 | ⬜ | 全量重嵌入时再做 |

> 基线：`mvn -q test` 562 用例全绿（2026-10-26，含 `ParseQualityReportTest` 9 例）。
> **注意**：旧索引不会自动使用新分块/章节元数据，旧书也需要重新解析才能获得净化后的全文；重跑前先用黄金集对比 Recall。

**解析报告的运行时自证（2026-10-26，dev 库）**

用一份故意带缺陷的 TXT（`raw 10 块`：1 条短残留行 + 1 个混入 30 个 U+FFFD 的长块 + 符号墙 + 重复免责声明 + 无标点超长单句）走真实上传→问答路径，
新库 `books.parse_quality` 里第一次有了报告：

```
GET /api/admin/books/parse-quality/{bookId}
{"contentChars":6312,"rawChunks":10,"acceptedChunks":8,"rejectedChunks":2,"rejectedRatio":0.2000,
 "garbledRatio":0.0000,"garbledHeavy":true,"hardCuts":6,"indexedChunks":8,
 "chapters":2,"chapterCoverage":1.0000,"embeddingModel":"…/bge-m3(free)","rejectReasons":{"GARBLED":2}}
```

确认到的四件事：
1. `V7__add_book_parse_quality.sql` 在启动时生效（`Migrating schema kbook-dev to version "7 - add book parse quality"` → `Successfully applied 1 migration`）；
2. 报告在向量化结束后独立落库（日志 `解析质量报告已落库: bookId=…, rawChunks=10, accepted=8, rejected=2`），没有把几分钟的向量生成包进事务；
3. **两个乱码口径确实分开**：整块被拒的 GARBLED 块不进入 `garbledRatio`（所以它是 0.0000），而"块被拒"这件事体现在 `rejectedRatio=0.20` 与 `garbledHeavy=true`——这正是 §5 里"乱码率"两行的差别；
4. `hardCuts=6` 与 `chapters=2 / chapterCoverage=1.0000` 来自同一份统计，硬切比例 6/8 是这份合成文本的极端无标点结构造成的，不是分块器退化。

顺带暴露的一个既有缺陷（**未修**）：`EmbeddingConsistencyChecker` 的启动日志让用户"调用 `POST /api/admin/books/vector/rebuild-content` 触发重建"，
但仓库里**没有这个端点**（全仓检索只有这一处字符串）。内容向量重建实际只由问答路径的 `ensureContentEmbedded` / `forceReEmbedIfMissing` 触发。
已把日志改成指向真实存在的端点与真实触发路径；补一个独立的重建端点属于另一件事，需要单独设计（限速 + 幂等 + 进度反馈）。

探针书留在 dev 库（`bookId=24718`，标题「解析质量探针(自测可删)」）：它带已知缺陷配比，可当解析侧的固定样本；
库里没有书籍硬删除端点，要清掉只能走回收站（`/api/book-trash`），那不会清 Qdrant/ES 里的向量，**我没有替它做删除**。

---

## 1. 现状（代码级）

### 1.1 解析与清洗

| 格式 | 当前处理 | 证据 | 问题 |
|---|---|---|---|
| EPUB | `HTML_TAG_PATTERN.matcher(html).replaceAll("")`，spine 顺序拼接 | `BookFullTextExtractor.java:373-393` | 只去标签，**不补块分隔**（`<p>A</p><p>B</p>` → `AB`）；不解析 HTML 实体；不去 nav/目录重复；标题层级丢失 |
| EPUB 降级 | ZIP 内 HTML 同样只去标签 | `:345-367` | 同上 |
| PDF（文字型） | 原始 `PDFTextStripper.getText()` | `:422-433` | 页眉/页脚/页码保留；跨行断词、连字符不处理；多栏顺序可能错 |
| PDF（扫描） | OCR 原始输出直接拼接 | `:437-438` | 中文间空格、误识别、行尾断词不清 |
| TXT | `Files.readString(filePath)`，默认 UTF-8 | `BookParserService.java:975` | **无编码探测**，GBK/GB18030 中文 TXT 会整体乱码 |
| 全文持久化 | `Book.fullText`（MEDIUMTEXT）存原始全文；`ragContent` 仅内存 | `Book.java:131-136` | 重嵌入可复用，但清理后的文本没有独立版本 |

补充：`WHITESPACE_PATTERN` 只用于"是否扫描版"的长度估算（`BookFullTextExtractor.java:408`），没有用于存储文本；且 Java 里 `"\s+"` 实际是空格模式，不是全空白。

### 1.2 分块

`EmbeddingIndexService.splitContent`（`:802-847`）+ `findChunkBoundary`（`:861-888`）：

- 目标 800 字，重叠 200 字；向后延伸窗口 +100 字。
- 切点优先级：**向后找 `\n\n` → 回退找 `\n\n` → 回退找 `\n` → 回退找 `。！？` → 都没有就硬切**。
- 搜索窗口只有 `[chunkEnd-200, chunkEnd+100)`。

### 1.3 元数据

`processContentBatch`（`:165-176`）只写：

```
bookId / chunkIndex / totalChunks / 模型名（键名写死为 "storeManager.getEmbeddingModel()"）
```

无章节、无页码、无字符偏移。读取侧 `EmbeddingStoreManager.doFilterSearch:555-567` 重建 Metadata 时又只拷 `bookId/chunkIndex/embeddingModel`，`searchContentByChunkIndex:211-217` 只拷 `chapterIdx`——即使写进去也拿不到。

### 1.4 检索时的截断

| 位置 | 行为 | 评价 |
|---|---|---|
| `RagPipelineComponents.truncateToChars:328` | 放不下就 `break` | 不切 chunk，但会浪费剩余预算 |
| `RagPipelineComponents.truncateByQuota:352` | 放不下就 `continue` | 不切 chunk，较好 |
| `ListQueryRetriever.toMatches:519-531` | 放不下就 `break` | 不切 chunk，同上浪费预算 |
| `ItemListRetriever` 逐条配额 | `selectByQuota` 放不下就跳过 | 不切 chunk，较好 |
| `AnswerOutlineService` 条目化输入 | `OutlineTextCompactor` 按条目/句子边界压缩 | **已修**：长列表尾部条目标题以清单形式补回 |
| `mergeAdjacentChunks:208-249` | 直接拼接整段文本 | **相邻 chunk 的 200 字重叠被重复拼进上下文** |

### 1.5 质量闸门

- 入库前无任何质量检查：空块、乱码、重复页眉、极短 chunk 都会嵌入。
- 查询侧有乱码检查，但只记一次 miss 并继续返回上下文（`BookChatRagService.java:415-429`，日志写"丢弃"实际没丢）。

---

## 2. 分块/截断专项：要不要处理

**要，但要区分三件事：入库切分、上下文拼接、展示截断。**

### 2.1 入库切分：现在"尽量不切句"，但不是保证

现有优先级在大多数中文段落上有效，但有 5 个缺口：

1. **硬切兜底**：切点只在 `[chunkEnd-200, chunkEnd+100)` 里找；遇到长段落、无标点、表格、代码，找不到边界就硬切。
2. **`\n` 优先于 `。`**：PDF/OCR 常在中句硬换行，当前会优先切在行尾，**把句子切断**。
3. **只认中文句末标点**：`。！？` 之外，`；：…`、英文 `.?!`、引号后标点都没有。
4. **overlap 从 `chunkEnd-200` 开始**：下一块的开头天然是半句；这是重叠的代价，但会拉低该块的向量质量。
5. **无最小/最大约束**：末块可能只有几个字；单句超过 800 字时硬切。

### 2.2 上下文拼接：重叠被重复，预算被浪费

`mergeAdjacentChunks` 把下一块全文拼上，而下一块开头含 200 字重叠。合并两块时上下文里会有约 200 字重复；三块合并重复更多。后果：

- 浪费 10~15% 的上下文预算；
- 模型看到重复句子，可能重复输出；
- 引用/对齐时同一段文本对应多个 chunkIndex，锚点变模糊。

**必须处理**：合并时按 `startChar/endChar` 只取 `[prev.end, next.end)`，或对相邻文本做最长公共前后缀去重。

### 2.3 展示/抽取截断

- `ItemListRetriever` 的逐条配额用 `selectByQuota`：放不下就跳过该块，不切 chunk 内部；
- `AnswerOutlineService` 的 6000 字输入上限改为 `OutlineTextCompactor.compact`：列表型文本保留"整条正文 + 剩余条目标题清单"，散文型文本退到段落 → 换行 → 句末 → 分句边界，都找不到才硬切。

**原则**：截断只发生在"喂给模型/展示"的最后一刻，并且**切在句子或条目边界**；入库的 chunk 文本永远保持完整。列表类内容优先保留所有条目标题，再按条目分配预算。

### 2.4 目标分块器

优先级（高 → 低）：

```
章节/标题边界
  → 空行（段落）
  → 句末标点（。！？…；.!? 及其后的引号/括号）
  → 分句标点（，、；:）
  → 空格/词边界（英文）
  → 硬切（仅当单个分句 > maxChunkChars，并标记 isHardCut）
```

规则：

- `target=800 / min=200 / max=1200`（可配置）；单句超 max 时允许略超，不硬切句子。
- 重叠 `overlap=200`，但**起点吸附到上一句/上一段开头**，并记录真实 `startChar`。
- 表格、代码块、诗歌：尽量整块保留；无法保留时按行切并标记 `contentType`。
- 先做换行归一化（PDF/OCR 的中句硬换行合并），再切分。
- 末块 < min 时并回前一块（若合并后 ≤ max）。
- 每个 chunk 记录 `cutReason`（section/paragraph/sentence/clause/hard）与 `isHardCut`，供质量统计。
- 每个 chunk 记录 `startChar/endChar`（未 trim 的原始区间），用于合并去重与引用定位。

### 2.5 验收（分块专项）

- 抽样 100 chunk/格式：**以句末标点或段落边界结尾的比例 ≥ 99%**；以句首/段首开头的比例 ≥ 95%。
- 合并相邻 chunk 后，重叠重复率 = 0（按 `startChar/endChar` 校验）。
- chunk 长度分布：P5 ≥ 200，P95 ≤ 1200；`isHardCut` 比例 < 2%。
- 黄金集 Recall@10 不低于改造前（目标 +5%）。
- `mvn test` 新增纯单测：边界优先级、长句、无标点、表格、末块合并、重叠去重。

---

## 3. 文本净化（入库前）

### 3.1 P0（确定性、低风险）

1. **编码探测**：TXT/PDF 先探测 UTF-8 / GB18030 / BOM；乱码率超阈值直接拒绝入库并标记"需重新解析"。
2. **HTML → 结构化文本**：块级标签转换行；解码 `&nbsp;` 等实体；剔除 `script/style/nav`；保留标题、列表、段落边界。
3. **PDF 净化**：去页眉/页脚/页码；合并跨行断句；处理连字符；去掉目录页与正文重复。
4. **OCR 净化**：中文间多余空格、常见形近误识别、行尾断词。
5. **入库质量闸门**：chunk 长度过短、替换符/问号占比过高、重复率过高 → 不嵌入；记录解析报告（字符数、乱码率、章节覆盖率、被拒 chunk 数）。
6. **去重**：重复页眉、目录与正文重复段落。

### 3.2 P1（结构保留，引用/章节问答的硬前提）

7. 解析产出一份**带偏移的规范化全文**：记录每个标题/段落的 `offset`、`chapterTitle`、`chapterIndex`、`pageNo`。
8. 分块在章节/标题边界优先切；chunk 元数据带 `headingPath / pageNo / startChar / endChar / contentType / cutReason`。
9. 元数据写入 Qdrant payload，并**打通读取路径**：`EmbeddingStoreManager.doFilterSearch`、`EmbeddingSearchService.searchContentByChunkIndex` 都要补字段。
10. 修正模型名元数据键（当前写死 `"storeManager.getEmbeddingModel()"`），统一为 `embeddingModel`。

### 3.3 P2（语义增强，成本高，最后做）

11. `embed_text = 章节标题 + 上位主题 + 原文`；`display_text` 保持原文，二者分开存/分别使用。
12. 短 chunk、表格、对话体做补全/摘要（LLM）。
13. QA 对/假设问题（成本最高、索引膨胀，优先级最低）。
14. 只用 LLM 做"结构化/摘要"，**不要用 LLM 改写正文后再入库**。

---

## 4. 数据模型与迁移

### 4.1 MySQL

| 字段/表 | 用途 |
|---|---|
| `books.full_text` | 保留原始全文（MEDIUMTEXT），作为重解析/重嵌入的源头 |
| `books.content_version` | 解析/清洗版本号（新增） |
| `books.structure_json` 或 `book_chapters` 表 | 章节标题、起止偏移、页码（新增） |
| `books.parse_quality` | 解析报告 JSON：字符数、乱码率、章节覆盖率、被拒 chunk 数（新增） |

> 不建议覆盖 `full_text`：保留原始文本，清洗结果由 `content_version + structure_json` 在索引阶段生成，便于回滚与 A/B。

### 4.2 Qdrant

chunk payload 增加：

```
chunkSchemaVersion / contentVersion
chapterTitle / chapterIndex / pageNo
startChar / endChar
contentType (body/table/caption/footnote)
cutReason / isHardCut
embeddingModel（统一键名）
```

### 4.3 索引版本与灰度

- 方案 A（推荐）：新 collection `kbook_content_v2` + alias 切换；旧集合保留用于回滚。
- 方案 B：同一集合用 `contentVersion` payload 过滤；查询侧按版本路由。
- 重嵌入走后台任务，限速；先灰度 10~20 本，用黄金集对比 Recall@10 / nDCG@10，再全量。
- 新旧索引不混用；回滚只切 alias。

---

## 5. 评测

| 指标 | 计算 | 目标 |
|---|---|---|
| 解析成功率 | 成功解析书数 / 总数 | ≥ 99% |
| 乱码率 | 替换符/问号占比超阈的 chunk 比例 | < 1% |
| 分块边界质量 | 抽样 100 chunk/格式，句末/段末结尾比例 | ≥ 99% |
| 重叠重复率 | 合并后重复字符 / 总字符 | 0 |
| 长度分布 | P5 / P50 / P95 | 200 / ~800 / ≤1200 |
| 检索 | Recall@10、nDCG@10（黄金集 `gold_chunks`） | 不低于基线，目标 +5% |
| 端到端 | 引用合法率、忠实度、pass^3 | 见准确率计划 |

黄金集与运行方式复用 `book-qa-accuracy-grounding-and-eval-plan.md` §4；检索回放先做独立 runner（依赖 Qdrant），不进当前 `mvn test`。

---

## 6. 明确不做

- ❌ 不用 LLM 改写正文后再入库（幻觉/漂移/不可追溯）。
- ❌ 不为了"干净"把标题、列表、表格结构抹平。
- ❌ 不同时改 chunk 大小和清洗规则（无法归因；先清洗、再调分块）。
- ❌ 不在没有黄金集的情况下全量重跑。
- ❌ 不追求"绝对不切句"：单句超过 max 时必须允许硬切，但要标记并可统计。

---

## 7. 阶段与工期

| 阶段 | 内容 | 工期 | 验收 |
|---|---|---|---|
| **A 净化与闸门** | 3.1-1~6（编码、HTML/PDF/OCR 清洗、质量闸门、去重） | 3~5 天 | 乱码率 <1%；解析报告可查；抽检文本不再出现 `AB` 粘连 |
| **B 结构与元数据** | 3.2-7~10 + 4.2 | 4~6 天 | 章节/页码/偏移可查；引用能落到真实章节 |
| **C 分块器重写** | 2.4 + 2.5 + 合并去重 + 两处硬截断修复 | 4~6 天 | 边界质量 ≥99%；重叠重复率 0；单测全绿 |
| **D 语义增强** | 3.3-11~13 | 3~5 天 | 黄金集 Recall/nDCG 提升；成本可控 |
| **E 全量迁移** | 4.3 灰度 + alias 切换 + 回滚演练 | 2~3 天（含观察） | 灰度书指标达标；可一键回滚 |

> 依赖：B 是 C 的前提（没有偏移就做不了合并去重与引用）；D 必须在 C 之后，否则无法区分"清洗收益"和"分块收益"。

**当前进度（2026-10-26）**：A 的编码/HTML/PDF/OCR 清洗与质量闸门（含解析报告持久化 + 乱码率入 DB，见 §0.1 与 §4.1）、B 的章节+偏移+元数据贯通、C 的结构感知分块 + 合并去重 + 两处硬截断均已落地并有单测；仍缺 B 的页码字段、D 语义增强、E 索引版本与灰度（需全量重嵌入时再做）。

---

## 8. 附录：关键证据

| 主题 | 位置 |
|---|---|
| EPUB 全文提取 | `BookFullTextExtractor.java:373-393`；ZIP 降级 `:345-367` |
| PDF 全文 / OCR | `BookFullTextExtractor.java:422-439`、`:453-500` |
| TXT 解析 | `BookParserService.java:973-1009` |
| 分块 | `EmbeddingIndexService.java:802-847` |
| 切点优先级 | `EmbeddingIndexService.java:861-888` |
| chunk 元数据 | `EmbeddingIndexService.java:165-176` |
| 元数据读取重建 | `EmbeddingStoreManager.java:555-567`；`EmbeddingSearchService.java:211-217` |
| 合并重叠 | `RagPipelineComponents.java:208-259` |
| 上下文截断 | `RagPipelineComponents.java:328-363`；`ListQueryRetriever.java:519-548` |
| 硬截断 | `ItemListRetriever.java:175-177`；`AnswerOutlineService.java:175-177` |
| 查询侧乱码检查 | `BookChatRagService.java:415-429` |
| 全文持久化 | `Book.java:131-136` |

---

## 9. 一句话总结

**先把脏文本清掉、把章节和偏移留住，再用"句子/段落优先 + 重叠去重"的分块器切；最后才考虑 embedding 前缀与语义增强。** 分块"尽量不切句"是不够的，必须做到"可验证地不切"。
