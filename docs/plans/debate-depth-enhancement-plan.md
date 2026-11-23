# 奇葩说辩论深度增强方案

> 目标：把「几下就结束、观点浮在表面」的图书辩论，改造成围绕书中核心分歧层层深挖的思辨引擎。
> 状态：**已实施**（2026-11-23）。P0-P4 全部落地，功能默认上线、无条件生效（原灰度开关已移除，见 §10.2）。
> 实施契约见 `debate-depth-impl-contract.md`。验证：后端 729/729 测试通过，`mvn compile` / `npm run build` 零错误。

---

## 1. 问题诊断（基于代码核查，非猜测）

### 1.1 书的观点根本没进发言 —— 「浅」的根因

| 环节 | 代码事实 |
|---|---|
| 辩题生成 | `DebateTopicService.buildBookInfoForTopic():173-209` 用 `compressedSummary`/`toc`/`description` 生成辩题，能产出 `proArgument`/`conArgument` |
| 会话创建 | 前端 `debate.tsx:281-283` 把正反方观点拼成 `bookContext` 传入；后端 `DebateSessionService.createSession():105` 落库到 `debate_sessions.book_context` |
| **发言** | **`DebateSpeechService.buildSpeechMessages():556-625` 完全没读 `session.getBookContext()`**。第 2 步只放 `topic` + `sideFull` + `generateExternalKnowledgeForDebate()` |
| 外部知识 | `ExternalKnowledgeGenerator.generateForDebate():65-93` 只拿辩题 + 立场 + 人设前 200 字，让 LLM 凭通用知识现编，与书无关 |

结果：辩题来自书，但辩论过程脱离书。辩手靠常识和套话打，三辩四辩没戏份，观点自然浮。

> 附：`generateForDebate(topic, stance, personalityContext)` 对应形参 `side, stance`，参数名错位但渲染语义恰好正确（立场=支持/反对、视角=人设），**不是功能 bug**。建议后续重命名为 `knowledgeSide/personalityContext` 防误改。

### 1.2 环节是「一次性」的，没有深挖结构

`debate-session.tsx:59-87` 的发言序列：

| 环节 | 实际发言 | 字数限制 | 问题 |
|---|---|---|---|
| OPENING | 一辩 2 人各 1 次 | 150-250 | 框架搭完就过 |
| CROSS_EXAM | 2 对 Q&A，共 4 turn | 30-60 | 一问一答即止，无追问链 |
| REBUTTAL | 二辩 2 人各 1 次 | 120-180 | 只拆一次，不针对质询漏洞 |
| FREE | 最多 12 次短句 | 20-80 | 有「回应上一位」约束，但无焦点，易散 |
| CLOSING | 四辩 2 人各 1 次 | 200-350 | 有升华无裁决 |

全场约 20 次发言，看似不少，但每个分歧点只被碰一次，没有「澄清→逼问→归谬→回应」的递进。

### 1.3 席位浪费

`OPENING_ORDER` 只含一辩，`CROSS_EXAM_ORDER` 用二辩+一辩，`REBUTTAL_ORDER` 用二辩，`CLOSING_ORDER` 用四辩。**三辩（PRO_3/CON_3）全程不发言**，四辩只在结辩说一次。用户精心配的 8 个性格，一半是摆设。

### 1.4 没有跨轮记忆

`buildSpeechMessages()` 把全部历史平铺成 `roleName：content`，不做结构化提炼。后发言者抓不住「哪个质疑还没被回应」，导致各说各话、重复立论。

---

## 2. 设计目标与原则

1. **深，而不是长**：不是简单增加发言数，而是每个分歧点有递进关系。
2. **基于书，但不复述书**：沿用项目合规底线——只输出评论性转述、概念、章节定位，不输出原文连续片段。
3. **全链路灰度**：新增总开关 `BOOK_DEBATE_DEEP_ENABLED`，默认 `false`；关闭时提示词与流程逐字节回旧版。
4. **向后兼容**：老会话无 dossier 时自动回退旧发言路径；`debate_sessions` 表结构不动。
5. **可观测**：每场辩论产出结构化「分歧点裁决」，报告与前端都能消费。

---

## 3. 整体设计

```
创建会话
   │
   ├─(新增) DebateDossierService 异步生成「论点弹药库」
   │        输入: topic + pro/conArgument + compressedSummary/toc/description
   │        输出: 3-5 个分歧点 → debate_dossiers 表
   │
发言（每一 turn）
   │
   ├─ 辩题 + 立场
   ├─(替换) 论点弹药库：当前分歧点的正方/反方可用论点 + 书中章节定位
   ├─(新增) 战场状态：未回应的质疑 / 被击穿的论点 / 已确立的论点
   ├─ 对话历史（保留，但压缩为最近 N 条 + 战场状态摘要）
   ├─ 角色设定
   └─ 输出要求
```

### 3.1 新增数据表 `debate_dossiers`

```sql
CREATE TABLE debate_dossiers (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id    VARCHAR(100) NOT NULL UNIQUE,
  focal_points  TEXT,          -- JSON：分歧点数组
  battle_state  TEXT,          -- JSON：动态战场状态，每轮发言后更新
  status        VARCHAR(20) DEFAULT 'GENERATING',  -- GENERATING/READY/FAILED
  created_at    DATETIME,
  updated_at    DATETIME
);
```

`focal_points` JSON 结构：

```json
[
  {
    "id": 1,
    "title": "分歧点标题（争议焦点）",
    "proThesis": "正方在该点的核心主张（评论性转述）",
    "conThesis": "反方在该点的核心主张（评论性转述）",
    "bookAnchor": "书中对应章节定位，如「第3章 论XX」——只有定位，不摘原文",
    "concepts": ["关键概念1", "关键概念2"]
  }
]
```

`battle_state` JSON 结构：

```json
{
  "currentFocalPointId": 2,
  "unansweredChallenges": [
    { "from": "CON_1", "text": "对方尚未回应：...", "targetSide": "PRO" }
  ],
  "brokenPoints": ["正方第2论点被击穿：..."],
  "establishedPoints": ["反方已确立：..."]
}
```

### 3.2 论点弹药库生成（`DebateDossierService`）

- 触发：`createSession` 成功后，通过 `@Async` 生成，不阻塞用户进入会话页。
- 输入：`topic` + `proArgument`/`conArgument` + `book.compressedSummary`（兜底 `chapterSummary`）+ `toc`。
- 输出要求写入新 prompt `DEBATE_DOSSIER_SYSTEM_PROMPT`：
  - 提炼 3-5 个真正的分歧点，而非罗列书中观点
  - 每个分歧点给出双方可用的**论证方向**（不是结论、不是原文）
  - 每个分歧点标注书中章节定位（章节名/序号），便于用户回书核对
  - **硬约束**：禁止输出书中连续 20 字以上的原文；只做概括、评论、转述
- 新 `AiScene.DEBATE_DOSSIER`，可在后台独立配模型。
- 失败降级：`status=FAILED`，发言时回退到现有 externalKnowledge 路径，辩论照常进行。

### 3.3 战场状态维护（`DebateBattlefieldService`）

- 每次发言落库后（`DebateSpeechService.streamResponse.onComplete()`）异步更新。
- 输入：本轮发言 + 当前 battle_state + 当前分歧点。
- 输出：新增/消解的 `unansweredChallenges`、`brokenPoints`、`establishedPoints`，推进 `currentFocalPointId`。
- 实现可选两档：
  - **P1 用 LLM**（推荐）：小模型 + `/no_think`，结构化 JSON 输出，带解析失败兜底。
  - **P2 降级规则版**：按「被质询方是否回应」关键词判断，零成本但精度低。
- 新 `AiScene.DEBATE_BATTLEFIELD`。

### 3.4 发言 prompt 改造

`buildSpeechMessages()` 第 2、3 步改造：

```
第 2 步（替换外部知识）：
  【本场辩题】...
  【你的立场】...
  【当前分歧点】{focalPoint.title}
  【该点正方论证方向】{proThesis}   ← 仅当己方为正方时强调
  【该点反方论证方向】{conThesis}
  【书中对应章节】{bookAnchor}
  【关键概念】{concepts}

第 3 步（替换平铺历史）：
  【战场状态】
  尚未回应的质疑：{unansweredChallenges}
  已被击穿的论点：{brokenPoints}
  已确立的论点：{establishedPoints}
  【最近 N 条发言】{最近 8 条}
  （并追加硬指令：发言必须先回应尚未回应的质疑，再展开己方论证）
```

字数限制调整（在 `DebatePrompts.java`）：

| 环节 | 现值 | 建议值 | 理由 |
|---|---|---|---|
| OPENING | 150-250 | 250-350 | 框架要立住 |
| CROSS_EXAM | 30-60 | 单问 60-100，追问链 3 turn | 有递进空间 |
| REBUTTAL | 120-180 | 180-250 | 针对性质疑要拆透 |
| FREE | 20-80 | 60-120 | 不再是纯短句 |
| CLOSING | 200-350 | 300-450 | 含分歧点裁决 |

### 3.5 环节序列改造（席位盘活）

前端 `debate-session.tsx`，保持 5 个 phase 不变（后端 `advanceRound` 不用改），只改每环节的 turn 序列：

| 环节 | 新序列 | 三辩/四辩安排 |
|---|---|---|
| OPENING | PRO_1 → CON_1 | 不变 |
| CROSS_EXAM | 对 2 个分歧点各打一组追问链：质询方连问 3 turn（澄清→逼问→归谬），被质询方每问必答 | PRO_2/CON_2 主问，一辩防守 |
| REBUTTAL | PRO_2 → CON_2 → PRO_3 → CON_3 | **三辩登场**，二辩拆逻辑、三辩补事实层 |
| FREE | 按分歧点分 block，每点 3-4 轮，强制回应上一位 + 回应未决质疑 | 三辩为主力，四辩参与 |
| CLOSING | CON_4 → PRO_4 | 四辩结辩，新增「分歧点裁决」要求 |

新增常量：`CROSS_EXAM_CHAINS`、`FREE_FOCAL_BLOCKS`。`MAX_FREE_EXCHANGES` 从 12 提到 16。

### 3.6 结果端：分歧点裁决

- `DebateReportService.generateReport()` 的 prompt（`DebatePrompts.java:257-270`）新增章节：
  「分歧点裁决 — 逐点说明双方在该点的攻防、谁更有说服力、依据是什么」
- `ReportPanel.tsx` 按 `##` 分节渲染，天然兼容，无需改解析。
- 前端在结果页新增「战场地图」卡片，展示 3-5 个分歧点 + 裁决结论（数据来自 `debate_dossiers`）。

---

## 4. 文件改动清单

### 后端

| 文件 | 改动 |
|---|---|
| `entity/debate/DebateDossier.java` | 新增实体 |
| `repository/debate/DebateDossierRepository.java` | 新增 |
| `service/debate/DebateDossierService.java` | 新增：弹药库生成 + 战场状态维护 |
| `constants/ai/DebatePrompts.java` | 新增 2 个 prompt；调整 5 个环节的字数要求；报告新增章节 |
| `service/debate/DebateSpeechService.java` | `buildSpeechMessages()` 注入弹药库 + 战场状态；`streamResponse.onComplete()` 触发状态更新 |
| `service/debate/DebateSessionService.java` | `createSession()` 成功后触发异步 dossier 生成 |
| `service/debate/DebateReportService.java` | 报告 prompt 接入 dossier 裁决 |
| `dto/debate/DebateDossierVO.java` | 新增，供前端渲染战场地图 |
| `controller/DebateController.java` | 新增 `GET /api/debate/sessions/{sessionId}/dossier` |
| `entity/AiScene.java` | 新增 `DEBATE_DOSSIER` / `DEBATE_BATTLEFIELD` |
| `config/...` | 新增开关 `BOOK_DEBATE_DEEP_ENABLED`（默认 false） |

### 前端

| 文件 | 改动 |
|---|---|
| `pages/book/debate-session.tsx` | 发言序列改造；字数/节奏提示；战场地图卡片 |
| `api/debate.ts` | 新增 `getDebateDossier()` |
| `types/debate.ts` | 新增 `DebateDossier` / `FocalPoint` / `BattleState` 类型 |
| `components/debate/ReportPanel.tsx` | 结果页接入分歧点裁决（可选，报告已含该章节） |

---

## 5. 分阶段实施

| 阶段 | 内容 | 交付判据 | 预估 |
|---|---|---|---|
| **P0** | 论点弹药库：dossier 表 + 生成服务 + 发言注入 | 辩手发言中能见到书中章节定位与概念，不再靠常识 | 1-2 天 |
| **P1** | 战场状态：未决质疑追踪 + 发言前硬约束 | 后发言者确实回应前面的质疑，不再各说各话 | 1-2 天 |
| **P2** | 环节序列改造 + 席位盘活 | 三辩有实质发言；质询有追问链；总发言 ~35-40 | 1-2 天 |
| **P3** | 结果端分歧点裁决 + 战场地图卡片 | 报告逐点裁决，前端可见 | 1 天 |
| **P4** | 灰度开关 + 回归 + 文档 | 开关关闭逐字节回旧版；测试全绿 | 0.5 天 |

P0 是投入产出比最高的一步：只改注入源，就能解决「辩论脱离书」这个根因。建议先做 P0+P1 验证效果，再决定 P2 是否全量。

---

## 6. 合规校验

- dossier prompt 硬约束：禁止连续 20 字以上原文，只做概括/评论/转述。
- 落库前复用 `AnswerOverlapGuard` 对 dossier 文本做重合检测（当前只覆盖回答，需扩展一条路径）。
- `bookAnchor` 只存章节名/序号，不存章节内容。
- 关闭开关后所有新 prompt、新字段均不生效，回退到当前行为。

---

## 7. 验收与效果度量

| 指标 | 现状基线 | 目标 |
|---|---|---|
| 单场有效发言次数 | ~20 | ~35-40 |
| 含书中章节定位的发言占比 | 0% | ≥ 60% |
| 回应上一轮未决质疑的发言占比 | 无统计 | ≥ 70% |
| 三辩/四辩发言占比 | 0% / 单次 | 各 ≥ 3 次 |
| 报告含逐点裁决 | 无 | 100% |

回归：`mvn test`（当前 723/723）、`npm run build`。灰度开关默认关闭，线上先小流量验证。

---

## 8. 待确认的产品决策

1. **深挖强度**：P2 的追问链是「每个分歧点 3 turn」还是「全场只深挖 1-2 个焦点」？前者内容多但时长长，后者更聚焦。
2. **是否需要用户投票**：本次方案不包含任何互动玩法（按需求澄清）。若后续要加，战场地图是天然入口。
3. **外部知识是否保留**：建议保留为补充，但降权——书中论点优先，外部知识只用于类比/反例。
4. **老会话兼容**：老会话无 dossier，展示时隐藏战场地图即可，不回溯生成。

---

## 9. 更正值

上一轮口头提到的「传参把立场和视角传反了」：**不成立**。`ExternalKnowledgeGenerator.generateForDebate(topic, stance, personalityContext)` 对应形参 `(topic, side, stance)`，参数名错位但渲染出的语义（立场=支持/反对、辩手视角=人设）是正确的。仅建议重命名以提升可读性，不作为 bug 修复。

---

## 10. 实施后流程 review 与修复（2026-11-23）

首轮实现编译测试通过，但跨模块时序上存在 4 处流程缺陷。已全部修复：

1. **战场状态并发覆盖（致命）**：`updateBattleStateAsync` 原本每轮都「读旧状态 → 调 LLM → 写回」，500ms 发言间隔下多个异步任务并发读同一快照，后写覆盖先写，未决质疑/被击穿论点无法累积；下一轮注入也读到滞后状态。修复：`DebateDossierService` 增加 per-session `CompletableFuture` 串行链，写回前强制保留当前焦点；`buildInjectionText` 读取前先 `awaitPendingUpdates(15s)`。

2. **首场辩论必然走回退路径（必现）**：弹药库异步生成，而前端只在进页面时拉一次 dossier，质询开始时仍是 GENERATING → 追问链回退成 4-turn。修复：前端增加 dossier 轮询（2s 间隔，最多 60s），READY/FAILED 后停止。

3. **焦点双轨冲突**：前端按发言序号算焦点塞进 `extraContent`，后端 LLM 又自行决定 `currentFocalPointId`，同一轮会出现两个矛盾的【当前分歧点】。修复：焦点真相源统一为**前端发言序列显式指定的焦点 id**。前端在质询/自由辩上下文带 `【焦点ID】N`；后端 `focusFocalPoint()` 在注入前切到该焦点，战场更新时不再允许 LLM 改焦点（`DEBATE_BATTLEFIELD_SYSTEM_PROMPT` 同步改规则 1）。这偏离了 review 时「以后端 battleState 为唯一真相」的初步建议，因为追问链按分歧点分组、自由辩按 block 分组，焦点必须由发言序列驱动才能自洽。

4. **续播与断连的边角问题**：CROSS_EXAM 链模式按 session 锁进 sessionStorage，避免 dossier 中途 READY 导致链长 4→12 时续播游标错位；`onConnectionClosed` 补上评分与战场更新，断连保存的部分内容不再成为「战场看不到的发言」。

5. **报告裁决对齐**：`DebateReportService` 追加裁决前 `awaitPendingUpdates(60s)`，避免最后几轮状态未写回就下结论。

验证：`mvn test` **729/729 通过**，`tsc -b` + `vite build` **零错误**。（原「开关关闭逐字节回旧版」的承诺已在 §10.2 随开关一并移除。）

### 10.1 二次回归审查（同日，用户要求确认无新 bug）

又补了 4 处健壮性与展示问题：

- **链配置锁得不彻底**：只锁 "deep"/"legacy" 模式标记时，dossier 中途不可用仍会让链长在 4↔12 间跳。改为把**整份 chains JSON** 锁进 sessionStorage，`useChains` 改用 `chains.some(t => !!t.focalTitle)` 判定（JSON.parse 后引用相等失效）。
- **战场更新链可能永久卡死**：LLM 调用挂起会让 per-session 链永不完成，后续所有发言的 `focusFocalPoint`/注入都卡在等待上。给链加 `orTimeout(120s)`，超时异常完成并清理。
- **战场状态被整批清空（真 bug）**：`hasSignal` 把 `currentFocalPointId` 算作有效信号，而战场 prompt 要求原样回传该字段，一旦 LLM 只带回 id 而三个列表解析为空，sanitize 会把累积的质疑/击穿/确立点全清空。改为只看三个列表；战场 prompt 同时要求「完整保留输入中仍未回应的质疑」「保留仍然有效的击穿/确立点」。
- **战场地图打开后是死数据**：前端只在进页面拉一次 dossier，质疑/击穿/确立列表永远是初始空态。每轮发言落库后延迟 1.5s 刷新，用序号 ref 丢弃迟到响应，防止快速连续发言时旧快照覆盖新状态。
- 顺带修正回答方收到的上下文标签：上一轮拿到的是质询方提问，标签从「对方上一条回答」改为「对方质询问题」。

**已知限制（非 bug）**：分歧点之间的主持人串场是前端预写、不落库，刷新后消失（不影响流程与观点）；战场数据因后端异步写回会滞后至多一轮；老会话无 dossier 时前端仍会轮询 60 秒才停（每 2 秒一次，无副作用）。

二次验证：`mvn test` **729/729**，`tsc -b` + `vite build` **零错误**。

### 10.2 移除灰度开关，功能默认上线（同日，用户决策）

用户明确要求「别加开关，功能就是默认上线的」。辩论深度增强是核心卖点，藏在默认关闭的开关后等于白做。执行：

- 删除 `config/properties/DebateProperties.java` 与 `application.yml` 的 `kbook.debate.deep-enabled` 配置。
- 4 个服务类（`DebateDossierService` / `DebateSpeechService` / `DebateSessionService` / `DebateReportService`）移除 `DebateProperties` 注入与全部 `isDeepEnabled()` 判断，保留深度分支。
- `DebateSessionService` 的测试用 6 参数构造器保留，dossierService 传 null 并加 null 检查（测试环境无弹药库服务，跳过生成不影响被测逻辑）。
- 只是**移除开关**，不改变深度逻辑本身：dossier 生成失败/无 dossier 时仍回退外部知识，不影响发言主链路。

验证：`mvn test` 729/729 通过。已用 `update.sh` 发布到生产（仅换 jar），容器 healthy，`/api/health` UP。

> 注：方案前文（§4.2、§7、§8、§9 表格）描述的灰度开关为**初始设计**，最终以本节为准——开关已不存在，功能无条件生效。
