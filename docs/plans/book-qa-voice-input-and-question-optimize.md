# 图书问答：语音输入（ASR）+ 提问优化 设计方案

> 状态：**v1 设计稿，待评审**
> 范围：图书问答 Sheet（`frontend/src/components/book/BookChatSheet.tsx`）的输入区
> 目标：降低提问成本，让用户「说一句」就能问；同时用「优化提问」把口语转成清晰、可检索的问题
> 不改动：现有 SSE 问答协议、RAG 链路、对话历史结构

---

## 0. 结论先行

**建议两个都做，但定位不同：**

| 能力 | 建议 | 理由 |
|---|---|---|
| 语音输入（ASR） | **P0 做，纯前端** | 用浏览器 `SpeechRecognition` 即可，零后端成本、零新依赖；不影响现有问答链路 |
| 输入框内「优化提问」图标 | **P1 做，用户显式点击触发** | 语音识别天然带口语、重复、缺标点、指代不清；优化按钮能把「嗯……那个主角为啥要跑」变成「主人公最终选择离开的原因是什么？」。**不建议自动改写**，避免用户失控感和额外 token 消耗 |

**一句话架构：**

```
语音识别：SpeechRecognition → useSpeechRecognition → 文本拼进 textarea → 走现有 handleSend
提问优化：输入框 ✨ → POST /chat/optimize-question → AI 场景 BOOK_QA_QUESTION_OPTIMIZE → 回填 textarea（不自动发送）
```

**最重要的风险（必须提前认知）：**

1. **Chrome 桌面版在国内网络下大概率识别失败**——Chrome 的 Web Speech 识别走 Google 在线服务，会报 `network`。Edge（微软服务）、Safari（Apple 服务）通常可用。第一版要明确提示「换 Edge」，中长期应规划云端 ASR 兜底。
2. **Android App（Capacitor WebView）不支持 `SpeechRecognition`**——WebView 不等于 Chrome 浏览器。Android 端需要 P2 原生插件或云端 ASR，第一版直接隐藏麦克风按钮。
3. **隐私**：音频会发送给浏览器厂商（Google/Microsoft/Apple），不经过 KBook 服务器。需要同步隐私说明文案。

---

## 1. 现状定位

### 1.1 输入区（唯一改动点）

| 位置 | 内容 |
|---|---|
| `BookChatSheet.tsx:795-875` | 底部输入区容器 |
| `BookChatSheet.tsx:798-840` | 对话风格切换按钮（「深度/随和…」） |
| `BookChatSheet.tsx:841-862` | `<textarea>`，受控 `input`，自动增高，Enter 发送 |
| `BookChatSheet.tsx:863-873` | 发送按钮，`loading` 时转圈 |
| `BookChatSheet.tsx:229-391` | `handleSend`，现有 SSE 问答入口 |
| `BookChatSheet.tsx:195-211` | `handleClose`，关闭时 abort SSE、停止 TTS |
| `BookChatSheet.tsx:137-145` | 卸载时 abort + `ttsService.dispose()` |
| `BookChatSheet.tsx:393-442` | `handleToggleSpeak`，朗读回答（TTS） |

### 1.2 可复用的既有能力

| 能力 | 位置 | 说明 |
|---|---|---|
| TTS 停止 | `utils/tts.ts` / `store/tts.ts` | 开始录音前必须停掉，避免扬声器声音被麦克风收进去形成回声 |
| Toast | `@/components/ui/sonner`（`main.tsx:115` 已挂 Toaster） | 权限/网络/优化结果提示 |
| AI 场景路由 | `AiScene.java` + `ChatModelManager.callAiForScene`（`ChatModelManager.java:131`） | 新增场景即可，管理后台自动渲染 |
| 非流式 AI 调用范例 | `BookChatService.generateFollowUpQuestions`（`:1488-1526`） | 提问优化可照此实现 |
| 提示词常量 | `constants/AiPromptConstants.java` | 新增一段系统提示词 |
| 输入清洗 | `CommonUtils.sanitizeAiInput`（`BookChatController.java:68`） | 优化接口沿用 |

### 1.3 当前没有的东西

- 没有任何 `SpeechRecognition` / `getUserMedia` 代码（`grep` 确认）。
- 没有麦克风权限、录音、原生 STT 相关依赖。
- 没有「改写/润色用户输入」的接口或 AI 场景。

---

## 2. 方案总览

### 2.1 两个能力正交

```
┌──────────────────────────── 输入框 ────────────────────────────┐
│  [深度 ▾]  ┌─────────────────────────────────────✨──🎤──┐     │
│            │ 聊聊《……》                                  │     │
│            └────────────────────────────────────────────────┘     │
│                                                         [发送]   │
└──────────────────────────────────────────────────────────────────┘
      ✨ = 优化提问（P1，有文字才出现）
      🎤 = 语音输入（P0，环境支持才出现）
```

- 两者都不改变「用户确认后发送」的交互：**语音不自动发送，优化不自动发送**。
- 语音识别只负责「把声音变成文字」，不碰后端。
- 优化提问只负责「把文字改得更清楚」，走普通 REST，不流式。

### 2.2 为什么第一版不用云端 ASR

| 方案 | 成本 | 接入工作量 | 国内可用性 | 隐私 | 结论 |
|---|---|---|---|---|---|
| 浏览器 Web Speech API | 0 | 0.5~1 天 | Chrome 差 / Edge 好 / Safari 好 | 音频给浏览器厂商 | **P0 采用** |
| 讯飞 / 阿里 / 腾讯实时 ASR | 按量 | 3~5 天（鉴权、WebSocket、音频采集） | 好 | 可控 | P2 兜底 |
| Android 原生 SpeechRecognizer 插件 | 0 | 2~3 天（Capacitor 插件 + 权限） | 取决于系统 | 系统厂商 | P2 |

第一版用浏览器能力验证「用户到底用不用语音提问」；`useSpeechRecognition` 的接口设计成 provider 无关，后续换云端只替换内部实现。

---

## 3. 语音输入设计（P0）

### 3.1 平台能力矩阵

| 环境 | 支持 | 说明 |
|---|---|---|
| Chrome / Edge 桌面 | 条件支持 | 需 HTTPS（localhost 例外）；Chrome 国内网络常报 `network`，Edge 更稳 |
| Safari macOS / iOS 14.5+ | 支持 | `webkitSpeechRecognition`；系统可能提前结束会话，无法稳定连续听 |
| Android Chrome 浏览器 | 支持 | 注意：不是 KBook App |
| Firefox | 不支持 | 按钮隐藏 |
| KBook Android App（Capacitor WebView） | 不支持 | WebView 未实现 `SpeechRecognition`；第一版隐藏，P2 原生插件 |
| KBook iOS App（WKWebView） | 需实测 | 不同 iOS 版本行为不一致；不支持则隐藏 |
| 微信内置浏览器 | 不支持/不稳定 | 按不支持处理 |

能力探测：

```ts
const Ctor = window.SpeechRecognition ?? window.webkitSpeechRecognition
const supported = !!Ctor && window.isSecureContext
```

> 注意：`isSecureContext` 在 `http://localhost` 和 Capacitor 的 `http://localhost` 下为 true，但这不代表 WebView 实现了识别 API。两个条件必须同时满足。

### 3.2 状态机

```
unsupported ──(环境不支持)── 按钮隐藏
idle ──点击/调用 start()──> requesting ──授权成功──> listening
                                   └──授权失败──> error ──提示后──> idle
listening ──点击停止 / 发送 / 关闭 / 手动输入 / 超时──> idle
listening ──onerror──> error ──提示后──> idle
```

- `requesting`：已调 `recognition.start()`，等待授权/首结果，按钮显示加载态。
- `listening`：正在收音，麦克风按钮高亮 + 脉冲，输入框实时显示中间结果。
- 不做自动重启（P0）。`onend` 即回到 `idle`，文字保留；用户想继续再点一次。这样避免 `no-speech` 无限重启、iOS 重复授权、录音停不下来等问题。

### 3.3 Hook 设计

新增 `frontend/src/hooks/useSpeechRecognition.ts`：

```ts
export type SpeechRecognitionStatus =
  | 'idle'
  | 'requesting'
  | 'listening'
  | 'error'
  | 'unsupported'

export interface UseSpeechRecognitionOptions {
  lang?: string                    // 默认 'zh-CN'
  maxDurationMs?: number           // 默认 120_000，硬停止
  onFinalText?: (text: string) => void
  onError?: (code: SpeechRecognitionErrorCode) => void
}

export interface UseSpeechRecognitionResult {
  status: SpeechRecognitionStatus
  supported: boolean
  interimText: string              // 当前未定稿的实时文本
  finalText: string                // 本次会话已定稿文本
  errorCode: SpeechRecognitionErrorCode | null
  start: () => void
  stop: () => void
  toggle: () => void
  reset: () => void
}
```

内部要点：

- 惰性创建 `recognition`，`continuous = true`、`interimResults = true`、`lang = 'zh-CN'`。
- **每次 start 生成一个 `sessionToken`**；所有回调先校验 token，避免停止/重开后旧回调把文字写回来。
- `onresult` 用「全量重建」而不是增量拼接：
  ```ts
  let final = ''
  let interim = ''
  for (let i = 0; i < event.results.length; i++) {
    const r = event.results[i]
    if (r.isFinal) final += r[0].transcript
    else interim += r[0].transcript
  }
  ```
- `onend` / `onerror` 关闭时清掉 120s 定时器。
- `stop()` 调 `recognition.stop()`；`abort()` 场景（关闭 Sheet、卸载）直接 `recognition.abort()`，不触发额外提示。
- 组件卸载时 `abort()`，避免麦克风常驻。

### 3.4 文本插入策略

组件侧维护三个 ref：

```ts
const voiceBaseRef = useRef('')        // 开始录音时的原始文本
const voiceCursorRef = useRef(0)       // 开始录音时的光标位置
const voiceSessionTextRef = useRef('') // 本次语音累计文本（final + interim）
```

每次识别结果到达：

```ts
const text = final + interim
voiceSessionTextRef.current = text
setInput(
  voiceBaseRef.current.slice(0, voiceCursorRef.current) +
  text +
  voiceBaseRef.current.slice(voiceCursorRef.current)
)
```

规则：

1. 光标在中间时，语音插在光标处，不覆盖已有文字。
2. 中间结果反复覆盖同一段，不重复追加；定稿后继续追加。
3. **监听期间用户手动打字（textarea `onChange`）→ 立即 stop ASR**，保留当前已合成文本。这样避免「基线过期」导致文字错位，逻辑最简单、行为可预期。
4. 停止后不清理已写入的文字，用户可继续编辑。
5. 软键盘/拼音输入不受影响：ASR 只走 `setInput`，不模拟键盘事件。

### 3.5 生命周期：必须停止 ASR 的时机

| 时机 | 处理 | 原因 |
|---|---|---|
| 用户再点麦克风 | `stop()`，回到 idle | 手动结束 |
| 点击发送 | 先 `stop()`，再用当前 `input` 发送 | 防止发送后还有尾包文字落进输入框 |
| 关闭 Sheet（`handleClose`） | `abort()` | 麦克风不能后台常驻 |
| 新建对话（`handleNewChat`） | `abort()` | 同上 |
| 切换历史会话 | `abort()` + 清空输入 | 文本属于上一个上下文 |
| 组件卸载 | `abort()` | 路由切换/页面退出 |
| 开始 TTS 朗读 | `abort()` ASR | 防止扬声器回声被识别 |
| 开始 ASR | 先 `ttsService.cancel()` + `setSpeakingId(null)` | 同上 |
| `loading === true` | 隐藏麦克风/禁用 | 输入框此时本就 `disabled` |
| 120s 超时 | `stop()` + toast | 防止忘记关闭 |

### 3.6 错误映射

| `error` | 文案 | 处理 |
|---|---|---|
| `not-allowed` / `service-not-allowed` | 麦克风权限被拒绝，请在浏览器设置中允许后重试 | 停止，不自动重试 |
| `audio-capture` | 未检测到麦克风设备 | 停止 |
| `network` | 语音识别服务网络异常。Chrome 的识别依赖在线服务，建议改用 Edge 浏览器 | 停止，保留已识别文字 |
| `no-speech` | 没有听到声音，请靠近麦克风再试 | 停止，保留已有文字 |
| `aborted` | 不提示 | 静默停止 |
| `language-not-supported` | 当前浏览器不支持中文语音识别 | 停止，本会话隐藏按钮 |
| 其他 | 语音识别失败，请重试 | 停止 |

> 国内 Chrome 的 `network` 是高频路径，文案里必须给出「换 Edge」的可执行建议，而不是只说失败。

### 3.7 UI 设计

输入区结构改为：

```tsx
<div className="flex items-stretch gap-2">
  <风格按钮 />
  <div className="relative flex-1">
    <textarea className="w-full ... pr-[72px]" />
    <div className="absolute right-1.5 bottom-1.5 flex items-center gap-0.5">
      {showOptimize && <OptimizeButton />}
      {speech.supported && <VoiceInputButton />}
    </div>
  </div>
  <发送按钮 />
</div>
```

- 图标按钮 `h-7 w-7`（28px），放在 textarea 内部右侧；textarea 增加右内边距，避免文字被图标压住。
- 有文字时才显示 ✨；环境支持且非 loading 时才显示 🎤。**不支持时直接隐藏**，不展示灰色禁用图标（避免用户反复点击）。
- `listening` 状态：
  - 麦克风按钮：`bg-primary text-primary-foreground` 或 `bg-destructive/10 text-destructive`，加 `motion-safe:animate-pulse` 光环。
  - 输入框上方出现一行状态：`正在聆听，点麦克风结束`。
  - 输入框实时显示中间结果，不自动滚动、不抢焦点。
- `requesting`：按钮内显示 `Loader2` 转圈，状态行显示「正在请求麦克风权限…」。
- 无障碍：
  - `type="button"`、`aria-label`（开始/结束语音输入）、`aria-pressed={listening}`、`title`。
  - 遵循 `prefers-reduced-motion`，脉冲动画用 `motion-safe:` 前缀。
- 移动端注意：底部输入区已有「风格按钮 + 发送按钮」，两个 28px 图标放进 textarea 内，不额外占横向空间；`pb-safe-bottom` 保持不变。

### 3.8 与现有逻辑的衔接

- `handleSend` 不改签名；发送前调用 `speech.stop()`。
- `handleSend` 读取 `input`（ASR 已通过 `setInput` 同步到 state），不需要额外参数。
- 语音不会触发 `manual=false`；它仍是用户主动发送，保持 `manual=true`，不影响现有「强/弱信号」逻辑（`BookChatController.java:54`）。
- 不做「说完自动发送」：识别错误、歧义、环境噪音都可能导致误发，必须让用户看一眼再发送。

---

## 4. 提问优化设计（P1）

### 4.1 产品判断

**建议加，但作为「显式动作」而不是自动流程。**

- 语音输入后文本通常是：「嗯 我觉得 就是 那个主人公 他为什么 最后要 离开」
- 直接丢给 RAG 也能答，但检索质量、回答聚焦度都会下降。
- 自动改写的问题：用户不知道自己被改了什么，容易产生「这不是我问的」感；且每次语音都调一次 LLM，成本不可控。
- 显式按钮：用户想整理就点，结果回填后还能编辑/撤销，心理模型清晰。

### 4.2 交互流程

```
用户输入/语音转写
        │
        ├─ 文字 < 4 字 → 不显示 ✨
        ├─ 点击 ✨ → 按钮转圈（请求中）
        │      ├─ 成功且文本有变化 → 回填 textarea + Toast「已优化提问 / 撤销」
        │      ├─ 成功但文本相同   → Toast「问题已经很清晰了」，不回填
        │      └─ 失败             → Toast「优化失败，请稍后重试」，保留原文
        └─ 用户仍可编辑，不自动发送
```

细节：

- 优化期间**不禁用 textarea**；请求发出时记录 `snapshot = input`，返回时若 `input !== snapshot`（用户改过），丢弃结果并 toast「文本已变化，未替换」。
- 撤销：`toast.success('已优化提问', { action: { label: '撤销', onClick: () => setInput(original) } })`，保留 6 秒。
- 优化结果只替换输入框内容，不发送、不写入历史、不创建会话。
- 与语音互斥：`listening` 时隐藏/禁用 ✨；优化请求中禁用 🎤。
- 长度限制：前端 4~500 字才显示；后端再做一次兜底。

### 4.3 后端接口

新增 `POST /api/books/{bookId}/chat/optimize-question`：

```json
// request
{ "message": "嗯 那个主人公 为啥最后要跑" }

// response (Result<T>)
{
  "code": 0,
  "message": "success",
  "data": {
    "original": "嗯 那个主人公 为啥最后要跑",
    "optimized": "主人公最终选择离开的原因是什么？",
    "changed": true
  }
}
```

Controller（`BookChatController.java`）：

```java
@Operation(summary = "优化提问")
@PostMapping("/{bookId}/chat/optimize-question")
public Result<OptimizeQuestionResponse> optimizeQuestion(
        @PathVariable Long bookId,
        @RequestBody OptimizeQuestionRequest request) {
    Long userId = extractUserId();
    return Result.ok(bookChatService.optimizeQuestion(userId, bookId, request.message()));
}
```

Service（`BookChatService.java`）：

1. `message == null || blank` → 返回原文，`changed=false`。
2. `CommonUtils.sanitizeAiInput(message)`；命中注入模式 → 抛 `BusinessException("该内容包含不允许的表述")` 或直接返回原文（建议返回原文 + 前端 toast，不打断输入）。
3. 长度 > 500 → 返回原文，`changed=false`。
4. 取图书基础信息（书名、作者、标签，复用现有 `buildSpeedReadContent` 或轻量版），作为上下文。
5. 调 `chatModelManager.callAiForScene(AiScene.BOOK_QA_QUESTION_OPTIMIZE, ...)`。
6. 清理输出：去引号、去「优化后：」前缀、去首尾空白；空结果则返回原文。
7. 异常 → 日志 debug，返回原文，`changed=false`（不把 500 抛给用户，优化是增强功能，不该阻塞提问）。

DTO 建议放 `dto/book/`：

```java
public record OptimizeQuestionRequest(String message) {}
public record OptimizeQuestionResponse(String original, String optimized, boolean changed) {}
```

### 4.4 AI 场景

`AiScene.java` 图书问答域新增：

```java
/** 提问优化 — 把口语/含糊的提问改写为清晰可检索的问题 */
BOOK_QA_QUESTION_OPTIMIZE("提问优化", Category.TOOL, false, false),
```

- `Category.TOOL`：默认走小模型；管理后台可单独绑定更快/更便宜的模型。
- `streaming=false`：一次返回，无需流式。
- `thinking=false`：改写任务不需要思考链，避免延迟和 token 浪费。
- 按 `AiScene` 类注释，管理后台会自动渲染新场景，无需改前端后台页面。

### 4.5 提示词草案

放在 `AiPromptConstants.java`：

```text
你是 KBook 的提问优化器。用户正在就《{bookTitle}》向 AI 提问，输入可能来自语音识别，
存在口语化、重复、自我修正、缺少标点、指代不清等问题。

请把用户的问题改写成一个清晰、具体、便于检索原著内容的问题：
1. 保持原意，不改变提问角度，不扩大或缩小问题范围；
2. 补全省略的主语和指代，删除口头禅、重复和自我修正；
3. 不回答问题，不添加原文没有的信息，不编造人物、情节或观点；
4. 保留书名、人名、专有名词；
5. 只输出改写后的问题本身，不要解释、不要引号、不要「优化后：」之类前缀；
6. 控制在 80 字以内；
7. 如果原问题已经清晰，原样输出，不要为了改写而改写。

<用户问题>
{message}
</用户问题>
```

实现时把固定指令放 `SystemMessage`，把 `{bookTitle}` 和用户问题放 `UserMessage`，便于复用 KV Cache（与 `generateFollowUpQuestions` 的消息顺序保持一致）。

### 4.6 UI 细节

- 图标：`Sparkles`（项目已用，语义一致）或 `WandSparkles`；建议 `Sparkles`。
- 位置：textarea 内部右侧、麦克风左边。
- 显示条件：`input.trim().length >= 4 && !loading && speech.status !== 'listening'`。
- 请求中：图标替换为 `Loader2` 旋转，按钮 `disabled`。
- 成功：回填后 textarea 自动增高（复用现有 `onInput` 逻辑，或抽一个 `resizeTextarea()`）。
- 失败：仅 toast，不动输入框。

---

## 5. 数据流全链路（按 R2 自查）

### 5.1 语音输入

| 层 | 数据 | 状态 |
|---|---|---|
| 浏览器 | 音频 → 厂商识别服务 | 不经过 KBook |
| Hook | `interimText` / `finalText` | `useSpeechRecognition` |
| 组件 ref | `base + cursor + sessionText` | 本地 |
| React state | `input` | `setInput` |
| 发送 | `handleSend(input)` | 现有 SSE |
| 后端 | `BookChatController.streamBookChat` | **零改动** |

确认：语音文本只存在于前端输入框；刷新/关闭后不保留（符合现有未发送输入的语义）。

### 5.2 提问优化

| 层 | 数据 | 状态 |
|---|---|---|
| 前端输入 | `input` | 已有 |
| API | `optimizeBookQuestion(bookId, { message })` | 新增 |
| Controller | `POST /chat/optimize-question` | 新增 |
| Service | `optimizeQuestion(userId, bookId, message)` | 新增 |
| AI 场景 | `BOOK_QA_QUESTION_OPTIMIZE` | 新增 |
| 响应 | `{ original, optimized, changed }` | 新增 |
| 回填 | `setInput(optimized)` + 撤销 toast | 前端 |

确认：优化结果不落库、不建会话、不影响历史；只有用户发送后才进入现有问答链路。

---

## 6. 代码改动清单

### 前端

| 文件 | 改动 | 预估 |
|---|---|---|
| `frontend/src/hooks/useSpeechRecognition.ts` | 新增 Hook：能力探测、状态机、结果重建、错误映射、超时、卸载清理 | 新文件 ~180 行 |
| `frontend/src/components/book/VoiceInputButton.tsx` | 新增麦克风按钮（loading/listening 态、无障碍） | 新文件 ~50 行 |
| `frontend/src/types/speech.ts` | 增加 `SpeechRecognitionStatus`、错误码类型；`Window` 类型声明可放 Hook 内 | +20 行 |
| `frontend/src/components/book/BookChatSheet.tsx` | 输入区包 `relative`；接 Hook；停止时机；优化按钮与请求；toast | +100 / -10 行 |
| `frontend/src/api/bookChat.ts` | `optimizeBookQuestion` | +8 行 |
| `frontend/src/types/bookChat.ts`（若无则就近定义） | `OptimizeQuestionResponse` | +6 行 |

### 后端

| 文件 | 改动 |
|---|---|
| `backend/.../entity/AiScene.java` | 新增 `BOOK_QA_QUESTION_OPTIMIZE` |
| `backend/.../constants/AiPromptConstants.java` | 新增 `QUESTION_OPTIMIZE_SYSTEM_PROMPT` |
| `backend/.../service/ai/BookChatService.java` | 新增 `optimizeQuestion(...)` |
| `backend/.../controller/BookChatController.java` | 新增 `POST /{bookId}/chat/optimize-question` |
| `backend/.../dto/book/OptimizeQuestion*.java` | 新增 request/response record |

### 不做

- 不引入新 npm / Maven 依赖。
- 不改 `streamBookChat` 的 SSE 协议。
- 不改对话历史表结构。
- 不做自动发送、自动改写、录音持久化。

---

## 7. 风险与边界

| 风险 | 影响 | 应对 |
|---|---|---|
| Chrome 国内网络无法识别 | 用户点麦克风后报 `network`，体验差 | 明确提示换 Edge；P2 接云端 ASR |
| Android App WebView 不支持 | 用户以为功能坏了 | 能力探测后隐藏；文档/更新说明标注「暂不支持」 |
| iOS Safari 提前结束 | 长问题被截断 | `onend` 保留文字，提示再点一次；不自动重启 |
| 权限拒绝后反复弹窗 | 骚扰 | 记录 `errorCode`，本次会话不再自动 start，用户可手动再点 |
| TTS 与 ASR 回声 | 识别到 AI 朗读内容 | 开始 ASR 停 TTS；开始 TTS 停 ASR |
| 用户边听边打字 | 文本错位/重复 | 手动 `onChange` 立即停止 ASR |
| 发送时尾包回流 | 已发送又出现新文字 | 发送前 stop + sessionToken 丢弃旧回调 |
| 优化请求慢 | 用户等待 | 按钮 loading，不阻塞编辑；超时/失败保留原文 |
| 优化改变原意 | 用户不满 | 显式触发 + 可撤销 + 不自动发送 |
| 注入/超长输入 | 成本或安全问题 | `sanitizeAiInput` + 500 字上限 + 输出清理 |
| 隐私合规 | 音频出境给浏览器厂商 | 隐私页补充说明；不存音频；不经过 KBook 服务器 |

---

## 8. 分阶段实施

### P0：语音输入（约 1 天）

1. `useSpeechRecognition` Hook + 能力探测。
2. 输入框 UI：麦克风按钮、监听/请求态、状态行。
3. 停止时机全接线：发送、关闭、新建、切会话、卸载、TTS、超时。
4. 错误 toast 映射。
5. `npm run build` 通过。

### P1：提问优化（约 0.5~1 天）

1. 后端 `AiScene` + prompt + service + controller + DTO。
2. 前端 API + ✨ 按钮 + loading + 撤销 toast + 竞态保护。
3. `mvn compile` / `npm run build` 通过。
4. 管理后台确认新场景自动出现，可绑定模型。

### P2：可选增强

- Android 原生 `SpeechRecognizer` 插件（Capacitor）。
- 云端实时 ASR 兜底（讯飞/阿里/腾讯），替换 Hook 内部 provider。
- 说话时显示音量波形。
- 语音输入结束后自动弹出「要不要优化一下？」的轻提示（不自动改写）。
- 隐私页/新手引导补充语音说明。

---

## 9. 验收清单

### 语音输入

- [ ] Chrome/Edge 桌面：首次点击弹权限，允许后能识别中文并实时显示。
- [ ] 拒绝权限：出现明确提示，不崩溃、不反复弹窗。
- [ ] 光标在文本中间时开始说话：文字插在光标处，不覆盖原有内容。
- [ ] 监听中手动打字：ASR 立即停止，已有文字保留。
- [ ] 监听中点击发送：麦克风停止，消息只发送一次，发送后无尾包文字。
- [ ] 监听中关闭 Sheet / 新建对话 / 切换历史会话：麦克风停止。
- [ ] AI 正在朗读时点麦克风：TTS 停止，无回声。
- [ ] 无麦克风设备 / 无网络 / 无声音：对应错误提示正确。
- [ ] Firefox / Capacitor Android：麦克风按钮不出现。
- [ ] 120 秒未手动停止：自动停止并提示。
- [ ] 页面卸载/路由切换：无麦克风常驻（浏览器地址栏录音标识消失）。

### 提问优化

- [ ] 输入 4 字以下：✨ 不出现；500 字以上：后端返回原文。
- [ ] 口语问题：返回更清晰的问题，回填且不自动发送。
- [ ] 已清晰的问题：toast「问题已经很清晰了」，不替换。
- [ ] 请求中编辑文本：结果丢弃，不覆盖用户新输入。
- [ ] 点击撤销：恢复原文。
- [ ] AI 失败/超时：保留原文，toast 提示，可继续发送。
- [ ] 优化接口不创建会话、不写历史；发送后问答链路与原来一致。
- [ ] 管理后台出现「提问优化」场景，可单独绑定模型。

### 构建

- [ ] `cd frontend && npm run build` 零错误。
- [ ] `cd backend && mvn compile` 零错误。

---

## 10. 待确认

1. **麦克风位置**：文档默认放在 textarea 内部右侧（与 ✨ 并排），不额外占横向空间；如果希望更显眼，也可放在发送按钮左侧独立成按钮。
2. **优化按钮是否常驻**：默认「有文字才出现」，减少空输入时的视觉噪音。
3. **Android 端**：第一版接受「App 内没有语音输入」，还是要在 P0 就同步做原生插件？建议前者，先验证使用率。
4. **隐私文案**：是否本轮同步更新 `pages/legal/privacy.tsx`，说明音频由浏览器厂商处理、KBook 不存储音频。
