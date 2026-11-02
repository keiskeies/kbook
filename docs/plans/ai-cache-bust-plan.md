# LLM 请求缓存破坏（随机 nonce）实施计划

> 状态：已实施 · 日期：2026-10-31 设计，2026-11-01 落地
> 范围：所有真正发往 LLM 的聊天/流式请求
> 不涉及：embedding、TTS、内部业务缓存（如速读缓存）
>
> **2026-11-01 补记**：本文档此前标着「已实施」，但树里既没有 `CacheBusting*` 三个类、
> 也没有 `AiModelProperties.cacheBustEnabled`，只有 `application.yml` 那一行配置是活的
> （一个没有绑定目标的死键）。实现已在 2026-11-01 按本文档补回，见 §6。

---

## 0. 目标

在每个 LLM 请求的消息最前面注入随机 nonce，使 provider 端的提示词缓存、KV cache、网关响应缓存必然 miss。

选择统一节点而不是逐业务改动：

- 非流式全部经过 `ChatModelFactory.wrap()`：`熔断器 → 重试 → 缓存破坏 → 实际模型`
- 流式全部经过 `ChatModelFactory.wrapStreaming()`：`熔断器 → 缓存破坏 → 实际模型`

业务里直连 `buildForScene` 的调用（元数据推断、重排、列表检索、AiServices、管理端测试等）也由同一节点覆盖，业务代码零改动。

---

## 1. 改动点

| 文件 | 改动 |
|---|---|
| `service/ai/core/model/CacheBustingSupport.java` | 新增：生成 nonce、复制消息列表、保留 `ChatRequest` 参数 |
| `service/ai/core/model/CacheBustingChatModel.java` | 新增：非流式装饰器，覆盖 `chat(List)` / `doChat(ChatRequest)` |
| `service/ai/core/model/CacheBustingStreamingChatModel.java` | 新增：流式装饰器，覆盖 `chat(List)` / `doChat(ChatRequest)` |
| `config/ChatModelFactory.java` | `wrap` / `wrapStreaming` 各接入一层装饰器 |
| `config/properties/AiModelProperties.java` | 新增 `cacheBustEnabled`，默认 `true` |
| `resources/application.yml` | 暴露 `kbook.ai.cache-bust-enabled` |

### nonce 注入规则

1. 每次调用生成新的随机 16 进制标记：`[kbook-req-xxxxxxxx]\n`。
2. 首条消息是 `SystemMessage` → 拼到其文本最前面；否则在 0 位补一条只含 nonce 的 `SystemMessage`。
3. `ChatRequest` 用 `toBuilder()` 重建，保留 temperature/topP/maxOutputTokens/tools/toolChoice/responseFormat 等字段。
4. 复制消息列表，不修改调用方入参。
5. nonce 必须在调用期生成；模型实例被 `ChatModelFactory` 缓存，构建期生成会导致所有请求复用同一随机数。
6. 缓存破坏层位于重试内侧：`RetryableChatModel` 的 429 重试、`StreamingSseHandler` 的流式重试每次都会用原始消息重新调用模型，因此每次重试都会生成新的 nonce。

---

## 2. 覆盖边界

| 层 | 是否覆盖 | 说明 |
|---|---|---|
| 非流式聊天（场景路由 + 直连 `buildForScene`） | ✅ | 含压缩、OCR、管理端测试连接 |
| 流式聊天（SSE / 管理端 / AiServices） | ✅ | 所有 `buildStreamingForScene` 产物 |
| provider 提示词/响应缓存 | ✅ | nonce 在消息序列最前面 |
| embedding | ❌（按需求排除） | 注入会改变向量，与已入库向量不一致 |
| TTS | ❌（按需求排除） | 音频缓存另算，且不能把随机数读出来 |
| 速读缓存 `SpeedReadCache` | ❌ | 命中时不会调用 LLM，本方案不改变其行为 |
| `RagAnswerCache` | 无需处理 | 当前只调用失效方法，读路径未接入 |
| 模型/配置实例缓存 | 不影响 | 不是响应缓存 |

> 如果后续要求“连内部缓存也不命中”，需要单独在速读入口加 bypass 开关，不能靠本方案里的 nonce 实现。

---

## 3. 开关与回滚

```yaml
kbook:
  ai:
    cache-bust-enabled: ${KBOOK_AI_CACHE_BUST_ENABLED:true}
```

- 默认开启。
- 关闭：设置环境变量 `KBOOK_AI_CACHE_BUST_ENABLED=false`，无需改代码。
- 关闭后模型链恢复为 `熔断器 → 重试 → 实际模型`，原有 KV Cache 前缀复用的代码不变。

---

## 4. 验证

### 单测（8/8 通过）

`mvn -q -Dtest=CacheBustingModelsTest test`

覆盖：
- nonce 位于首条 SystemMessage 之前；
- 无 SystemMessage 时在 0 位补 system；
- 连续两次调用 nonce 不同；
- `ChatRequest` 的 temperature/topP/maxOutputTokens 原样保留；
- 流式 `chat(List)` 与 `doChat(ChatRequest)` 两个入口都注入；
- 429 重试（非流式 `chat(List)` / `doChat(ChatRequest)`、流式 SSE 重试）每次都生成不同 nonce；
- 调用方原始消息列表不被修改。

### 运行时核对

1. 同一 prompt 连续调用两次，观察网关侧 cached tokens 应为 0；若无该指标，对比 TTFT / 输入费用。
2. 回归 `/no_think` 仍追加在 SystemMessage 末尾、AiServices 工具调用正常、PDF OCR 正常、SSE 完成/重试正常。

---

## 5. 代价

- provider 前缀缓存全部失效：长上下文输入按全价计费，TTFT 变长。这是“完全去缓存”的预期代价。
- OCR 与管理端“测试连接”也会带 nonce；目前视为可接受。
- 开关保留，排查结束后可直接关闭恢复原有缓存收益。

---

## 6. 落地记录（2026-11-01）

按 §1 的改动点补齐，与本文档、`application.yml` 的那行配置同批提交。

| 项 | 结果 |
|---|---|
| 三个类 + 属性 + 两处接线 | `CacheBustingSupport` / `CacheBustingChatModel` / `CacheBustingStreamingChatModel` / `AiModelProperties.cacheBustEnabled` / `ChatModelFactory.wrap` `wrapStreaming` |
| 单测 | `CacheBustingModelsTest` 8 例全绿（§4 清单全覆盖，另加 doChat 走重试一例） |
| 变异检查 | 把 `newNonce()` 换成固定串（等价于「构建期生成」这一真实失效模式）→ 4 例变红：`nonceDiffersPerCall`、两条重试用例、流式双入口用例 |
| 与文档的两处偏差 | ① 装饰器一律调 `delegate.chat(...)` 而非 `delegate.doChat(...)`，理由同 `CircuitBreakerChatModel` 里那条 ClassCastException 注释；② 重试用例用消息含「429」的 `RuntimeException` 触发（`RetryableChatModel.isRateLimitError` 按消息匹配），不需要真造一个 OpenAi 异常 |
| 未验 | §4 的运行时核对（同一 prompt 连打两次看网关 cached tokens 归 0）需要 provider 侧指标，本机未做 |
