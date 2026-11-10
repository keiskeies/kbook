# 笔记输入减负与自动整理实施计划

> 状态：已实施并验证（2026-11-03）· 进度与偏差见 §8
> 范围：`记一笔` 表单（`NoteComposer`）与新建纯想法笔记的自动整理
> 不涉及：笔记详情页编辑表单、书评收录弹窗、笔记检索算法

---

## 0. 目标与现状

目标：让「记一笔」回归一句话输入。标题、标签默认不出现在表单里，留空由系统补齐；需要手动整理的少量用户仍可一键展开。

现状（已核实）：

| 位置 | 现状 | 问题 |
|---|---|---|
| `frontend/src/components/note/NoteComposer.tsx` | 标题输入框、标签输入框常驻 | 两个视觉「框」让快记变成填表 |
| `backend/.../NoteService.java:73` | `title` 直接取请求值，可为空 | 无标题笔记在列表里只能靠正文兜底 |
| `backend/.../Book.java:58-72` | 图书已有 `formatTags` / `conceptTags` / `readerNeedTags` / `targetReaderTags` 四组 JSON 标签 | 现成词库未被笔记复用 |
| `backend/.../NoteService.java:156` | 已有用户标签云（按使用次数排序） | 可作用户个人词库 |
| `backend/.../entity/AiScene.java` | 无笔记标签场景 | 需要新增场景，后台自动渲染 |

---

## 1. 产品决策

1. **默认收起**：标题、标签输入框默认隐藏；顶部保留一行轻量入口「添加标题、标签」。
2. **按习惯记忆**：展开区提供「以后默认展开」勾选框，偏好存 `localStorage`（键 `kbook:note-composer-meta-open`），只影响当前设备，零后端改动。
3. **留空即自动**：
   - 标题留空 → **确定性生成**，不调用 LLM：取正文首个有效行，去 Markdown 标记，截断到 20 字。
   - 标签留空 → 保存成功后**异步调用 AI**，由 AI 从候选词库中做选择题，选 1-3 个；没有合适标签就保持为空。
4. **手动优先**：用户填了标题或标签就绝不被覆盖；AI 只补空字段。
5. **可撤销**：AI 补标签后 toast 提示「AI 已补标签：#a #b」，附「撤销」按钮，撤销即清空这组标签。
6. **失败静默**：AI 不可用、词库为空、输出不合规时都保持无标签，不阻塞保存、不弹错误。

标题不交给 LLM：一句正文的首行截断已经足够「大概有个标题」，确定性、零延迟、零幻觉。

---

## 2. 端到端数据流

```
用户只写正文 → POST /api/notes
  → NoteService.createThought
      ├─ tags 为空：tags = null
      └─ title 为空：title = NoteTitleGenerator.from(contentMd)
  → 返回 NoteVO（含自动标题）
前端关闭抽屉
  → 若本次请求 tags 为空且正文非空：
      POST /api/notes/{id}/auto-tags
        → NoteAutoTagService
            ├─ 组装候选词库：用户历史标签 + 图书标签（按频次）
            ├─ ChatModelManager.callAiForScene(NOTE_AUTO_TAG, ...)
            ├─ 解析 + 只保留词库内标签
            └─ 仅当笔记标签仍为空时写回
        → 返回更新后的 NoteVO
  → 前端 invalidate ['notes']，toast 提示并可撤销
```

编辑已有笔记（`PUT /api/notes/{id}`）不触发自动标题/标签，避免覆盖用户的编辑意图。

---

## 3. 后端设计

### 3.1 自动标题：`NoteTitleGenerator`

纯静态工具类，便于单测。规则：

1. 按行扫描，取第一个非空行；跳过纯代码围栏行（```）。
2. 去块级 Markdown 前缀：`#` 标题、`>` 引用、`-/*/+` 列表、`1.` 有序列表、`- [ ]` 任务列表。
3. 去行内 Markdown：图片/链接保留可读文字，去掉 `**`、`*`、`~~`、反引号、HTML 标签。
4. 连续空白折叠为单个空格。
5. 超过 20 字截断并追加 `…`；结果为空则返回 `null`。

在 `NoteService.createThought` 内使用：`title = trimToNull(req.getTitle())`；为空时回填 `NoteTitleGenerator.from(req.getContentMd())`。

### 3.2 候选词库

`NoteAutoTagService` 组装，优先级从高到低：

1. **用户历史标签**：`NoteService.listTags(userId)`，最多 50 个。
2. **图书领域标签**：`formatTags`（领域）
3. **图书概念标签**：`conceptTags`
4. **图书读者需求标签**：`readerNeedTags`

后三组来自 `BookRepository` 新增的只取标签列的 JPQL，解析 JSON 数组后按出现频次降序、同频按字典序，去重后截断。候选总数上限 120，避免提示词膨胀。**不使用 `targetReaderTags`**：读者画像（创业者、学生）不适合作为笔记主题标签。

标签统一做去逗号、去 `%`/`_`、长度 ≤20 的清洗；清洗后为空则丢弃。

### 3.3 AI 场景与提示词

新增枚举：`NOTE_AUTO_TAG("笔记自动标签", Category.TOOL, false, false)`。非流式、无思考，后台场景配置页自动出现。

System 提示词要点：

- 只能从候选词库中逐字选择 1-3 个标签；
- 没有合适标签时返回空数组；
- 只输出 JSON 字符串数组，例如 `["认知偏差","决策"]`，不要解释或代码块。

User 消息包含候选词库（顿号分隔）、笔记标题、正文（截断 1200 字）。

### 3.4 输出校验

新增纯类 `NoteTagResponseParser`：

1. 先 `CommonUtils.stripCodeFence`，再取第一个 `[` 到最后一个 `]`；
2. 用 Jackson 解析为字符串数组；
3. 只保留与候选词库**完全一致**的值（防止 AI 造词）；
4. 去重、最多 3 个；任何解析失败返回空列表。

### 3.5 接口

```
POST /api/notes/{noteId}/auto-tags
```

- 校验笔记归属与未删除；
- 笔记已有标签 → 原样返回，不调用 AI；
- 正文为空 → 原样返回；
- AI 异常/空结果/无候选 → 原样返回，不抛错；
- 成功且笔记标签仍为空 → `NoteService.addTagsIfEmpty` 写回并返回最新 VO。

该方法**不加类级事务**，避免 LLM 调用长时间占用数据库连接；写回使用独立事务方法。

---

## 4. 前端设计

### 4.1 表单收起

`NoteComposer` 在标题位置渲染一行轻量切换：

- 收起态文案：「添加标题、标签」；已有值时可显示「标题、标签（N）」。
- 展开态：标题 `Input`、`TagInput`、勾选框「以后默认展开」。

打开抽屉时：

- 读取偏好；为 `true` 则默认展开；
- 即使偏好为 `false`，若恢复的草稿里已有标题/标签，也自动展开，避免内容被藏起来。

**2026-11-03 反馈修正（内容优先）**：首版把元数据做成带 padding 的卡片、灵感标签换行铺满，正文被压到约 220px。现改为：

- 元数据不再用卡片，标题与「默认展开」同排，标签行仅保留轻微底色；
- 灵感标签改为按需展开，且只占一行横向滚动；
- 移动端 390×844 实测：收起 460px、展开元数据 380px、再展开灵感 334px；
- 正文始终 `flex-1`，是剩余空间的最大占有者。

### 4.2 保存与异步补标签

- `createNote` 的 payload 仍传用户输入；标题为空的自动生成在后端完成。
- 保存成功先关闭抽屉、清草稿、toast「记下了」。
- 若本次 payload 的 `tags` 为空且正文非空，后台调用 `autoTagNote(noteId)`：
  - 返回带标签 → `invalidateQueries(['notes'])`，toast 提示并附「撤销」；
  - 返回无标签/失败 → 静默。
- 撤销调用 `updateNoteTags(noteId, [])` 并刷新列表。

### 4.3 接口类型

`frontend/src/api/note.ts` 新增：

```ts
export function autoTagNote(noteId: number) {
  return request.post<NoteVO>(`/notes/${noteId}/auto-tags`)
}
```

---

## 5. 边界与约束

| 场景 | 行为 |
|---|---|
| AI 未配置 / 超时 / 报错 | 保存不受影响，笔记保持无标签 |
| 候选词库为空（新用户 + 无书标签） | 跳过 AI 调用 |
| AI 返回词库外的词 | 丢弃；全被丢弃则保持无标签 |
| 用户手动填了标签 | 不请求 AI |
| AI 返回前用户又加了标签 | 写回前再次检查，仍为空才写 |
| 草稿恢复出标题/标签 | 自动展开元数据区 |
| 标题正文为纯空白 | 标题保持 `null` |
| 编辑已有笔记 | 不触发自动整理 |

---

## 6. 实施清单

- [x] 后端：`NoteTitleGenerator` + 单测
- [x] 后端：`NoteTagResponseParser` + 单测
- [x] 后端：`AiScene.NOTE_AUTO_TAG`
- [x] 后端：`BookRepository` 标签列查询（热门 3000 本 + 10 分钟缓存）
- [x] 后端：`NoteAutoTagService` + `NoteController` 接口
- [x] 后端：`NoteService` 自动标题 / `addTagsIfEmpty`
- [x] 前端：`api/note.ts` 接口
- [x] 前端：`NoteComposer` 收起/偏好/异步补标签/撤销
- [x] 验证：后端单测 + 编译
- [x] 验证：前端 `npm run build`
- [x] 验证：无头浏览器验证收起态、展开态、偏好记忆、保存后 AI 标签与撤销（接口 mock）

---

## 7. 验证计划

1. **单元测试**
   - 标题：普通句、标题行、引用、列表、任务列表、链接/加粗、超长截断、空白。
   - 标签解析：纯 JSON、代码块包裹、混入解释文字、词库外标签、重复、超过 3 个、空数组。
2. **编译**
   - `backend: mvn -q compile`；相关单测 `mvn -q -Dtest=... test`。
   - `frontend: npm run build`。
3. **运行时（无头浏览器，接口 mock）**
   - 390×844：默认不见标题/标签输入框；点开出现；勾选「以后默认展开」后重开自动展开。
   - 只填正文保存：请求体 `title=null`；mock 返回自动标题 + AI 标签；toast 出现「撤销」；点撤销发出 `PUT /notes/{id}/tags` 且 body 为 `{"tags":[]}`。
   - 1440×900：抽屉仍从右侧弹出，交互一致。
4. **真实链路（如本地后端可用）**
   - 创建无标题笔记，确认返回值带自动标题。
   - 调用 auto-tags，确认只返回词库内标签；AI 未配置时确认 200 且标签为空。

---

## 8. 进度与偏差

| 日期 | 事项 | 结果 |
|---|---|---|
| 2026-11-03 | 方案落档 | 完成 |
| 2026-11-03 | 后端实现与单测 | `NoteTitleGeneratorTest` 5 项、`NoteTagResponseParserTest` 6 项、`NoteServiceCreateThoughtTest` 3 项全绿 |
| 2026-11-03 | 前端构建 | `npm run build` 通过 |
| 2026-11-03 | 无头浏览器（接口 mock） | 20 项断言全绿：移动端底部抽屉、桌面端右侧抽屉、默认收起、展开、偏好记忆、保存后 AI 标签、撤销、草稿清空 |
| 2026-11-03 | 真实后端 + 真实模型 | 测试笔记经 auto-tags 返回并落库 `中国历史 / 权力斗争 / 人性`，三个词均能在图书标签库中命中；测试行已删除 |

### 环境偏差（非本次代码引入）

1. **dev 库 `notes.status` 历史遗留列**：`notes` 表仍保留旧版 `status VARCHAR(16) NOT NULL` 且无默认值，当前实体不映射该列，导致 `POST /api/notes` 在本机 dev 库插入失败（500）。本次改动未触碰该列；auto-tags 的真实验证使用了一条带 `status` 值的临时行，验证后已 `DELETE`。若要恢复本机 API 建笔记，需要对 dev 库做一次无损迁移（如将 `status` 改为可空），这不在本次范围内。
2. **新 AI 场景需要绑定模型**：dev 库没有 `roles=TOOL/QA` 的默认配置，新场景不会自动回退。本地已把 `NOTE_AUTO_TAG` 绑定到现有 `ai-gateway`（`ai_provider_config.id=35`）用于验证；其他环境需在「AI 场景配置」里绑定一次，或配置 `roles=TOOL` 的默认模型。
