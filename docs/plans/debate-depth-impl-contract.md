# 辩论深度增强 — 并行开发接口契约（内部）

> 本文件是并行开发的**唯一接口真相源**。所有 agent 严格按此实现，不得擅自改签名/字段名/文件路径。
> 集成与编译由主 agent 统一负责。每个 agent **只允许改自己所有权内的文件**。

---

## 0. 文件所有权（严格遵守，越界即冲突）

### Agent A — 后端核心基础设施
- 新增 `backend/src/main/java/com/kbook/entity/debate/DebateDossier.java`
- 新增 `backend/src/main/java/com/kbook/repository/debate/DebateDossierRepository.java`
- 新增 `backend/src/main/java/com/kbook/dto/debate/DebateDossierVO.java`（含内部静态类 FocalPointVO / ChallengeVO / BattleStateVO）
- 新增 `backend/src/main/java/com/kbook/config/properties/DebateProperties.java`
- 新增 `backend/src/main/java/com/kbook/service/debate/DebateDossierService.java`
- 修改 `backend/src/main/java/com/kbook/constants/ai/DebatePrompts.java`
- 修改 `backend/src/main/java/com/kbook/entity/AiScene.java`
- 修改 `backend/src/main/resources/application.yml`（加开关配置）

### Agent B — 后端接入层（等 A 完成后启动）
- 修改 `backend/src/main/java/com/kbook/service/debate/DebateSpeechService.java`
- 修改 `backend/src/main/java/com/kbook/service/debate/DebateSessionService.java`
- 修改 `backend/src/main/java/com/kbook/service/debate/DebateReportService.java`
- 修改 `backend/src/main/java/com/kbook/service/debate/DebateService.java`
- 修改 `backend/src/main/java/com/kbook/controller/DebateController.java`

### Agent C — 前端
- 修改 `frontend/src/types/debate.ts`
- 修改 `frontend/src/api/debate.ts`
- 修改 `frontend/src/pages/book/debate-session.tsx`
- 新增 `frontend/src/components/debate/BattleMapPanel.tsx`

---

## 1. 后端接口契约（A 实现，B 消费）

### 1.1 实体 DebateDossier

```java
package com.kbook.entity.debate;
@Entity
@Table(name = "debate_dossiers", indexes = {
    @Index(name = "idx_debate_dossier_session_id", columnList = "session_id")
})
public class DebateDossier extends com.kbook.common.entity.BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id", nullable = false, length = 100, unique = true)
    private String sessionId;
    @Column(name = "focal_points", columnDefinition = "TEXT")
    private String focalPoints;   // JSON 数组，FocalPointVO 列表
    @Column(name = "battle_state", columnDefinition = "TEXT")
    private String battleState;   // JSON 对象，BattleStateVO
    @Column(name = "status", length = 20)
    private String status;        // GENERATING / READY / FAILED
    @Override public Long getId() { return id; }
    // Lombok: @Getter @Setter @ToString @Builder @NoArgsConstructor @AllArgsConstructor
}
```

### 1.2 VO（全部放一个文件 DebateDossierVO.java，用内部静态类）

```java
package com.kbook.dto.debate;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DebateDossierVO {
    private String sessionId;
    private String status;                       // GENERATING/READY/FAILED
    private List<FocalPointVO> focalPoints;
    private BattleStateVO battleState;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FocalPointVO {
        private Integer id;
        private String title;
        private String proThesis;
        private String conThesis;
        private String bookAnchor;               // 仅章节定位，不含原文
        private List<String> concepts;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ChallengeVO {
        private String from;                     // positionKey，如 CON_1
        private String text;                     // 尚未回应的质疑（评论性转述）
        private String targetSide;               // PRO / CON
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BattleStateVO {
        private Integer currentFocalPointId;
        private List<ChallengeVO> unansweredChallenges;
        private List<String> brokenPoints;
        private List<String> establishedPoints;
    }
}
```

### 1.3 DebateProperties（开关）

```java
package com.kbook.config.properties;
@Data @Component
@ConfigurationProperties(prefix = "kbook.debate")
public class DebateProperties {
    /** 深度模式总开关；false 时所有发言/报告链路与改造前逐字节等价 */
    private boolean deepEnabled = false;
}
```

`application.yml` 增加：
```yaml
kbook:
  debate:
    deep-enabled: ${BOOK_DEBATE_DEEP_ENABLED:false}
```
（若 yml 顶层已有 `kbook:`，合并进去，不要新建第二个顶层 key。）

### 1.4 DebateDossierService — 公开方法签名（B 严格按此调用）

```java
package com.kbook.service.debate;
@Service
public class DebateDossierService {
    /** 会话创建成功后调用，内部 @Async 生成，不阻塞；任何异常内部吞掉 */
    void generateAsync(Long userId, Long bookId, String sessionId,
                       String topic, String proArgument, String conArgument);

    /** 查询；不存在返回 null，不抛异常 */
    DebateDossierVO getBySessionId(String sessionId);

    /**
     * 构建发言注入文本。
     * @param side PRO/CON
     * @return 格式化文本（含【当前分歧点】【双方论证方向】【书中章节】【战场状态】）；
     *         无 dossier / status != READY / 任何异常 → 返回 null（B 回退旧路径）
     */
    String buildInjectionText(String sessionId, String side);

    /** 发言落库后 @Async 更新战场状态；任何异常内部吞掉 */
    void updateBattleStateAsync(String sessionId, String positionKey,
                                String side, String content);

    /** 报告生成时的「分歧点裁决」文本；无 dossier 返回空串 */
    String buildVerdictText(String sessionId);
}
```

### 1.5 AiScene 新增两个枚举值（插在辩论域末尾）

```java
DEBATE_DOSSIER("辩论论点弹药库", Category.TOOL, false, false),
DEBATE_BATTLEFIELD("辩论战场状态", Category.TOOL, false, false),
```

### 1.6 DebatePrompts 新增常量 + 字数调整（A 独占修改）

新增：
```java
public static final String DEBATE_DOSSIER_SYSTEM_PROMPT = "...";       // 生成 3-5 分歧点 JSON
public static final String DEBATE_BATTLEFIELD_SYSTEM_PROMPT = "...";   // 更新战场状态 JSON
public static final String DEBATE_VERDICT_SYSTEM_PROMPT = "...";       // 分歧点裁决（报告用）
```

字数调整（SYSTEM prompt 与对应 OUTPUT prompt 里的数字都要改）：

| 常量 | 环节 | 新字数 |
|---|---|---|
| DEBATE_OPENING_PROMPT / OUTPUT | OPENING | 250-350 |
| DEBATE_CROSS_EXAM_QUESTIONER_PROMPT / OUTPUT | 质询提问 | 60-100 |
| DEBATE_CROSS_EXAM_ANSWERER_PROMPT / OUTPUT | 质询回答 | 60-100 |
| DEBATE_REBUTTAL_PROMPT / OUTPUT | REBUTTAL | 180-250 |
| DEBATE_FREE_PROMPT / OUTPUT | FREE | 60-120 |
| DEBATE_CLOSING_PROMPT / OUTPUT | CLOSING | 300-450 |

报告 prompt `DEBATE_REPORT_SYSTEM_PROMPT` 章节列表增加一条：
`8. 分歧点裁决 — 逐点说明双方攻防、哪方更有说服力、依据是什么`

**硬约束**：所有涉及书中内容的 prompt 必须写明「禁止输出书中连续 20 字以上的原文，只做概括/评论/转述；章节定位只写章节名或序号」。

### 1.7 API 端点（B 在 Controller 新增）

```
GET /api/debate/sessions/{sessionId}/dossier  →  Result<DebateDossierVO>
```
不存在时返回 `Result.ok(null)`，不抛异常。

---

## 2. 后端接入规格（B 实现）

### 2.1 DebateSpeechService.buildSpeechMessages()
- 注入 `DebateProperties` 与 `DebateDossierService`。
- 开关关闭 → 保持原逻辑（含现有 externalKnowledge）**逐字节不变**。
- 开关打开 → 在现有第 2 步位置：
  - `String injection = dossierService.buildInjectionText(sessionId, side);`
  - 若 `injection != null`：把原 externalKnowledge 段落**替换**为 injection（深度模式不保留外部知识）。
  - 若 `injection == null`：回退到原 externalKnowledge 路径（保证老会话/生成失败不出错）。
- 第 3 步历史注入保持原样，但 injection 文本里已含战场状态，不重复。

### 2.2 DebateSpeechService.streamResponse.onComplete()
- 保存消息、评分之后（不影响主链路）：
  - 开关打开时调 `dossierService.updateBattleStateAsync(sessionId, positionKey, side, content);`
  - 必须放在 try-catch 内，异常只 log，绝不影响 SSE 完成。

### 2.3 DebateSessionService.createSession()
- 保存会话后：
  - 开关打开时调 `dossierService.generateAsync(userId, bookId, sessionId, topic, proArgument, conArgument);`
  - proArgument / conArgument 从 `bookContext` 解析（格式：`正方：xxx\n反方：xxx`），解析失败传空串。
  - 调用必须在 try-catch 内。

### 2.4 DebateReportService
- 构建报告 prompt 时，开关打开且 dossier READY → 追加 `dossierService.buildVerdictText(sessionId)` 到 UserMessage 末尾。
- 失败/关闭时完全保持原 prompt。

### 2.5 DebateService（门面）
- 新增 `getDossier(String sessionId)` 委托给 `dossierService.getBySessionId()`。

### 2.6 DebateController
- 新增 §1.7 端点，通过门面调用。

---

## 3. 前端接口契约（C 实现）

### 3.1 types/debate.ts 新增（字段名与 §1.2 JSON 完全一致）

```ts
export interface DebateDossier {
  sessionId: string
  status: 'GENERATING' | 'READY' | 'FAILED'
  focalPoints: DebateFocalPoint[]
  battleState?: DebateBattleState
}
export interface DebateFocalPoint {
  id: number
  title: string
  proThesis: string
  conThesis: string
  bookAnchor: string
  concepts: string[]
}
export interface DebateChallenge { from: string; text: string; targetSide: string }
export interface DebateBattleState {
  currentFocalPointId: number
  unansweredChallenges: DebateChallenge[]
  brokenPoints: string[]
  establishedPoints: string[]
}
```

### 3.2 api/debate.ts 新增

```ts
export function getDebateDossier(sessionId: string) {
  return request.get<DebateDossier | null>(`/debate/sessions/${sessionId}/dossier`)
}
```

### 3.3 发言序列改造（debate-session.tsx）

保持 5 个 phase 与后端 `advanceRound` 不变，只改前端 turn 序列：

- `CROSS_EXAM`：改为按分歧点的追问链。用 `CROSS_EXAM_CHAINS`（新常量）替代原 4-turn 数组：
  - 取 dossier 前 2 个分歧点，每个点打 1 组「质询 3 turn + 回答 3 turn」的追问链。
  - 无 dossier（老会话/开关关）→ 回退原 `CROSS_EXAM_ORDER`。
  - 每个追问的 `extraContent` 带上「当前分歧点标题 + 对方上一条回答」。
- `REBUTTAL_ORDER`：`['PRO_2', 'CON_2', 'PRO_3', 'CON_3']`（三辩登场）；无 dossier 时也可用此新序列。
- `MAX_FREE_EXCHANGES`：12 → 16；按分歧点分 block，每点 3-4 轮；发言 `extraContent` 带当前焦点。
- `CLOSING_ORDER` 不变。

**闭包陷阱硬规则（AGENTS.md A2）**：所有计数器用 `let` 就地 `++`，禁止读闭包 state 的 `.length`；所有回调链最前面检查 `isChainActiveRef.current`。

### 3.4 战场地图组件（新增 BattleMapPanel.tsx）

- Props: `{ dossier: DebateDossier; compact?: boolean }`
- 展示：分歧点列表（当前焦点高亮）、未回应质疑、被击穿论点、已确立论点。
- 样式跟随项目 Tailwind 主题，暗色适配。
- 在 `debate-session.tsx` 的 PC 侧栏与移动端 Sheet 各挂一处（跟随现有 `useOverlay`/`useIsMobile` 模式）。
- dossier 为 null 或 GENERATING/FAILED → 整块不渲染，流程不受影响。

### 3.5 老会话兼容
- `getDebateDossier` 返回 null 或失败 → 静默，不弹 toast，不阻断发言流程。
- 辩论流程的推进完全不依赖 dossier 是否加载成功。

---

## 4. 全局质量约束（所有 agent）

1. **开关默认 false**：`BookDeep=false` 时所有行为与改造前逐字节等价（含外部知识路径）。
2. **任何 LLM 调用、JSON 解析、DB 读写都必须 try-catch**，失败降级不影响辩论主流程。
3. **合规**：dossier/battlefield/verdict 的所有 prompt 必须含「禁止连续 20 字原文」约束；bookAnchor 只存章节名。
4. **不改数据库 DDL**（项目 `ddl-auto: update` 自动建表）。
5. **不改 `debate_sessions` 表结构**。
6. 每个 agent 完成后自查：Java 语法正确 / TypeScript 类型正确；不要运行完整构建（集成由主 agent 做）。
7. 遇到契约未覆盖的疑问：自行决断，采用最保守方案（回退旧行为），并在最终汇报里说明。

---

## 5. 集成验收（主 agent 负责）

- `mvn test`（基线 723/723）
- `npm run build`
- 开关关闭路径回归（逐字节等价）
- Git 工作区检查：只出现契约内文件
