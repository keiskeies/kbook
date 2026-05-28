# KBook 前端重构设计规范

> 版本: v1.0  
> 日期: 2026-05-27  
> 范围: 前端 UI/UX 全面重构

---

## 一、设计哲学

### 1.1 核心定位
KBook 不是工具，是「陪伴」。界面应该传递：温暖、安静、有品位。

### 1.2 设计原则
1. **少即是多** — 每页只保留一个核心行动点
2. **内容优先** — 界面元素不抢夺书籍内容的注意力
3. **反馈即时** — 用户的每个操作都要有视觉反馈
4. **一致性** — 同样的元素在不同页面表现完全一致

---

## 二、色彩系统（Color System）

### 2.1 主色更换

从冷调靛蓝（`#3B5BDB`）更换为**暖调苔藓绿**（`#5B8C5A`）。

理由：
- 绿色与「阅读」「自然」「成长」强关联
- 低饱和绿色长时间阅读不疲劳
- 在深浅模式下都有良好表现

### 2.2 完整色板

```css
/* === 品牌色 Brand === */
--brand-50:  #F0F7EF   /* 最浅背景 */
--brand-100: #D4E8D3   /* 悬停背景 */
--brand-200: #A8D1A7   /* 边框/分割线 */
--brand-300: #7DBA7C   /* 次要元素 */
--brand-400: #5B8C5A   /* 主色 Primary */
--brand-500: #4A7249   /* 按下状态 */
--brand-600: #3A5A39   /* 深色强调 */

/* === 功能色 Functional === */
--success:   #5B8C5A   /* 成功/正面 — 同主色 */
--warning:   #D4A574   /* 警告/提示 — 暖橙 */
--danger:    #C75B5B   /* 错误/删除 — 柔和红 */
--info:      #6B8FA8   /* 信息/链接 — 灰蓝 */

/* === 中性色 Neutral === */
--gray-0:   #FFFFFF   /* 纯白 */
--gray-50:  #FAFAF8   /* 页面背景（浅色模式） */
--gray-100: #F2F0EC   /* 卡片背景 */
--gray-200: #E6E2DC   /* 边框/分割线 */
--gray-300: #C9C3BA   /* 禁用文字 */
--gray-400: #9A948A   /* 次要文字 */
--gray-500: #6B655C   /* 正文 */
--gray-600: #4A453E   /* 标题 */
--gray-700: #2E2A24   /* 深色背景 */
--gray-800: #1A1814   /* 最深背景 */

/* === 深色模式映射 === */
.dark {
  --background:      #1A1814       /* gray-800 */
  --foreground:      #F2F0EC       /* gray-100 */
  --card:            #2E2A24       /* gray-700 */
  --card-foreground: #F2F0EC
  --primary:         #7DBA7C       /* brand-300 — 深色模式主色提亮 */
  --primary-foreground: #1A1814
  --muted:           #2E2A24
  --muted-foreground:#9A948A       /* gray-400 */
  --border:          #3D3832       /* 比 card 稍亮 */
  --ring:            #7DBA7C
}
```

### 2.3 使用规范

| 场景 | 颜色 | 禁止 |
|------|------|------|
| 主按钮背景 | `--brand-400` | 不可用渐变 |
| 主按钮按下 | `--brand-500` | 不可用纯黑阴影 |
| 卡片背景 | `--gray-100`（浅）/ `--gray-700`（深）| 不可用纯白/纯黑 |
| 页面背景 | `--gray-50`（浅）/ `--gray-800`（深）| 不可用 `#fff` / `#000` |
| 正文文字 | `--gray-500` | 不可用 `#000` |
| 次要文字 | `--gray-400` | 不可用 `#999` |
| 边框 | `--gray-200`（浅）/ `#3D3832`（深）| 不可用 `#eee` / `#333` |
| 成功/正面 | `--success` | 不可用亮绿 `#00C853` |
| 警告 | `--warning` | 不可用亮黄 `#FFD600` |
| 错误 | `--danger` | 不可用亮红 `#FF1744` |

---

## 三、间距系统（Spacing System）

基于 4px 基准单位：

```
space-1:  4px   /* 图标内边距、紧凑间距 */
space-2:  8px   /* 元素间最小间距 */
space-3:  12px  /* 卡片内边距、列表项间距 */
space-4:  16px  /* 标准间距 */
space-5:  20px  /* 段落间距 */
space-6:  24px  /* 模块间距 */
space-8:  32px  /* 大模块间距 */
space-10: 40px  /* 页面级间距 */
space-12: 48px  /* 最大间距 */
```

### 3.1 页面布局规范

```
页面水平内边距: 16px (space-4)
卡片内边距:     16px (space-4)
卡片圆角:       16px (space-4)
按钮圆角:       12px (space-3)
标签圆角:       9999px (全圆)
输入框圆角:     12px (space-3)
```

### 3.2 禁止的间距值

- ❌ `px-3.5`、`py-2.5` 等非 4 的倍数
- ❌ `gap-1.5`、`gap-2.5` 等非 4 的倍数
- ❌ `mt-1`、`mb-0.5` 等小于 4px 的间距
- ❌ 直接使用 `style={{ margin: 10 }}` 等硬编码

---

## 四、字体系统（Typography）

### 4.1 字号层级

| 层级 | 大小 | 字重 | 行高 | 用途 |
|------|------|------|------|------|
| Display | 28px | 700 | 1.2 | 页面大标题（仅首页 Hero） |
| H1 | 22px | 700 | 1.3 | 页面标题 |
| H2 | 18px | 600 | 1.4 | 模块标题 |
| H3 | 16px | 600 | 1.4 | 卡片标题 |
| Body | 14px | 400 | 1.6 | 正文 |
| Small | 12px | 400 | 1.5 | 辅助文字 |
| Caption | 11px | 500 | 1.4 | 标签、徽章 |

### 4.2 字体栈

```css
font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
```

### 4.3 使用规范

- 正文必须使用 `14px`，禁止用 `13px` 或 `15px`
- 标题字重最低 `600`，正文最高 `400`
- 单行文字截断用 `truncate`，多行用 `line-clamp-2`
- 禁止使用 `text-[9px]`、`text-[10px]` 等魔法值

---

## 五、阴影系统（Elevation）

定义 4 个层级，所有阴影统一使用：

```css
/* Elevation 1 — 卡片默认 */
--shadow-sm: 0 1px 2px rgba(26, 24, 20, 0.04), 0 1px 3px rgba(26, 24, 20, 0.02);

/* Elevation 2 — 卡片悬停、下拉菜单 */
--shadow-md: 0 4px 6px -1px rgba(26, 24, 20, 0.06), 0 2px 4px -2px rgba(26, 24, 20, 0.04);

/* Elevation 3 — 模态框、抽屉 */
--shadow-lg: 0 10px 15px -3px rgba(26, 24, 20, 0.08), 0 4px 6px -4px rgba(26, 24, 20, 0.04);

/* Elevation 4 — 悬浮按钮、重要提示 */
--shadow-xl: 0 20px 25px -5px rgba(26, 24, 20, 0.1), 0 8px 10px -6px rgba(26, 24, 20, 0.04);
```

### 5.1 使用规范

| 元素 | 默认 | 悬停/激活 |
|------|------|-----------|
| 卡片 | shadow-sm | shadow-md |
| 按钮 | 无阴影 | shadow-sm |
| 下拉菜单 | shadow-lg | — |
| 模态框 | shadow-xl | — |
| 悬浮按钮 | shadow-lg | shadow-xl |

---

## 六、动画系统（Motion）

### 6.1 时间规范

```
--duration-fast:   150ms   /* 按钮悬停、颜色变化 */
--duration-normal: 250ms   /* 页面切换、展开收起 */
--duration-slow:   400ms   /* 模态框、抽屉 */
--easing-default:  cubic-bezier(0.4, 0, 0.2, 1);
--easing-bounce:   cubic-bezier(0.34, 1.56, 0.64, 1);
```

### 6.2 页面切换动画

```css
/* 页面进入 */
.page-enter {
  animation: pageEnter 300ms var(--easing-default) forwards;
}
@keyframes pageEnter {
  from { opacity: 0; transform: translateY(12px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* 页面离开（可选） */
.page-exit {
  animation: pageExit 200ms var(--easing-default) forwards;
}
@keyframes pageExit {
  from { opacity: 1; transform: translateY(0); }
  to   { opacity: 0; transform: translateY(-8px); }
}
```

### 6.3 按钮交互反馈

```css
/* 按下缩放 */
.btn-press {
  transition: transform 150ms var(--easing-default), box-shadow 150ms var(--easing-default);
}
.btn-press:active {
  transform: scale(0.96);
}

/* 悬停上浮 */
.btn-lift {
  transition: transform 200ms var(--easing-default), box-shadow 200ms var(--easing-default);
}
.btn-lift:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}
```

### 6.4 列表项进入动画

```css
/* 列表项依次进入 */
.list-item-enter {
  animation: listItemEnter 400ms var(--easing-default) forwards;
  animation-delay: calc(var(--index) * 50ms);
  opacity: 0;
}
@keyframes listItemEnter {
  from { opacity: 0; transform: translateY(16px); }
  to   { opacity: 1; transform: translateY(0); }
}
```

### 6.5 骨架屏动画

```css
.skeleton {
  background: linear-gradient(90deg, var(--gray-100) 25%, var(--gray-200) 50%, var(--gray-100) 75%);
  background-size: 200% 100%;
  animation: skeletonShimmer 1.5s infinite;
}
@keyframes skeletonShimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
```

---

## 七、组件规范

### 7.1 按钮 Button

```
类型:
  - primary:   主按钮 — bg-brand-400, text-white, 用于主要行动
  - secondary: 次按钮 — bg-gray-100, text-gray-600, 用于次要行动
  - ghost:     幽灵按钮 — transparent, text-brand-400, 用于文字链接
  - danger:    危险按钮 — bg-danger, text-white, 用于删除

尺寸:
  - sm:  高 32px, 内边距 12px 16px, 字号 12px
  - md:  高 40px, 内边距 16px 20px, 字号 14px（默认）
  - lg:  高 48px, 内边距 20px 24px, 字号 16px

状态:
  - default:  正常状态
  - hover:    背景加深 5%, shadow-sm
  - active:   scale(0.96)
  - disabled: opacity 0.4, cursor not-allowed
  - loading:  显示 spinner, 禁止点击
```

### 7.2 卡片 Card

```
样式:
  - 背景: bg-gray-100（浅）/ bg-gray-700（深）
  - 圆角: rounded-2xl (16px)
  - 阴影: shadow-sm
  - 边框: 1px solid gray-200（浅）/ 3D3832（深）

悬停:
  - 阴影: shadow-md
  - 过渡: 200ms

禁止:
  - 禁止使用渐变背景（特殊情况需审批）
  - 禁止圆角超过 16px
```

### 7.3 输入框 Input

```
样式:
  - 背景: bg-gray-50（浅）/ bg-gray-800（深）
  - 圆角: rounded-xl (12px)
  - 边框: 1px solid gray-200, focus 时 border-brand-400
  - 内边距: 12px 16px
  - 字号: 14px

状态:
  - focus: ring-2 ring-brand-400/30, border-brand-400
  - error: border-danger, ring-danger/20
  - disabled: bg-gray-100, text-gray-300
```

### 7.4 徽章 Badge

```
类型:
  - default:   bg-gray-200, text-gray-600
  - primary:   bg-brand-100, text-brand-500
  - success:   bg-success/10, text-success
  - warning:   bg-warning/10, text-warning
  - danger:    bg-danger/10, text-danger

样式:
  - 圆角: rounded-full (全圆)
  - 内边距: 4px 10px
  - 字号: 11px
  - 字重: 500
```

---

## 八、页面级重构规范

### 8.1 首页 Home

#### 信息层级（从上到下）

```
1. 顶部导航栏（固定）
   - 品牌 Logo + KBook
   - 通知铃铛

2. Hero 区域
   - 个性化问候语
   - 搜索框（醒目）

3. 继续阅读（如果有数据）
   - 标题: "继续阅读"
   - 横向滚动卡片（大封面 + 进度条）
   - 无数据时隐藏整个模块

4. 为你推荐（如果有数据）
   - 标题: "为你推荐" + "基于你的画像"
   - 竖向列表（封面 + 书名 + 作者 + 匹配度）
   - 无数据时显示引导卡片

5. 发现
   - Tab 切换: 高分 / 新书 / 热门
   - 双列网格卡片

6. 阅读统计（弱化）
   - 折叠为单行统计数字
   - 点击展开详情
```

#### 禁止

- 禁止「阅读统计」占据超过一行的首屏空间
- 禁止模块之间没有明显分隔

### 8.2 书架页 Bookshelf

#### 布局变更

```
变更前: grid-cols-3（三列小封面）
变更后: grid-cols-2（双列大封面）

卡片内容:
  - 封面: 宽高比 3:4, 圆角 12px
  - 书名: 14px, 最多两行
  - 作者: 12px, 单行截断
  - 进度: 底部进度条（高度 3px）
  - 移除: 评分徽章、匹配度徽章（书架不需要这些）
```

#### 空状态

```
- 插画: 一个打开的书本 + 飘落的叶子（绿色调）
- 标题: "书架还是空的"
- 描述: "去首页发现好书，或搜索你感兴趣的书"
- 按钮: "去发现好书"（primary）
```

### 8.3 书籍详情页 Book Detail

#### 布局

```
1. 顶部返回栏（固定）
2. 封面 + 基本信息
3. 操作按钮行（加入书架、开始阅读、AI 问答）
4. 匹配度卡片（可展开）
5. 3分钟速读卡片（可展开）
6. 简介（展开/收起）
7. 标签
8. 元信息（文件大小、格式等）
```

#### 操作按钮规范

```
主按钮: "开始阅读" — primary, 全宽
次按钮行:
  - 加入书架 — secondary, 图标 + 文字
  - AI 问答 — secondary, 图标 + 文字
  - 更多操作 — ghost, 图标
```

### 8.4 AI 问答页 AI Chat

#### 优化点

```
1. 消息气泡:
   - 用户: bg-brand-400, text-white, 圆角 16px 16px 4px 16px
   - AI: bg-gray-100, text-gray-500, 圆角 16px 16px 16px 4px
   - 最大宽度: 85%

2. 快捷操作:
   - 输入框上方显示 3-4 个快捷问题
   - 点击直接发送

3. 空状态:
   - 品牌吉祥物（绿色小书）
   - 欢迎语 + 能力介绍
   - 热门问题推荐

4. 输入框:
   - 增加语音输入按钮（预留位置）
   - 发送按钮: 圆形, bg-brand-400
```

---

## 九、实现清单

### Phase 1: 色彩系统迁移

- [ ] 更新 `index.css` 颜色变量
- [ ] 更新 `tailwind.config.js` 颜色映射
- [ ] 全局替换旧颜色值
- [ ] 验证深浅模式一致性

### Phase 2: 设计 Token 提取

- [ ] 创建 `src/styles/tokens.css`
- [ ] 创建 `src/styles/animations.css`
- [ ] 重构按钮组件
- [ ] 重构卡片组件
- [ ] 重构输入框组件
- [ ] 重构徽章组件

### Phase 3: 书架重构

- [ ] `grid-cols-3` → `grid-cols-2`
- [ ] 封面尺寸放大
- [ ] 移除评分/匹配度徽章
- [ ] 优化空状态

### Phase 4: 首页重构

- [ ] 重排模块顺序
- [ ] 弱化阅读统计
- [ ] 优化继续阅读卡片
- [ ] 优化为你推荐列表

### Phase 5: 动画系统

- [ ] 页面切换动画
- [ ] 按钮交互反馈
- [ ] 列表项进入动画
- [ ] 骨架屏动画
- [ ] 卡片悬停效果

---

## 十、验收标准

1. **色彩**: 所有界面元素使用新色板，无旧蓝色残留
2. **间距**: 所有间距为 4px 倍数，无魔法值
3. **字体**: 所有文字使用规范字号，无 `text-[9px]` 等魔法值
4. **阴影**: 所有阴影使用 elevation 系统，无随意 shadow 值
5. **动画**: 每个页面切换有动画，每个按钮有反馈，每个状态变化有提示
6. **一致性**: 同样的元素在不同页面表现完全一致

---

## 附录: 参考资源

- **色彩灵感**: [Coolors - Moss Green Palette](https://coolors.co)
- **动画规范**: [Material Design Motion](https://m3.material.io/styles/motion/overview)
- **间距系统**: [Tailwind Spacing](https://tailwindcss.com/docs/customizing-spacing)
- **字体规范**: [Apple Human Interface Guidelines - Typography](https://developer.apple.com/design/human-interface-guidelines/typography)
