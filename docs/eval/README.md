# 图书问答离线评测 runbook

> 关联：`docs/plans/book-qa-accuracy-grounding-and-eval-plan.md` §4、`docs/plans/book-qa-iterative-generation-plan.md` §10。

## 1. 准备

1. 后端已启动（默认 `http://localhost:8181`），Qdrant 与模型可用。
2. 准备一个可登录用户的 JWT，设置：
   ```bash
   export KBOOK_EVAL_TOKEN="<jwt>"
   ```
   本地开发可以自签长时效 token（access token 默认 2 小时，全量跑 3 轮容易中途过期把报告作废）：
   ```bash
   node scripts/eval-dev-token.mjs --base-url http://localhost:18181 --ttl-hours 24
   ```
   脚本用 `backend/src/main/resources/application-dev.yml` 里的 `jwt.secret` 签发，签完立刻调
   `GET /api/user/me` 自检，验证通过才打印。**只在本地开发库用，不要对生产密钥执行。**
3. 复制 `golden-set.sample.jsonl` 为真实黄金集，填写 `book_id`、`gold_chunks`、`gold_items` 等。
   现成参考：`golden-set.real.jsonl`（book_id=24689《穷查理宝典》14 例，gold_chunks 由人工通读该书
   全部 273 个 chunk 原文后标注，非系统检索结果，避免循环自证）。

## 2. 用例字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | 用例唯一标识 |
| `book_id` | 是 | 大于 0；0 为模板会被跳过 |
| `type` | 否 | `factual` / `unanswerable` / `list` / `multiturn` / `meta` |
| `question` | 是 | 用户问题 |
| `session_id` | 否 | 多轮追问时复用已有会话 |
| `pre_turns` | 否 | 多轮用例的前置问题数组；与正式问题在同一会话内依次发送，`sessionId` 由首轮响应自动带出（不依赖外部预置会话） |
| `gold_chunks` | 否 | 应命中的 `chunkIndex` 列表，用于 Recall/MRR/nDCG |
| `gold_items` | 否 | 列表型应覆盖的条目名，用于条目覆盖率 |
| `must_refuse` | 否 | 不可答题设为 `true` |
| `refusal_patterns` | 否 | 自定义拒答话术片段；默认含"没找到/未涉及/信息不够/无法回答/材料不足/没有检索到" |
| `refusal_topics` | 否 | 主题词列表。命中"主题词附近有否定词"即判为拒答（覆盖"这本书里没有量子计算""量子纠缠？不存在的"这类不含拒答关键词的同义否定） |
| `must_not_claim` | 否 | 回答中出现即判失败的断言片段；**不可答题同样校验**，用于捕捉"先说没找到、再补一段编造"的伪拒答 |
| `min_recall` | 否 | 默认 0.5 |
| `min_coverage` | 否 | 默认 1.0 |
| `min_citation_validity` | 否 | 默认 0.99 |
| `rubric` | 否 | 给人工/LLM judge 的评分说明 |

## 3. 运行

```bash
# 指标纯函数单测（不需要后端）
node --test scripts/eval-bookqa-metrics.test.mjs

# 跑黄金集（每例 3 次，计算 pass^3）
node scripts/eval-bookqa.mjs \
  --dataset docs/eval/golden-set.real.jsonl \
  --runs 3 \
  --out eval-report.json
```

runner 会：
1. 调 `POST /api/books/{bookId}/chat/stream`，解析 SSE；
2. 用 `session_id` 查 history，找到最后一条 assistant；
3. 调 `GET /api/books/{bookId}/chat/{conversationId}/evidence` 取实际依据；
4. 计算 Recall@k / MRR / nDCG@k / 上下文召回 / 条目覆盖率 / 拒答正确率 / 引用合法率 / 延迟；
5. 写 `eval-report.json` 并打印 Markdown 表。

**报告有效性**：只要有用例的所有 run 都执行失败（认证过期、后端掉了、模型不可达），
整份报告会被标记为 `valid: false`，Markdown 顶部打红色警告，进程退出码 3。
这种"跑挂了"的报告不能当质量基线用——所有指标都是 0，读成"质量差"就错了。
错误按 `auth` / `timeout` / `network` / `other` 归类并统计在 `aggregate.errorKinds` 里。

**一次评测必须独占一个后端**（2026-10-26 实际踩到）。`qaOutlineExecutor` 只有 2 线程，
两个评测共用同一个后端会互相挤占，列表用例的"首轮条目化（12s）"超时被人为制造出来，
P50/P95 全部虚高。用包装脚本（如 `scripts/eval-postfix.sh`）起评测时，
终止包装脚本**不会**终止它的 `mvn spring-boot:run` 与 `node` 子进程——它们会继续占着端口活着。
复跑前：确认目标端口上的 java 进程数，跑完核对日志里每个 `question=` 的出现次数 == `--runs`。

### 3.1 快照回放（不调模型）

已经跑过的回答把依据写进了 `ai_retrieval_snapshots`，可以直接回放打分，不必再烧一次模型：

```bash
node scripts/eval-snapshot-coverage.mjs \
  --dataset docs/eval/golden-set.real.jsonl \
  --since 2026-10-26T17:33:00 \
  --out docs/eval/snapshot-coverage-24689.json
```

它按会话里的提问匹配黄金集用例，给出两个口径：

- **ctxRecall**：gold 是否进入了这一次回答的材料（不看排序）；
- **rankRecall**：gold 排在按 score 降序的前 k 条里的比例（看排序）。

另外统计"回答引用的片段编号是否落在 gold 上"——材料在上下文里但模型没引它，
是另一类问题（提示词/注意力），不能和"没检索到"混为一谈。

> 依据编号口径：快照的写入顺序（`ai_retrieval_snapshots.id` 升序）就是提示词里的片段顺序，
> 也是回答中 `[N]` 的来源；`/evidence` 接口按 `chunkIndex` 升序返回，不能直接当顺序用。
> 引用编号**优先取 `ai_conversations.citation_json`**（`CitationValidator` 校验后的合法编号，
> 其 `included` 字段就是本次回答的片段总数，与快照行数一致），正文正则只作兜底——
> `mysql --batch` 的输出按行切分，多行回答正文会被截断成第一行，只靠正文正则会漏掉绝大部分引用。

## 4. 指标口径

- **Recall@k**：`gold_chunks` 中被实际依据命中的比例（只看按 score 降序的前 k 条，衡量**排序**）。
- **上下文召回（ctxRecall）**：`gold_chunks` 中进入了本次回答材料的比例（不看排序）。
  两个口径必须分开看：检索预算大时（整本书进上下文）top-10 召回低只说明"gold 排第 10 名之后"，
  不代表答案拿不到依据。报告里 `pass^k` 与 `pass^k(上下文)` 分别给出这两个口径。
  > ⚠️ `pass^k` 用的是排序口径，**上下文里塞得越满，答对了越容易被判 fail**：
  > gold 排在第 22 段时 Recall@10 = 0，即使答案正确引用了该片段。
  > 读报告时**必须同时看 `pass^k(上下文)`**，否则会把正确答案统计成失败。
- **MRR**：第一个命中 gold chunk 的倒数排名。
- **nDCG@k**：二值相关性下的归一化折损累计增益。
- **条目覆盖率**：`gold_items` 中在回答里出现的比例（忽略空白）。
- **拒答正确率**：`must_refuse=true` 的用例中回答被判为拒答的比例。判定 = 命中 `refusal_patterns`，或主题词邻域出现否定词（`refusal_topics`）。
- **误拒率**：可答用例中被判为拒答的比例（越低越好）。可答用例的拒答判定与拒答用例口径不同：
  拒答词必须出现在读者所问对象（`gold_items`）40 字邻域内才算拒答。
  原因：答案写全之后仍可能在尾部做局部坦白（"第11、18、19、20条…我按'这部分材料不足'如实标注了"），
  全文搜拒答词会把整条正确回答判成拒答，误拒率就只剩这一个来源。
- **引用合法率**：回答中的 `[N]` 引用里，N 落在 `1..evidenceCount` 的比例。
- **pass^k**：同一用例跑 k 次全部成功才计成功；报告同时给出单次成功率。
- **延迟**：SSE 从请求到 `done` 的总耗时；P50/P95 按所有 run 统计。
- **topScore**：本轮依据里的最高相似度分（向量分用例），用于标定 `qdrant.absolute-min-score`。列表型用的是 rank 序数分，不可比，已排除在统计外。

> 列表型用例的 `gold_chunks` 有两种情况，要分开看：
> - **答案不在正文块里**（如「私人书单六辑」：`gold_chunks=[0,1]` 是前篇里"分自然、人类、经济、投资、人生和处世六辑呈现"那句概述，
>   目录页 chunk 272 也一次列全六辑名），逐条检索拿到的是各辑的正文块（档 2 窗口 82~148；各辑开头块以
>   `第一辑` + 空行 + `自然` 的跨行形式带名称），chunk 号天然对不上 →
>   这类用例以 `gold_items` 覆盖率为主判据，`min_recall` / `ctxRecall` 参考价值有限。
>   **当前状态**：该用例 coverage 已到 1.000（六辑齐全、每辑带引用），但 ctxRecall / recall 仍 0.000 ——
>   属于标注口径问题，不是检索问题；要让它变绿得把各辑锚点块（83/88/99/112/123/136）补进 `gold_chunks`，
>   改 gold 之前不要为了指标去动检索。
> - **答案就在某个块里**（如「幸福与成功避免清单」的清单正文在同一块），
>   `ctxRecall` 是有效判据，掉 0 说明检索没覆盖到那一节。

## 5. 当前限制

- runner 需要真实后端 + Qdrant + 模型；本仓库当前没有 CI 环境可直接跑。
- `/evidence` 只返回 `ai_retrieval_snapshots` 中记录的依据；旧回答（快照上线前）为空，Recall 会显示 `n/a`。
- 页面 `gold_chunks` 需要人工标注；可先从 Qdrant/快照里抽候选再核对。
- LLM judge 尚未接入；当前成功判定是确定性规则（召回/覆盖/拒答/引用）+ `must_not_claim`。
- 拒答判定是规则式的，只覆盖显式否定；「书中虽未直接讨论，但结合普遍原理可以看出……」这类"转折式伪拒答"仍需 `must_not_claim` 兜底。
