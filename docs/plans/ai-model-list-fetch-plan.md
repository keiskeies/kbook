# AI 设置「获取模型列表」实施计划

> 状态：P1+P2+P3 已实施并运行时验证（2026-11-01）· 进度与偏差见 §9
> 范围：管理端 AI 供应商配置的「模型名称」填写体验
> 不涉及：模型路由、场景绑定、成本统计、embedding 检索

---

## 0. 目标与现状

目标：填 AI 配置时点一下就能从服务商拉到真实模型列表并选中，不再手敲模型 ID。

现状（已核实）：

| 位置 | 现状 | 后果 |
|---|---|---|
| `frontend/src/pages/admin/ai-config.tsx:351-368` | 仅当 `presets.find(p => p.baseUrl === form.baseUrl)` 命中（`:278`）才渲染 `<select>` | baseUrl 稍有不同（自建网关、`/v1` 后缀差异）就退化成纯 `<input>` |
| `backend/src/main/resources/ai-providers.yml` | 21 个预设、静态 `models` 列表，数据截至 2026-05-15 | 新模型、灰度模型、微调名一律看不到 |
| `AdminAiConfigController:140-155` `POST /{id}/test` | 已有「测试连接」，走 `ChatModelFactory.buildChatModelForTest`（`:275-279`） | 复用不了：测试连接是「发一条对话」，不能列模型 |
| 全库 grep `listModels` / `/models` / `availableModels` | 零命中 | 需要新建，无既有实现可挂 |

即：**预设列表是「猜」，拉取是「问」**。功能定位是给自定义 baseUrl 兜底，预设列表保留作降级。

---

## 1. 接口设计

```
POST /api/admin/ai-config/models
```

放在 `AdminAiConfigController` 内，自动继承类级 `@PreAuthorize("hasRole('ADMIN')")`（`:22-27`）。

**为什么是 POST + body 而不是 `GET /{id}/models`**：主场景是「新建配置、还没保存」，此刻 DB 里没有这一行，只能用表单值探测。保存后再编辑时走 `configId`，见下。

请求体（新增 `record ProviderModelsRequest`）：

```jsonc
{
  "provider": "OPENAI",            // OLLAMA | OPENAI（与实体枚举一致）
  "baseUrl": "https://xxx/v1",     // 必填，表单当前值
  "apiKey": "sk-xxx",              // 可选：新建时传表单值
  "configId": 12                   // 可选：apiKey 为空时后端按 id 从库里取 key
}
```

`configId` 优先于 body 里的 `apiKey`：编辑已存在配置时前端不必把明文 key 再发一次，将来若把 `apiKey` 改成 WRITE_ONLY + 掩码回显，本接口无需改动。

响应（`Result<T>` 包装，`common/api/Result.java:13-30`）：

```jsonc
{
  "code": 0,
  "data": {
    "models": [
      { "id": "deepseek-v4-flash", "label": "DeepSeek V4 Flash", "contextWindow": null, "createdAt": "2026-03-01T00:00:00Z" }
    ],
    "source": "PROVIDER",   // PROVIDER | EMPTY（拉到了但空）| UPSTREAM_ERROR
    "endpoint": "https://xxx/v1/models",   // 实际请求地址，便于排错
    "total": 3,
    "hint": null            // 降级提示语，如"该服务未提供模型列表接口，请手动填写"
  }
}
```

失败沿用 `testConnection` 的风格：`Result.fail("获取模型失败: ...")`，前端 `request.ts:5-14` 的拦截器会直接 reject 成 toast。

---

## 2. 各服务商怎么问（核心：URL 归一 + 宽容解析）

新增两个**纯函数**类，便于单测（不碰 Spring、不发 HTTP）：

- `service/ai/core/model/ModelEndpointResolver` — `(provider, baseUrl) → List<候选 URI>`
- `service/ai/core/model/ModelListParser` — `(原始 JSON 文本) → List<ModelSummary>`

### 2.1 URL 归一规则

| 表单 baseUrl | provider | 实际请求 |
|---|---|---|
| `http://localhost:11434` | OLLAMA | `GET /api/tags`（响应是 `{"models":[{"name":...,"size":...}]}`） |
| `http://localhost:11434/v1` | OLLAMA | 剥掉尾部 `/v1` → `GET /api/tags` |
| `https://api.deepseek.com/v1` | OPENAI | `GET /v1/models` + `Authorization: Bearer` |
| `https://api.openai.com/v1/chat/completions` | OPENAI | 剥掉 `/chat/completions` → `/v1/models` |
| 末尾已带 `/models` | 任意 | 原样使用，不重复追加 |
| `https://generativelanguage.googleapis.com/v1beta` | OPENAI | 复用 `ChatModelFactory.isGeminiModel`（`:777-784`）判定 → `GET /v1beta/models?key=xxx`（Gemini 用 query 而非 Bearer） |
| `https://api.anthropic.com` 或 `/v1` | OPENAI | `/v1/models` + `x-api-key` + `anthropic-version: 2023-06-01`；这条线的响应带 `max_input_tokens`，可直接回填上下文长度 |
| `https://one-api.xxx` / `http://192.168.1.20:8000` 这类不带版本号的网关 | OPENAI | **按序尝试** `{base}/models` → `{base}/v1/models`，第一个 2xx 生效 |

归一化步骤固定为：`trim` → 去尾 `/` → 剥已知动作后缀（`/chat/completions`、`/completions`、`/embeddings`）→ 按 provider 与特征域名拼候选列表。

「多候选按序尝试」是这一版最容易踩空的地方：vLLM、one-api、LiteLLM、各类中转网关的路径前缀互不相同，只拼一种会大面积 404。

### 2.2 宽容解析

OpenAI 官方是 `{"data":[{"id":...}]}`，但现实中会遇到 `{"models":[...]}`、`{"data":{"models":[...]}}`、裸数组。统一按：

- 外层容器键依次取 `data` → `models` → `items`，或根本身就是数组；
- 条目 id 依次取 `id` → `name` → `model` → `model_id`；
- 可选字段：`created`(epoch 秒) → `createdAt`，`context_window` / `context_length` / `max_input_tokens` / `inputTokenLimit` → `contextWindow`，`object=="model"` 之外的一般是 embedding/tts 条目，**不做过滤**（见 4.3）。
- 去重、按 `createdAt` 倒序（新模型在前）、上限 500 条截断。

---

## 3. 后端服务层

新增 `service/ai/core/AiModelListService`：

1. 校验入参（provider 非空、baseUrl 是 http/https、长度 ≤ 500 与实体 `baseUrl` 列宽一致）。
2. 取 apiKey：`configId` 命中则用库里的，否则用 body 传入的。
3. 查 Caffeine 缓存 → 未命中则发请求。
4. 返回 VO。

缓存策略沿用 `ChatModelFactory` 的写法：

- key = `provider|baseUrl|apiKeyFingerprint`，指纹沿用 `Integer.toHexString(apiKey.hashCode())`（`ChatModelFactory:204-206`），**明文 key 不进缓存键**；
- `maximumSize(64)` + `expireAfterWrite(10min)`；
- 注册进 `AiConfigCacheInvalidator`（`ChatModelFactory:120-131` 已有同样姿势），配置保存后列表跟着失效。

HTTP 客户端：**不引新依赖**（AGENTS.md 约定）。用项目里已有的 LangChain4j `HttpClient`（`langchain4j-http-client-spring-restclient`，`backend/pom.xml:129-177` 已引入并作为唯一 HTTP 实现），与 chat 调用共用连接与超时语义。

硬超时与固定参数（这一条必须显式覆盖）：**不能用配置里的 `timeout`**——`ai_provider_config.timeout` 默认 600 秒（实体 `:77`），是给长对话用的。模型列表接口固定 `connect 3s / read 8s / 单候选 8s、全部候选累计 20s`，否则 UI 会转圈到用户以为页面挂了。

### 3.1 安全边界

这个端点接受管理员提供的任意 URL 并由服务端发起请求，属于 SSRF 面。已核实的前提：管理员本就能把 provider 指向任意地址并让后端发对话（`buildChatModelForTest`），且本地 Ollama / 内网 vLLM 是**合法且主要**的用法，所以不能一刀切禁私网。做法是把「可读任意内部响应」这个能力关掉：

- 只 `GET`，不跟随重定向（`HttpClient.Redirect.NEVER`）；
- 协议白名单 http/https；拒绝 `169.254.0.0/16`、`metadata.google.internal` 这类云元数据地址；
- 响应体读取上限 1MB，超限即断；
- **只回传解析后的模型 ID 数组，绝不回传原始 body**——这样它不是一个通用「读内部 HTTP 响应」的原语；
- 错误信息只带状态码与一句中文提示，不透出上游响应内容；
- 日志不打印 apiKey（沿用指纹）；
- 与 chat 的熔断/重试层**隔离**，不走 `wrap()`，避免拉列表失败污染 provider 熔断计数。

---

## 4. 前端设计

### 4.1 API 封装

`frontend/src/api/aiConfig.ts` 新增（紧挨 `testAiConfig`，`:85`）：

```ts
export interface AiModelSummary { id: string; label?: string; contextWindow?: number; createdAt?: string }
export interface AiModelListResult { models: AiModelSummary[]; source: string; endpoint: string; total: number; hint?: string }
export const listProviderModels = (body: { provider: string; baseUrl: string; apiKey?: string; configId?: number }) =>
  request.post<AiModelListResult>('/admin/ai-config/models', body).then(r => r.data)
```

### 4.2 模型字段块改造（`ai-config.tsx:351-368`）

数据源优先级：**拉取结果 > YAML 预设 > 自由文本**。

```
模型名称        [ 搜索下拉（可输入）            ▼ ]  [⟳ 获取模型]
                获取到 214 个 · https://xxx/v1/models   ✕ 失败原因内联
```

- 按钮 disabled 条件：`!form.baseUrl.trim()`；OLLAMA 不要求 key，OPENAI 无 key 且无 `configId` 时提示「填 API Key 后再获取」（部分网关不校验，仍可点，拉到 401 再报错）。
- 结果用 react-query 缓存，key = `['admin','ai-models', provider, baseUrl]`（**不把 apiKey 放进 key**），`staleTime: 10min` → 同一网关第二次开表单秒开。
- 列表用「输入即过滤」的下拉，不是原生 `<select>`：OpenRouter 一类会返回 400+ 条，原生 select 无法用。
- 选中模型时若 `contextWindow` 有值 → 一并回填 `form.maxTokens`（与现有 `:358` 选预设模型填 `maxTokens` 的行为一致）。
- **自由文本入口必须保留**：灰度模型、`ft:` 微调名、网关别名都不在 `/models` 里。现有 `isCustomModel`（`:175`）逻辑改成「输入框内容不在列表里就视为自定义」，而不是多一个 `__custom__` 选项。
- 拉取失败：内联一行红字 + 保持当前可用输入，不弹 toast 阻断编辑；空列表 → 显示 `hint`（「该服务未提供模型列表接口，请手动填写」）。

### 4.3 不做按 purpose 过滤（本期）

`purpose` 为 `EMBEDDING` 的配置同样能拉列表（Ollama `/api/tags` 里混着 chat 和 embedding 模型）。但按名字猜类型不可靠（`bge-m3` 是 embedding，`qwen3-embed` 是，某些网关的 `*-embedding-v2` 也是），误过滤会把用户要的模型藏起来。本期全量展示 + 搜索，不做启发式筛除。

### 4.4 列表行上的入口（可选，P3）

`ai-config.tsx:80`（EMBEDDING 行）与 `:113`（CHAT 行）的「测试连接」图标旁加一个「刷新模型」，复用同一接口、只传 `configId`。价值不如表单内那个高，因为列表页不需要选模型。

---

## 5. 分期

| 期 | 内容 | 产出 |
|---|---|---|
| P1 | `ModelEndpointResolver` + `ModelListParser` + `AiModelListService` + `POST /models` + 单测 | 后端可 curl 验证 |
| P2 | `aiConfig.ts` 封装 + 模型字段块改造（搜索下拉、获取按钮、失败内联、回填 maxTokens） | 主用户价值 |
| P3（可选） | 行上「刷新模型」入口；`apiKey` 改 WRITE_ONLY + 掩码回显 | 独立于本功能的收尾 |

## 6. 测试计划

纯函数单测（不发真实网络，符合「阈值抽成纯函数」的既有做法）：

- Resolver：表中 9 类 baseUrl 各一条 + 空串/无协议/带空格/末尾多斜杠 + `http://` 被拒 + 元数据地址被拒。
- Parser：`{"data":[{"id"}]}`、`{"models":[{"name"}]}`、裸数组、Ollama `tags`、Anthropic（含 `max_input_tokens`）、条目缺 id、>500 条截断、非 JSON 文本、空数组。
- 变异检查：把「剥 `/chat/completions`」「去重」「createdAt 倒序」任一故意改坏，确认对应用例真的会红。

端到端自证（本地独占端口，避开 `D:\item\kbook` 占用的 8181/15173）：

1. `mvn compile` + `npm run build` 双端零错误（R4 清单）；
2. 造一个假上游（`node` 起 8199 端口返回 `{"data":[{"id":"m1"},{"id":"m2"}]}`），配 baseUrl 填 `http://localhost:8199`（不带 `/v1`）验证多候选回退；
3. 配 Ollama `http://localhost:11434` 验证 `/api/tags` 分支；
4. 故意填错 key 验证 401 的错误文案，并确认前端输入框仍可用；
5. 抓包确认失败响应体不含上游原文。

## 7. 已定稿决策（2026-11-01 确认）

| 议题 | 决策 | 放弃的替代方案 |
|---|---|---|
| 拉取入口 | 表单内按需拉取，接口收 body 的 `provider/baseUrl/apiKey`，编辑已存配置时传 `configId` 由后端取 key | 只在配置行放按钮（必须先保存，新建流程仍手敲） |
| 列表存储 | 只 Caffeine 内存缓存（TTL 10min，随配置保存失效），不加列 | `models_snapshot` JSON 列（要迁移、要管陈旧） |
| 控件形态 | 搜索式下拉，选中回填 `contextWindow` → `maxTokens`，列表外的输入自动视为自定义 | 原生 `<select>` 追加分组（400+ 条时不可用） |

## 8. 顺带发现（不在本计划内，仅记录）

`ai_provider_config.api_key` 明文入库（实体 `AiProviderConfig.java:69` 无 converter；仓库里唯一的加解密工具 `common/crypto/AesGcmUtil.java` 只用于点击验证码），且 `listAll` / `getByPurpose` / `create` / `update` 都把它原样序列化回前端，编辑表单直接预填（`ai-config.tsx:218-225` → `:374`）。本功能沿用现状不加剧问题；是否收敛为「写入型字段 + 掩码回显」建议单独立项。

---

## 9. 实施记录（2026-11-01）

### 9.1 落地的文件

| 文件 | 内容 |
|---|---|
| `service/ai/core/model/ModelEndpointResolver.java` | 新增：`(provider, baseUrl) → 候选地址列表`，含鉴权风格与元数据地址拦截 |
| `service/ai/core/model/ModelListParser.java` | 新增：宽容解析 + 去重 + 新模型在前 + 500 截断 |
| `service/ai/core/model/ModelSummary.java` / `ModelListResult.java` | 新增：条目与响应 VO |
| `service/ai/core/AiModelListService.java` | 新增：多候选尝试、Caffeine 缓存、硬超时、响应体上限 |
| `controller/AdminAiConfigController.java` | 新增 `POST /api/admin/ai-config/models` + `ProviderModelsRequest` |
| `frontend/src/api/aiConfig.ts` | 新增 `listProviderModels` 与三个类型 |
| `frontend/src/pages/admin/ai-config.tsx` | 模型字段块换成搜索式下拉；删除 `isCustomModel`（下拉本身即自由文本） |
| 测试 | `ModelEndpointResolverTest`(18) / `ModelListParserTest`(19) / `AiModelListServiceTest`(18) |

### 9.2 与设计的偏差（都是实现期发现的更好做法，不是妥协）

1. **失败不再走 `Result.fail`**（§1 原案）：全部 `code=0` + `data.source=UPSTREAM_ERROR`。
   理由：失败时管理员最需要的是「实际尝试了哪个地址」，`Result.fail` 只有 message 字段，装不下 endpoint；
   且 §4.2 要求失败内联提示不弹 toast，走 fail 分支会被 axios 拦截器统一 reject 成 toast 语义。
2. **HTTP 客户端用 JDK `java.net.http.HttpClient`**（§3 原案是 LangChain4j 的 `HttpClient`）：
   需要 `Redirect.NEVER`、连接/读取分别限时、响应体 1MB 断流这三件事，SDK 的抽象层不暴露。
   仓库里 Qdrant 调用（`EmbeddingIndexService.java:298`）已是同一写法，未引入新依赖。
3. **表单里刚输入的密钥优先于 `configId` 的库存值**（§1 原案相反）：
   「换了 key 想验证能不能列出模型」是本功能的主要用法，库优先会让这种场景永远测不到新 key。
   `apiKey` 留空时才回落到 `configId`，将来掩码回显后前端不传 key 也照常工作。
4. **前端不叠 react-query 缓存**（§4.2 原案）：后端已有 10 分钟缓存，第二次点击本就毫秒级；
   把明文 key 或它的哈希放进 query key 都是额外负担，改成组件态 + 结果与地址绑定（改地址即作废）。
5. **多候选都失败时按「可行动性」挑文案**（设计未提）：
   401 > 3xx > 5xx > 超时 > 404 > 连接失败。按发生顺序覆盖会让「密钥不对 + 第二个候选 404」
   显示成「该地址没有模型列表接口」，管理员就会去改地址，真正该改的是密钥。
6. **搜索无命中时退回展示全部并标注**（设计未提，浏览器实测发现）：
   换地址重拉后，输入框里的旧模型名必然不在新列表里，过滤结果为 0 →
   状态行写「已获取 N 个」而下拉是空的，看起来像什么都没拉到。不替用户改掉手填值，改为兜底展示。

### 9.3 验证结果

| 层 | 结果 |
|---|---|
| `mvn -o test` 全量 | 645 例全绿（含新增 60 例） |
| 变异检查 | 剥后缀 / 去重 / 时间倒序 / 错误优先级 四处各改坏 → 对应用例变红；据此补强了 `sortsNewestFirst`（原断言字典序与时间序同向，打不红） |
| `npm run build` | tsc + vite 零错误 |
| 真实 HTTP 服务层 | 19 例对着本地 `HttpServer` 桩跑通：候选顺序、401 优先、不跟随 302、1MB 断流、8 秒硬超时、缓存命中与失效、只传 configId |
| 端到端 curl（8181） | 6 条：无版本号回退、Ollama `/api/tags`、401 文案、换对密钥成功、USER token 403、元数据地址被拒 |
| 浏览器（1440×1000 与 390×844） | 各 29 项断言全绿：假上游 22 项 + 掩码/行刷新/configId 探测 7 项；含预设回归；全程不点保存，未向 dev 库写入任何配置行 |
| 真实网关 | 2/2 拉到列表（局域网 ai-gateway + cherry-in 170 条），见 §9.4 |
| 日志审计 | 后端日志无明文密钥（3 个测试 key 计数均为 0）、无上游响应原文；实体 `toString` 也不含明文 |

### 9.4 真实网关抽样（2026-11-01 补做）

用 dev 库里已有的 2 条配置，**只传 `configId`**（密钥留在服务端），结果：

| 配置 | 地址 | 结果 |
|---|---|---|
| ai-gateway (CHAT) | `http://192.168.1.10:1994/v1` | 4 条 / 57ms，在用模型 `agnes` 在列表里 |
| cherry-in-bge-m3 (EMBEDDING) | `https://open.cherryin.net/v1` | 170 条 / 416ms，在用模型在列表里 |

两家都一次命中，`id`/`created` 字段名无需扩表；170 条也验证了「搜索式下拉而非原生 select」的判断。
抽样脚本 `sampling.mjs` 全程不打印密钥。

### 9.5 P3 已做（2026-11-01）

1. **`apiKey` 不再回传浏览器**：实体字段加 `@JsonProperty(WRITE_ONLY)` + `@ToString.Exclude`
   （后者顺手堵掉「日志里打整个实体」这条路），新增只读派生值 `apiKeyMasked`
   （`••••` + 末 4 位，短密钥不露尾巴，未配置为 null）。
   前端编辑态输入框留空 + 占位符「已保存 ••••xxxx · 留空则不修改」；
   保存路径本来就 `apiKey: trim() || undefined`，留空即沿用库存值（controller 跳过 null）。
   契约由 `AiProviderConfigJsonTest` 4 条钉住：出参无 `apiKey`、入参仍收、掩码规则、toString 不含明文。
2. **配置行上的「刷新模型」入口**：只发 `{configId}`，类型/地址/密钥全部取服务端库存值
   （不信客户端回传，行操作才等于「刷新这一行」），成功后 toast 报数量并预热 10 分钟缓存。

### 9.6 仍未做

- 真实网关只覆盖到你环境里的这 2 家；OpenRouter / 硅基流动 / DashScope 等按 §2.2 的宽容解析兜，
  真遇到拉不到时优先扩 `ID_KEYS`/`CONTEXT_KEYS`，不要加域名特判。
- `TtsConfig.apiKey`（另一张表）仍按原样回传，本次未动。

### 9.7 一条踩坑记录

验 P3 时列表接口仍返回明文密钥，第一反应是「Lombok 的 getter 覆盖了字段注解」。
真实原因是**上一轮 `TaskStop` 没杀掉 `spring-boot:run` 的 java 子进程**，
8181 一直被改动前编译的旧进程占着，新起的实例因端口冲突直接 `APPLICATION FAILED TO START`。
教训：改完序列化行为后，先用 `netstat` + 进程启动时间对一下「我打的是哪个进程」，再怀疑代码。
