# KBook 前端重新设计方案

## 概述

将 KBook 从"AI 原生阅读平台"重新定位为**"AI 陪你聊书"的内容平台**。砍掉阅读器、书架、社区（书评/关注/通知），聚焦于：推荐引擎 + 辩论/圆桌 + AI 问答 + 速读卡片。

---

## 当前状态分析

### 保留的功能
- AI 书籍问答（RAG BookChatSheet）— 管理员上传的图书可问答
- 辩论（奇葩说）— 5 轮辩论流程 + 评分 + 报告
- 圆桌派 — 多角色讨论 + 覆盖度 + 报告
- 速读卡片 — AI 生成核心观点摘要
- 个性推荐 — 画像 + 向量 + 协同过滤
- MoodQuickSwitch — 意图 + 情绪选择器
- AI 助手 — 多轮对话 + @Tool
- 搜索 — ES 全文检索
- 用户画像/Onboarding
- 管理后台（仅管理员可见）

### 砍掉的功能
- 阅读器（ReaderPage + 所有 renderer 组件 + TTS）
- 书架（BookshelfPage）
- 社区：书评（ReviewsPage）、关注（FollowListPage）、通知（NotificationsPage）
- 排行榜（RankPage）— 无用户量无意义，合并到发现页
- 阅读进度 — 去掉进度条/进度同步，保留"已读/在读/想读"状态标记
- 垃圾桶（BookTrashPage）

### 导航结构变更

**当前 5 Tab**: 首页 / 榜单 / AI助手 / 书架 / 我的

**新 3 Tab**: 首页 / 发现 / 我的

- **首页**: MoodQuickSwitch + 推荐 + 速读卡片入口
- **发现**: 辩论/圆桌内容流 + 搜索 + 热门标签
- **我的**: 画像 + 阅读记录(想读/在读/已读) + 设置

AI 助手改为从书籍详情页和发现页的浮动按钮入口进入，不再是独立 Tab。

---

## 详细变更计划

### Phase 1: 路由与导航重构

#### 1.1 更新路由常量
**文件**: `frontend/src/constants/index.ts`

- 移除: `BOOKSHELF`, `RANK`, `READER`, `REVIEWS`, `NOTIFICATIONS`, `FOLLOW_LIST`, `TRASH`, `CHAT_ROOM`(保留 CHAT 作为 AI 助手入口)
- 修改: `AI` → 改为浮动入口，不再是 Tab 页
- 新增: `DISCOVER: '/discover'`, `READING_LIST: '/profile/reading-list'`
- 保留: `HOME`, `PROFILE`, `BOOK_DETAIL`, `SEARCH`, `DEBATE`, `DEBATE_SESSION`, `ROUND_TABLE`, `ROUND_TABLE_SESSION`, `CHAT`, 所有 ADMIN 路由, 所有 AUTH 路由

#### 1.2 更新路由配置
**文件**: `frontend/src/router/index.tsx`

- 移除路由: `BOOKSHELF`, `RANK`, `READER`, `REVIEWS`, `NOTIFICATIONS`, `FOLLOW_LIST`, `TRASH`, `HISTORY`(改为 READING_LIST)
- 新增路由: `DISCOVER` → DiscoverPage
- 修改: `HISTORY` → `READING_LIST` → ReadingListPage
- 移除 lazy import: ReaderPage, RankPage, BookshelfPage, ReviewsPage, NotificationsPage, FollowListPage, BookTrashPage, ReadingHistoryPage
- 新增 lazy import: DiscoverPage, ReadingListPage

#### 1.3 重写 TabBar
**文件**: `frontend/src/components/layout/TabBar.tsx`

3 Tab 设计:
```
首页 (Home icon) | 发现 (Compass icon) | 我的 (User icon)
```
- 移除中间的 BlinkingBot 浮动按钮
- 移除榜单和书架 Tab
- AI 助手改为全局浮动入口（见 Phase 2）

#### 1.4 重写 DesktopSidebar
**文件**: `frontend/src/components/layout/DesktopSidebar.tsx`

3 个导航项:
- 首页 (Home)
- 发现 (Compass)
- 我的 (User)

副标题改为"AI 书籍讨论平台"（原"智能阅读平台"）

#### 1.5 更新 AppLayout
**文件**: `frontend/src/components/layout/AppLayout.tsx`

- TAB_ROUTES 更新为 3 项: HOME, DISCOVER, PROFILE
- 移除 RankPage, BookshelfPage, AIPage 的 Tab 注册
- 新增全局 AI 助手浮动按钮（FAB），在移动端和 PC 端都显示

---

### Phase 2: 首页重新设计

#### 2.1 重写首页
**文件**: `frontend/src/pages/home/index.tsx`

新首页结构（从上到下）:

```
┌─────────────────────────────────────┐
│ Header: 问候语 + 搜索入口          │
├─────────────────────────────────────┤
│ MoodQuickSwitch（意图+情绪选择器）  │
├─────────────────────────────────────┤
│ 为你推荐 — 书籍卡片列表            │
│ (每张卡片: 封面 + 标题 + 速读摘要  │
│  + 匹配度 + 评分 + 3个入口按钮:    │
│  AI问答 / 圆桌 / 辩论)             │
├─────────────────────────────────────┤
│ 热门标签云                          │
└─────────────────────────────────────┘
```

关键变化:
- **移除"继续阅读"轮播** — 没有阅读器后无意义
- **移除阅读统计** — 简化为"想读/在读/已读"计数
- **推荐卡片增强** — 每张卡片直接展示速读摘要（1-2 句核心观点），不再需要点进去才看到
- **推荐卡片操作区** — 每张卡片底部 3 个快捷按钮: AI问答 / 圆桌 / 辩论，点击直接跳转
- **MoodQuickSwitch 提升为核心交互** — 放在搜索框下方，视觉权重最高

PC 端布局:
- 左栏(主): MoodQuickSwitch + 推荐列表
- 右栏(侧): 热门标签 + 阅读状态统计

#### 2.2 优化 MoodQuickSwitch
**文件**: `frontend/src/components/home/MoodQuickSwitch.tsx`

- 增加视觉权重：更大的卡片、更明显的选中状态
- 意图行增加文字描述（如"充电 → 提升技能和认知"）
- 情绪行增加文字标签（不只是 emoji）
- 切换后推荐列表实时刷新（已有，确认可用）

---

### Phase 3: 发现页（新页面）

#### 3.1 创建发现页
**文件**: `frontend/src/pages/discover/index.tsx`（新建）

发现页结构:

```
┌─────────────────────────────────────┐
│ Header: "发现" + 搜索入口          │
├─────────────────────────────────────┤
│ Tab 切换: 精选辩论 | 热门圆桌 | 全部书籍 │
├─────────────────────────────────────┤
│ 内容流:                             │
│ ┌─ 辩论卡片 ─────────────────────┐ │
│ │ 书名 + 辩题                     │ │
│ │ 正方观点摘要 vs 反方观点摘要    │ │
│ │ 参与角色头像 + 观看按钮         │ │
│ └─────────────────────────────────┘ │
│ ┌─ 圆桌卡片 ─────────────────────┐ │
│ │ 书名 + 讨论主题                 │ │
│ │ 参与角色列表 + 覆盖度进度       │ │
│ │ 观看按钮                        │ │
│ └─────────────────────────────────┘ │
│ ┌─ 书籍卡片 ─────────────────────┐ │
│ │ 封面 + 标题 + 速读摘要          │ │
│ │ 标签 + 评分 + 操作按钮          │ │
│ └─────────────────────────────────┘ │
└─────────────────────────────────────┘
```

关键设计:
- **辩论/圆桌从"用户主动创建"变为"内容消费"** — 展示已有的辩论/圆桌内容，用户直接观看
- **精选辩论** — 展示评分最高的辩论，每场辩论显示辩题、正反方核心观点、评分
- **热门圆桌** — 展示参与度最高的圆桌，显示讨论主题、角色列表、覆盖度
- **全部书籍** — 搜索 + 标签筛选 + 书籍列表（替代原 RankPage 和 SearchPage 的部分功能）
- 底部有"创建新辩论/圆桌"的入口按钮

#### 3.2 辩论/圆桌内容卡片组件
**文件**: `frontend/src/components/discover/DebateCard.tsx`（新建）
**文件**: `frontend/src/components/discover/RoundTableCard.tsx`（新建）

卡片设计:
- 辩论卡片: 书名 + 辩题 + 正方/反方观点摘要(各1-2句) + 评分 + 观看按钮
- 圆桌卡片: 书名 + 讨论主题 + 角色头像列表 + 覆盖度百分比 + 观看按钮

---

### Phase 4: 书籍详情页重新设计

#### 4.1 重写书籍详情页
**文件**: `frontend/src/pages/book/detail.tsx`

新详情页结构:

```
┌─────────────────────────────────────┐
│ Header: 返回 + 书名 + 收藏按钮     │
├─────────────────────────────────────┤
│ 封面(居中大图) + 标题 + 作者       │
│ 评分 + 匹配度 + 标签               │
│ 阅读状态: 想读 / 在读 / 已读       │
├─────────────────────────────────────┤
│ 速读卡片（核心观点 + 适合谁读      │
│  + 不适合谁读 + 收获）             │
├─────────────────────────────────────┤
│ 匹配度八维度详情                    │
├─────────────────────────────────────┤
│ AI 问答入口（大按钮）              │
├─────────────────────────────────────┤
│ 辩论/圆桌入口（2个大按钮）         │
│ - 查看已有辩论/圆桌                │
│ - 创建新辩论/圆桌                  │
├─────────────────────────────────────┤
│ 简介                                │
└─────────────────────────────────────┘
```

关键变化:
- **移除阅读进度条** — 不再有阅读器
- **移除评论区** — 社区砍掉
- **移除垃圾桶** — 简化
- **移除管理员编辑功能** — 管理员编辑在管理后台做
- **阅读状态替代书架** — "想读/在读/已读"三态切换，替代原来的"加入书架"
- **速读卡片提升为核心内容** — 直接展示在详情页，不再折叠
- **辩论/圆桌入口突出** — 两个大按钮，"查看已有"和"创建新的"
- **AI 问答保持 Sheet 形式** — 点击按钮弹出底部/右侧 Sheet

PC 端布局:
- 左栏: 封面 + 元信息 + 阅读状态 + 简介
- 右栏: 速读卡片 + 匹配度 + AI问答/辩论/圆桌入口

#### 4.2 阅读状态组件
**文件**: `frontend/src/components/book/ReadingStatusButton.tsx`（新建）

三态切换按钮: 想读 → 在读 → 已读
- 复用后端 ReadingProgress 的状态，但去掉进度百分比
- 样式: 3 个胶囊按钮，选中态高亮

---

### Phase 5: 个人中心重新设计

#### 5.1 重写个人中心
**文件**: `frontend/src/pages/profile/index.tsx`

新个人中心结构:

```
┌─────────────────────────────────────┐
│ 用户头像 + 昵称 + 画像标签         │
│ (MBTI / 职业 / 年龄段)             │
├─────────────────────────────────────┤
│ 阅读状态统计:                       │
│ 想读(12) | 在读(3) | 已读(28)      │
├─────────────────────────────────────┤
│ 菜单列表:                           │
│ - 阅读记录                          │
│ - 编辑画像                          │
│ - 对话风格                          │
│ - 修改密码                          │
│ - 关于/隐私/条款                    │
│ - 退出登录                          │
└─────────────────────────────────────┘
```

关键变化:
- **移除书架入口** — 改为"阅读记录"
- **移除通知/关注入口** — 社区砍掉
- **移除垃圾桶入口** — 砍掉
- **移除阅读统计详情** — 简化为三态计数
- **突出画像信息** — 显示 MBTI、职业、年龄段等标签，引导完善画像

#### 5.2 阅读记录页
**文件**: `frontend/src/pages/profile/reading-list.tsx`（新建，替代 history.tsx）

三 Tab 切换: 想读 / 在读 / 已读
- 每个列表显示书籍封面 + 标题 + 作者 + 状态切换按钮
- 支持移除记录
- 复用后端 ReadingProgress API，但只取状态不取进度

---

### Phase 6: AI 助手入口重新设计

#### 6.1 全局 AI 助手浮动按钮
**文件**: `frontend/src/components/layout/AiFab.tsx`（新建）

- 在 AppLayout 中添加全局浮动按钮（FAB）
- 移动端: 右下角浮动按钮（在 TabBar 上方）
- PC 端: 右下角浮动按钮
- 点击后打开 AI 助手 Sheet（底部/右侧弹出）
- 保留现有 AiChatService 的对话功能

#### 6.2 AI 助手 Sheet
**文件**: `frontend/src/components/ai/AiChatSheet.tsx`（新建）

- 从原 `pages/ai/index.tsx` 和 `pages/ai/room.tsx` 提取核心对话功能
- 改为 Sheet 形式，不占用独立页面
- 支持新建会话、历史会话列表、消息发送/接收

---

### Phase 7: 搜索页优化

#### 7.1 搜索页调整
**文件**: `frontend/src/pages/search/index.tsx`

- 移除"加入书架"按钮 → 改为"想读/在读/已读"状态按钮
- 搜索结果卡片增加速读摘要展示
- 搜索结果卡片增加快捷入口: AI问答 / 辩论 / 圆桌

---

### Phase 8: 辩论/圆桌页面调整

#### 8.1 辩论列表页调整
**文件**: `frontend/src/pages/book/debate.tsx`

- 增加"已有辩论"列表展示（当前只有创建新辩论的入口）
- 辩论列表显示: 辩题 + 正反方观点摘要 + 评分 + 观看人数
- 保留创建新辩论的功能

#### 8.2 圆桌列表页调整
**文件**: `frontend/src/pages/book/round-table.tsx`

- 增加"已有圆桌"列表展示
- 圆桌列表显示: 讨论主题 + 参与角色 + 覆盖度
- 保留创建新圆桌的功能

---

### Phase 9: 删除废弃文件

#### 9.1 删除页面文件
- `frontend/src/pages/reader/index.tsx`
- `frontend/src/pages/bookshelf/index.tsx`
- `frontend/src/pages/rank/index.tsx`
- `frontend/src/pages/reviews/index.tsx`
- `frontend/src/pages/notifications/index.tsx`
- `frontend/src/pages/follow/list.tsx`
- `frontend/src/pages/profile/trash.tsx`
- `frontend/src/pages/profile/history.tsx`
- `frontend/src/pages/ai/index.tsx` → 改为 Sheet
- `frontend/src/pages/ai/room.tsx` → 改为 Sheet
- `frontend/src/pages/user/profile.tsx` → 社区砍掉

#### 9.2 删除组件文件
- `frontend/src/components/reader/` — 整个目录（EpubRenderer, PdfRenderer, TxtRenderer, TtsFloatPlayer, SettingsPanel, TocPanel）
- `frontend/src/components/comment/` — 整个目录（社区砍掉）
- `frontend/src/components/common/ImageViewer.tsx` — 阅读器相关，可保留但不再从详情页调用

#### 9.3 删除/简化 Store
- `frontend/src/store/reader.ts` — 删除（阅读器状态）
- `frontend/src/store/progress.ts` — 简化（只保留阅读状态，去掉进度百分比）
- `frontend/src/store/tts.ts` — 删除（TTS 功能随阅读器砍掉）

#### 9.4 删除/简化 Hooks
- `frontend/src/hooks/useTxtReader.ts` — 删除
- `frontend/src/hooks/useEpubReader.ts` — 删除
- `frontend/src/hooks/usePdfReader.ts` — 删除
- `frontend/src/hooks/useTtsReader.ts` — 删除

#### 9.5 删除/简化 API
- `frontend/src/api/progress.ts` — 简化（只保留状态切换，去掉进度上报/批量同步）
- `frontend/src/api/bookshelf.ts` — 删除（改为阅读状态 API）
- `frontend/src/api/comment.ts` — 删除（社区砍掉）
- `frontend/src/api/notification.ts` — 删除（社区砍掉）
- `frontend/src/api/follow.ts` — 删除（如存在，社区砍掉）

#### 9.6 删除/简化常量
- `frontend/src/constants/index.ts` 中移除: `READER_THEMES`, `BOOK_FORMAT`, `STORAGE_KEYS.READER_SETTINGS`, `STORAGE_KEYS.LOCAL_PROGRESS`, `STORAGE_KEYS.PENDING_PROGRESS`, `STORAGE_KEYS.TTS_SETTINGS`

#### 9.7 删除工具函数
- `frontend/src/utils/tts.ts` — 删除

---

### Phase 10: 管理后台保留但隔离

管理后台页面保持不变，但确保:
- 阅读器入口只在管理后台可见（已有 `isAdmin` 判断）
- TTS 配置页面保留（管理员可能需要）
- 图书管理、AI 配置保留

---

## 实施顺序

1. **Phase 1** — 路由与导航重构（基础，其他改动依赖此步骤）
2. **Phase 9** — 删除废弃文件（先清理，再建设）
3. **Phase 2** — 首页重新设计
4. **Phase 3** — 发现页创建
5. **Phase 4** — 书籍详情页重新设计
6. **Phase 5** — 个人中心重新设计
7. **Phase 6** — AI 助手入口重新设计
8. **Phase 7** — 搜索页优化
9. **Phase 8** — 辩论/圆桌页面调整
10. **Phase 10** — 管理后台确认

---

## 假设与决策

1. **后端 API 不改** — 前端重新设计只改前端，后端 API 保持兼容。书架 API (`/api/bookshelf`) 仍然存在，前端不再调用；阅读进度 API 简化调用但后端不改
2. **阅读状态复用 ReadingProgress** — "想读/在读/已读"用 ReadingProgress 的状态字段表示，不新建 API
3. **AI 助手改为 Sheet** — 不再是独立页面，而是从 FAB 打开的 Sheet
4. **辩论/圆桌的"已有内容列表"** — 需要后端提供列表 API（当前已有 `GET /api/books/{id}/debate/sessions` 和 `GET /api/books/{id}/round-table/sessions`）
5. **发现页的"精选辩论"** — 需要后端提供全局辩论/圆桌列表 API（跨书籍），如果后端暂无此 API，发现页先展示书籍列表 + 热门标签

---

## 验证步骤

1. `npm run build` 零错误
2. 所有保留的路由可正常访问
3. 首页: MoodQuickSwitch 切换后推荐刷新
4. 发现页: 辩论/圆桌/书籍列表正常展示
5. 书籍详情: 速读卡片、AI 问答、辩论/圆桌入口正常
6. 个人中心: 阅读状态切换正常
7. AI 助手 FAB: 点击弹出 Sheet，对话正常
8. 搜索: 结果展示正常，状态切换正常
9. 管理后台: 所有功能不受影响
10. 移动端 + PC 端响应式布局正常
