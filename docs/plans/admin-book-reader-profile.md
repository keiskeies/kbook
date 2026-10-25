# 管理后台 — 图书 × 读者画像交叉分析 设计文档

- 状态：待评审（Phase 1 可直接开工）
- 创建：2026-10-24
- 涉及端：后端 `admin` 模块 + 前端 `pages/admin/dashboard`
- 关联代码：
  - `backend/src/main/java/com/kbook/service/admin/AdminDashboardService.java`（现有画像聚合在 653-670 行）
  - `backend/src/main/java/com/kbook/dto/admin/DashboardResponse.java`（`UserProfile` 在 213-217 行）
  - `backend/src/main/java/com/kbook/controller/AdminDashboardController.java`
  - `frontend/src/pages/admin/dashboard.tsx`、`frontend/src/api/adminDashboard.ts`

---

## 1. 背景与目标

现有看板只有**全站**用户画像（`userProfile.mbtiDistribution / genderDistribution / statusDistribution`）和**单书热度**（`contentHeat.hotBooks`，按问答/讨论次数排序）两套彼此无关的数据。管理员看得到"哪本书火"，但看不出"这本书是谁在读"。

本功能要回答三个问题：

1. **一本书的读者长什么样** —— 性别、年龄、MBTI、婚育等结构。
2. **这个结构和全站比是否异常** —— 一本书 72% 女性读者，而全站 58%，才是有效信息。
3. **反过来，一类读者最爱读什么** —— "25-30 岁女性读者读完最多的是哪 10 本"。

### 非目标（本期明确不做）

- 不做人群圈选后的推送/运营触达（只有分析，没有动作）。
- 不做读者个体明细钻取（不列"某某用户读过这本书"）。
- 不做画像随时间的漂移趋势（Phase 3 再说）。
- 不引入新的画像采集表单，字段全部用 `users` 表现有列。

---

## 2. 核心口径定义

### 2.1 读者池（`basis` 参数）

同一本书在不同口径下人数能差一个数量级，所以口径必须是显式参数，不能藏在一个默认值里。

| `basis` | 含义 | 数据源 | 说明 |
|---|---|---|---|
| `READ`（默认） | 真正翻开过 | `reading_progress.progress > 0` | 主口径。有进度行即算 |
| `TOUCH` | 触达/想读 | `bookshelf` ∪ `user_read_history` | 噪声大，含大量"加了书架没看" |
| `DEEP` | 深度互动 | `book_reviews` ∪ `note(book_id)` ∪ `ai_sessions(book_id)` | 样本小但质量最高，适合分析口碑 |

⚠️ `DEEP` 实测几乎等于 `ai_sessions`：本地库 `book_reviews` **0 行**、未删除且带 `book_id` 的 `notes` 仅 2 行，而带 `book_id` 的 `ai_sessions` 有 380 行；三口径并集去重后共 11 人。也就是说"深度互动"目前实际含义是**"和这本书聊过天的人"**，UI 文案不要写成"评论/笔记人群"，否则管理员会以为口碑数据存在。上线前用同样三条 SQL 在生产库复核一次比例。

并集口径内部按 `user_id` 去重：一个人同时有进度和书评，在 `READ` 里算 1 人，在 `DEEP` 里也算 1 人，不累加。

三条实测约束（本地 `kbook-dev`，见附录 A）：

1. **口径之间没有包含关系，不能假设单调。** 直觉上是"触达 ≥ 阅读 ≥ 深度"，实测 21 本有进度的书里：与 `bookshelf` 单表比，**21/21 全部是 `READ > TOUCH`**（读者有进度行、书架里却没这本书）；与 `bookshelf ∪ user_read_history` 并集比，仍有 1 本反例、其余只是并列而非递增。所以三个 `basis` 是三个**并列视角**，不是漏斗；UI 文案与图形都不要画成"触达→阅读→深度"的递减关系，测试也不能断言单调（详见 11 节）。
2. **读者池统一过滤 `users.status = 'APPROVED'`。** 实测 36 个账号里 23 个 `PENDING`、1 个 `BANNED`，仅 12 个已通过。不过滤的后果：未填写桶被未审核账号填满、`siteRatio` 基线被拉偏、反查榜前排可能是根本没进过 App 的账号。三口径 SQL 一律 `JOIN users u ON u.id = ... AND u.status = 'APPROVED'`。**实现上这条只有一处定义**（`ProfileReaderSql.APPROVED`，附录 K）：它曾在服务里被逐条 SQL 手写七遍，而漏写任何一遍都不报错、只会让未审核账号静默进画像。
   ⚠️ 代价要提前知道：画像字段的填写**大量发生在 `PENDING` 账号上**，过滤后有效样本直接腰斩（`education` 全站 10 人填写 → 已通过账号仅 2 人；`annual_income` 10 → 2）。P1 的 `coverage` 会因此更低，见 4.1 的阈值校准要求。
3. **`DEEP` 的 `ai_sessions` 分支必须限定会话类型。** 表上 `ai_sessions(book_id)` 这句话省略了一个前提：带 `book_id` 的会话行**只有**图书问答。实测成立（381 行全是 `book_chat`，11 人），且唯一写 `book_id` 的地方是 `BookChatService`——所以它是**靠写入端的习惯成立的**，而 `AiSession.java` 的类型注释还写着三个早已不用的值。不补这条过滤，将来谁给助理会话也带上 `book_id`，"和这本书聊过天的人"会静默变成"用过 AI 助理的人"，cohort 分母虚高 → 该走保护态的书出分布（第 4 节规则 1 反向失效）。落点与取证见**附录 M**：类型集合收成 `BookChatScope` 一处，写侧、看板问答数、画像 DEEP 三处引用它。

### 2.2 时间窗

沿用看板现有区间语义（`days` = 7 / 30 / 90 / 0=全部；或 `start`+`end`）。

- `READ`：按 `reading_progress.updated_at` 落在区间内 —— 语义是**"这段时间里还在读的人"（活跃口径）**，不是"这段时间开始读的人"。
  两种口径谁大谁小并不固定（`updated_at ≥ created_at`，区间集合互不包含），实测 30 天窗：按 `updated_at` 11 人、按 `created_at` 2 人。选定 `updated_at` 后需在 UI 注明"按最近阅读时间归属"，否则和"新增读者"这类说法混起来会算错。
  代价：读者一旦不再产生新进度就掉出区间，历史区间必然小于"全部"区间 —— 这是 12 节里"区间内读者偏少"那条风险的来源，`days=0`（全部）要作为兜底口径常开。
- `TOUCH`：`bookshelf.added_at` / `user_read_history.created_at`。
- `DEEP`：`book_reviews.created_at` / `notes.created_at` / `ai_sessions.created_at`。
- 画像字段本身取**当前值**（快照式）。用户今天填了 MBTI，会被计入他过去读过的所有书的画像。这一点必须写进 UI 说明，否则会出现"上个月画像和现在一样"的疑惑。

### 2.3 有效样本

某维度下 `count(读者 WHERE 该字段 IS NOT NULL AND <> '')`。该值为 0 时该维度整体置灰并提示"无人填写"。

---

## 3. 指标体系

每个维度（dimension）下含若干桶（bucket），每个桶输出：

| 指标 | 公式 | 用途 |
|---|---|---|
| `count` | 该书该桶人数 | 绝对量 |
| `ratio` | `count / 该维度有效样本` | 书内结构 |
| `siteRatio` | 全站同桶人数 / 全站该维度有效人数 | 基线 |
| `lift` | `ratio / siteRatio` | **核心指标**，>1 表示这本书偏该人群 |
| `significant` | 见 3.2 | 该 lift 是否可信 |

维度级另附：

| 指标 | 说明 |
|---|---|
| `coverage` | 该维度填写率 = 有效样本 / 读者总数；**必须展示**，否则 20 人里 15 个女性→75% 会严重误导 |
| `readers` | 该书读者总数（去重） |
| `sampleProtected` | 是否触发小样本抑制（见 4） |

### 3.1 结论文案（确定性，不调 LLM）

`highlights: string[]` 由后端按规则拼，最多 3 条，规则固定：

1. 取 `lift >= 1.3 && significant` 且 `count >= 20` 的桶，按 lift 降序 → "女性读者占比 72%，为全站 1.24 倍"。
2. 取 `lift <= 0.7 && significant` 且 `count >= 20` → "18 岁以下读者几乎空白（占比 1%，全站 9%）"。
3. 无显著项时 → "读者结构与全站接近，无明显人群偏好"。

理由：符合项目既有约定 A3（可确定内容优先预写），成本为 0 且可复现，不受模型抖动影响。

### 3.2 显著性

不做完整假设检验，用两条硬阈值即可（够用且可解释）：

- `count >= 10` 才参与 `significant` 判定；
- 用 Wilson 区间下界判断"是否显著高于基线"，上界判断"是否显著低于"，实现放在 `BookProfileCalculator`，公式单测覆盖。

这两条阈值和 3.1 的 `count >= 20` 一样是**按生产量级拍的**，与 `min-cohort-size` 属同一族参数，4.1 校准阈值时一起调，不要只改 k-匿名那一项留下"分布图出来了但没有一条结论"的空档。

理由：小样本下 `lift=3.0` 经常只是 4 个人里 3 个巧合。宁可少下结论。

---

## 4. 隐私与 k-匿名（硬性要求）

单书画像是**反向识别风险很高**的数据：某书只有 3 个读者且都是女性、31-40 岁，等于点名了人。

规则：

1. 读者总数 `< min-cohort-size`（默认 10） → 只返回 `readers` 与 `sampleProtected=true`，所有 `dimensions` 返回空数组。
2. 单个桶 `count < min-bucket-size`（默认 5） → 该桶并入 `其他`，不单独展示。
3. `其他` 桶本身如果也是小样本，同样不展示 lift。
4. 任何接口不返回 `user_id` 列表、不返回昵称、不返回可组合出个体的多维明细（即禁止"性别=女 AND 年龄=31-40 AND MBTI=INFJ"这类 AND 查询下钻到人）。
5. 画像反查（`profile-books`）同样受读者数阈值约束：某书在该人群下 `count < min-bucket-size` 不进榜。
6. 四个端点（`book-profile` / `profile-books` / `profile-dimensions` 与看板主接口）只允许 ADMIN 角色访问——这是前面 5 条成立的前提：任何一条只要能被普通用户调用，就等于把"某书读者全是 31-40 岁女性"这种断言开放给了被画像的当事人。（2026-10-25 补：实现早已落在 `AdminDashboardController` 类级 `@PreAuthorize("hasRole('ADMIN')")`，是**文档漏写**而非代码漏做；接口层取证见附录 H.2。）

阈值集中成配置项（`admin.book-profile.min-cohort-size` 默认 10、`min-bucket-size` 默认 5），落在 `application.yml`，由 `BookProfileCalculator` 读取，**不写死常量**。

> 规则 1–5 逐条的接口层取证（401/403/200 矩阵、标识符泄漏扫描、AND 下钻、保护态最小暴露、count 闸门）见**附录 H**；H.3 记录了规则 5 的一处实现偏离与改正，H.8 是在浏览器里的复核，H.9 把这道闸门抽成 `Thresholds.isCountPublishable` + `BookProfileCalculator.rankingCandidates` 并做了变异测试——规则 2（并桶）与规则 5（出榜）现在共用同一个判定。H 之后又在**附录 I** 于当前构建上整体重跑（14 条 0 FAIL，并修掉入参笔误被报成 500 的一处），H.8 那条「admin 深链原因未定位」由 I.7 定位为脚手架的双重编码，不是产品缺陷。

### 4.1 实测校准结果（阈值 10/5 目前会把页面清空）

本地库实跑：有阅读进度的书共 21 本，读者数分布是 `1-4 人：21 本；5-9：0；10-19：0；>=20：0`。也就是说按 `MIN_COHORT_SIZE=10`，**21 本书全部走保护分支，一张分布图都出不来**。

结论与处理：

1. 上线前必须先在生产库跑同一条分布 SQL（`SELECT c, COUNT(*) FROM (按书统计 DISTINCT user_id) GROUP BY c`）做校准，再定阈值。开发库只有 3 个真实读者，不能代表生产。（脚本已在 dev 上端到端跑通、18 条语句 0 失败，见附录 AO）
2. 阈值做成配置项而不是常量，就是为了这一步：如果生产数据也偏薄，允许在不改代码的情况下降到 5/3，或按维度差异化（性别 5、年龄 10、MBTI 15——维度越细越容易反推到人）。
3. 保护态必须有**可用的降级展示**，不是空白：显示"该书 8 位读者，样本过小不提供分布，仅显示总人数与评分"，并把"读者数"和阈值一起给出来，管理员才知道是被保护还是真没人读。
4. 如果校准后绝大多数书都落在保护分支，说明这套功能该反过来做——不做单书画像，而做**人群选书**（`profile-books`，样本天然聚合到大盘），P2 提到 P1 之前。这条是本次验证带来的最大路线变化，需要产品决策。

---

## 5. 维度清单（全部落在现有列上，零新增字段）

> ⚠️ 本表的「实测存储值」来自 2026-10-24 对本地库的实跑校验，不是推测。**DB 里存的不是中文**，接口出入参、分桶、显示名三件事必须分开处理。

| `dimKey` | DB 列（`users`） | 实测存储值 | 分桶 | 填写率(实测) |
|---|---|---|---|---|
| `GENDER` | `gender` | `MALE` / `FEMALE` | 枚举映射为「男/女」显示 | 33.3% |
| `AGE` | `birthday` | DATE | `<18` `18-24` `25-30` `31-40` `41-50` `>50` | 27.8% |
| `MBTI_TYPE` | `mbti` | `INTJ`/`ENTJ`/`INFP`/`ISTP`/`ENFP`（4 位大写，干净） | Top 6 + `其他` | 36.1% |
| `MBTI_AXIS` | `mbti` | 同上 | `E/I` `S/N` `T/F` `J/P` 各两桶 | 36.1% |
| `MARITAL` | `is_married` | 存 `1`（tinyint 布尔） | 已婚 / 未婚 | 25.0% |
| `CHILDREN` | `has_children` | 存 `1` | 有孩 / 无孩 | 25.0% |
| `CHILD_AGE` | `children_age_ranges` | `children_3_6`、`children_no_children,children_3_6` | 逗号分隔 token，一人可进多桶 | **8.3%** |
| `OCCUPATION` | `occupation` | `ENGINEER`/`Engineer`/`engineer`/`TECH`/`hacker`/`TECH,FINANCE,EDUCATION,FREELANCE` | 逗号多值 + 大小写混乱 | 33.3% |
| `EDUCATION` | `education` | `SELF`/`Master`/`Bachelor`/`BACHELOR`/`COLLEGE`/`PhD` | 大小写变体混存 | 27.8% |
| `INCOME` | `annual_income` | `MEDIUM`/`100k`/`100K`/`150K_300K`/`300K_500K`/`500K`/`{{7*7}}` | 大小写 + 格式 + **脏值** | 27.8% |
| `ENTREPRENEURSHIP` | `entrepreneurship` | `false`/`true`/`NOT_INTERESTED`/`Startup`/`Tech Startup`/`{{7*7}}` | 布尔与枚举混存 + **脏值** | 27.8% |
| `CHAT_STYLE` | `book_chat_style` | `DEEP`×29 / `WITTY` / `CASUAL` | 原值映射显示名 | **86.1%** |

`mood` 实测仅 5.6%，不进 P1/P2。实体字段名与列名不同：`aspirationEducation→education`、`aspirationIncome→annual_income`，DTO 映射时注意。

上表填写率按**全部 36 个账号**算。按 2.1 的口径（只算 `APPROVED` 12 人）实测有效样本更薄，P1 的 `coverage` 会直接反映这一点：

| 维度 | 全站填写(36 人) | APPROVED(12 人) | 有效样本 |
|---|---|---|---|
| `gender` | 12 | 6 | 6 人 |
| `mbti` | 13 | 5 | 5 人 |
| `birthday`(→AGE) | 10 | 6 | 6 人 |
| `is_married` / `has_children` | 9 | 5 | 5 人 |
| `children_age_ranges` | 3 | 3 | 3 人 |
| `occupation` | 12 | 4 | 4 人 |
| `education` / `annual_income` / `entrepreneurship` | 10 | **2** | 2 人 |
| `book_chat_style` | 31 | 7 | 7 人 |

也就是说 dev 库里 P2 那几个维度（学历/收入/创业）**有效样本只有 2 人，任何分桶都必然被 k-匿名吃掉**。`AGE` 实测生日区间 1990-07-15 ~ 2000-01-01，只会落进 `25-30`/`31-40` 两个桶，其余桶恒空 —— 恒空桶不显示（区别于"无人填写"）。

### 5.1 归一化（新增的强制环节，实测逼出来的）

原始值不能直接当桶标签，`BookProfileCalculator` 前必须过一层 `Normalizer`：

1. **枚举白名单**：每个 `dimKey` 声明合法 token 集（大小写不敏感）。不在白名单里的一律归入 `脏值`，且 `脏值` 桶**不参与 lift、不进反查、不参与榜单**。
   —— 实测 `annual_income`/`entrepreneurship` 里存着 `{{7*7}}`，说明注册接口对这些字段没有取值校验。它不会在 FreeMarker 里被执行（FreeMarker 用 `${}`），但如果把它原样当桶标签展示，看板会出现一行 `{{7*7}} 1 人`，且可被恶意用户用来在看板上刷字。
2. **大小写/别名折叠**：`master`/`Master`/`MASTER` → `Master`；`100k`/`100K` → `100K`。别名表做成枚举里的静态映射，不做模糊匹配。
3. **多值字段**：`occupation`、`children_age_ranges` 是逗号分隔，一人进多桶，`ratio` 分母仍是"该维度有效人数"，因此**各桶占比之和可能 > 100%**，UI 必须标注。
   实测两份额外的麻烦：
   - `occupation` 同一列里并存**两套语义** —— 分类 token（`TECH`/`FINANCE`/`MEDICAL`/`EDUCATION`/`FREELANCE`）和职位名（`ENGINEER`/`engineer`/`Engineer`/`hacker`）。P2 归一化必须先决定"按分类"还是"按职位"，不能直接 Top 8 原值展示，否则 `ENGINEER`(4)、`Engineer`(3)、`engineer`(1) 会占掉三个榜位，其实是同一拨人。
   - `children_age_ranges` 的 token 带前缀（实测 `children_3_6`、`children_no_children,children_3_6`），需要 `children_*` → 中文显示名映射；且 `children_no_children` 与同行其他年龄段同时出现，和 `has_children` 语义打架 —— 该值一律视为"无孩"并**丢弃同一行的其余年龄段**，不参与多值计数。
4. **显示名映射**：`MALE→男`、`FEMALE→女`、`DEEP→深度型`……复用后端已有的枚举→中文映射习惯（参考 `AiScene.getDisplayName()` 的做法），前端不再自己做翻译。
5. **反查接口的 `value` 参数用存储值**（`?dim=GENDER&value=FEMALE`），`label` 才用于展示；这样避免中文 URL 编码与大小写歧义。

分桶规则集中在一张 `dimKey → SQL 表达式` 的映射表里（`BookProfileDimension` 枚举），既避免字符串拼接 SQL，也天然形成列白名单。

> ⚠️ 现有 `AdminDashboardService.getGroupCount(String column)` 把列名直接拼进 native SQL。新功能**不得**复用这个签名，必须走枚举白名单，否则 `dimKey` 变成注入点。（2026-10-25 附录 L：这个方法已经删掉了——它同时是「全站画像分布」的第三份口径副本，比注入面更贵。）

---

## 6. 接口契约

### 6.1 单书画像

```
GET /api/admin/dashboard/book-profile
    ?bookId=123            (必填, >0)
    &days=30               (7|30|90|0，默认 30；与 start/end 互斥)
    &start=2026-06-01      (可选，ISO 日期)
    &end=2026-10-24        (可选)
    &basis=READ            (READ|TOUCH|DEEP，默认 READ)
```

响应（`Result<BookProfileVO>`）：

```json
{
  "code": 0,
  "data": {
    "bookId": 123,
    "bookTitle": "被讨厌的勇气",
    "basis": "READ",
    "rangeDays": 30,
    "rangeStart": "2026-09-24",
    "rangeEnd": "2026-10-24",
    "readers": 312,
    "sampleProtected": false,
    "minCohortSize": 10,
    "profiledUsers": 243,
    "siteReaders": 4821,
    "basisNote": "按最近阅读时间归属，画像字段取当前值",
    "dimensions": [
      {
        "key": "GENDER",
        "label": "性别",
        "coverage": 0.78,
        "buckets": [
          { "value": "FEMALE", "label": "女", "count": 140, "ratio": 0.72, "siteRatio": 0.58, "lift": 1.24, "significant": true },
          { "value": "MALE", "label": "男", "count": 54,  "ratio": 0.28, "siteRatio": 0.42, "lift": 0.67, "significant": true },
          { "value": "", "label": "未填写", "count": 118, "ratio": 0.38, "siteRatio": 0.41, "lift": 1.02, "significant": false }
        ]
      }
    ],
    "highlights": [
      "女性读者占比 72%，为全站 1.24 倍",
      "25-30 岁读者为全站 1.6 倍"
    ]
  }
}
```

`buckets[].value` 是 **DB 存储值**，`label` 只是展示名（实测 `gender` 存的是 `MALE`/`FEMALE`，不是中文）。前端点桶下钻反查时，传的是 `value`，不是 `label` —— 见 5.1。
示例里的 `readers: 312` 是生产量级；dev 库当前单书最多 4 位读者，同参数请求会得到 `sampleProtected=true`（见 4.1）。

错误：

| 场景 | 返回 |
|---|---|
| `bookId` 不存在或已删除 | `code=1, message="图书不存在"` |
| `days` 与 `start/end` 同时给 | 以 `start/end` 为准（与主看板一致） |
| 自定义区间跨度 > 366 天 | `code=1, message="自定义区间最长支持 366 天"`（复用主看板常量 `MAX_CUSTOM_RANGE_DAYS`） |
| 读者数 < `min-cohort-size`（默认 10，见 4.1） | `code=0` + `sampleProtected=true` + `dimensions=[]` |

### 6.2 画像反查

```
GET /api/admin/dashboard/profile-books
    ?dim=GENDER&value=FEMALE   (必填；dim 走枚举校验，value 是 DB 存储值而非展示名)
    &days=30
    &basis=READ
    &limit=10                  (1..50)
    &sortBy=READERS            (READERS|LIFT，默认 LIFT)
```

响应 `Result<List<ProfileBookVO>>`，每项：`bookId, title, author, count, ratio, lift, readers`。
（2026-10-25 契约核对补正：实际还多一个 `siteRatio`，上面这行漏写，见附录 P。顺带量到 `siteRatio` 在两个响应里都只有 TS 声明、没有任何组件读过它——是死字段，不是缺陷，本轮没动。）
`count >= min-bucket-size`（默认 5）是**与 `sortBy` 无关的硬闸门**（4. 规则 5）：段内只有 1 个读者的书一旦上榜，`count=1 / readers=1 / ratio=1` 等价于"这本书的全部读者就是那 1 个人"，换个排序参数就能绕过隐私要求是不成立的。
（原设计把这条写成"`sortBy=LIFT` 时后端内部要求"，只从降噪角度考虑；2026-10-25 实测发现前端默认就是 `READERS`，等于默认路径不受闸门保护，已改正——见附录 H.3。）
降噪角度仍然成立，只是它是这条闸门的**副产品**，不是适用条件。

---

## 7. 后端设计

### 7.1 分层与新增文件

```
entity/…                          无新增
dto/admin/BookProfileVO.java      新增（含内部类 DimensionVO / BucketVO）
dto/admin/ProfileBookVO.java      新增
service/admin/BookProfileService.java       新增：编排、缓存、口径
service/admin/BookProfileCalculator.java    新增：分桶/lift/Wilson/k-匿名纯函数，便于单测
repository/ReadingProgressRepository.java   扩展：按 bookId + 时间窗取读者 id
service/admin/AdminDashboardService.java    基线复用（不改动现有行为）
controller/AdminDashboardController.java    新增两个 @GetMapping，只做参数校验与路由
```

Controller 不写业务；计算全部落 `BookProfileCalculator`；口径 SQL 落各 Repository。符合项目规范「Controller 只管参数校验和路由」。

**实现期更新**（2026-10-25，P2/P3 之后；上表只反映 P1）：

- `dto/admin/`：`BookAudienceFitVO`（内含 `Fit`）、`BookProfileTrendPointVO`、`BookProfileSnapshotResultVO`；
- `service/admin/`：`ProfileReaderSql`（读者池 SQL 唯一来源）、`BookProfileDimension`（15 维取值与白名单）、`AdminRangeRules`（时间窗判定）、`TargetAudienceRules`（书侧标签 → 画像桶）、`AudienceFitCalculator`（单书版吻合度纯函数）、`BookProfileSnapshotService`（定时入口 / 趋势读）与 `BookProfileSnapshotWriter`（事务写路径，见附录 AD）；
- `entity/BookProfileDaily` + `repository/BookProfileDailyRepository` + `db/migration/V4__create_book_profile_daily.sql`（附录 AB）；
- `controller`：5 个 GET（`book-profile`、`profile-dimensions`、`profile-books`、`book-audience-fit`、`book-profile-trend`）+ 1 个 POST（`book-profile-snapshot`）；
- 前端：`components/admin/BookProfileDrawer.tsx`、`BookProfileSection.tsx`、`AudienceFitBlock.tsx`、`ProfileTrendBlock.tsx`、`CollapsibleSection.tsx` 与 `pages/admin/profile-books.tsx`。

### 7.2 查询设计（两条 SQL 打天下）

```sql
-- ① 读者池（basis=READ）
SELECT DISTINCT rp.user_id
FROM reading_progress rp
JOIN users u ON u.id = rp.user_id AND u.status = 'APPROVED'
WHERE rp.book_id = :bookId
  AND rp.progress > 0
  AND rp.updated_at >= :start AND rp.updated_at < :end;
-- 图书存在性不在这条 SQL 里判：books 表没有 deleted_at（删除走 book_trash），
-- 由 Service 层先 getBookById 校验并取书名，查不到即返回"图书不存在"
-- status 过滤放在池里（见 2.1），基线 SQL 同样带这个条件，否则分子分母口径不一致

-- ② 该池的多维分布（一条查询出 4 个常用维度，避免 4 次往返）
SELECT
  COALESCE(NULLIF(UPPER(u.gender), ''), '未填写')                 AS gender_bucket,
  CASE WHEN u.birthday IS NULL THEN '未填写'
       WHEN TIMESTAMPDIFF(YEAR, u.birthday, CURDATE()) < 18 THEN '<18'
       WHEN TIMESTAMPDIFF(YEAR, u.birthday, CURDATE()) <= 24 THEN '18-24'
       WHEN TIMESTAMPDIFF(YEAR, u.birthday, CURDATE()) <= 30 THEN '25-30'
       WHEN TIMESTAMPDIFF(YEAR, u.birthday, CURDATE()) <= 40 THEN '31-40'
       WHEN TIMESTAMPDIFF(YEAR, u.birthday, CURDATE()) <= 50 THEN '41-50'
       ELSE '>50' END                                            AS age_bucket,
  COALESCE(NULLIF(UPPER(LEFT(u.mbti, 4)), ''), '未填写')           AS mbti_bucket,
  COUNT(*)                                                       AS cnt
FROM users u
WHERE u.id IN (:readerIds)
GROUP BY gender_bucket, age_bucket, mbti_bucket;
```

多维笛卡尔分组后在 Java 侧按维度分别累加（交叉桶数量最多 `4*7*17*3 ≈ 1400`，成本可忽略），比每维度一条 SQL 少 3 次往返。全站基线用同一表达式跑一次（去掉 `WHERE u.id IN`），并缓存。

`DEEP`/`TOUCH` 口径的读者池用 `UNION` 合并多张表后同样 `DISTINCT user_id`，注意各表的删除与时间列不同：

| 表 | 时间列 | 软删除过滤 |
|---|---|---|
| `bookshelf` | `added_at` | 无 |
| `user_read_history` | `created_at` | 无 |
| `book_reviews` | `created_at` | 无 |
| `notes` | `created_at` | **需 `deleted_at IS NULL`**（笔记是软删除） |
| `ai_sessions` | `created_at` | 无 |

### 7.3 索引缺口（必须先补，否则上线即慢查询）

| 表 | 现有索引 | 缺口 |
|---|---|---|
| `reading_progress` | 唯一键 `uk_user_book(user_id, book_id)` | 前导列是 `user_id`，**按 `book_id` 查走不了** → 需 `idx_progress_book(book_id, updated_at)` |
| `user_read_history` | 唯一键 `uk_user_book_action(user_id, book_id, action)` | 同上 → 需 `idx_history_book_action(book_id, action)` |
| `bookshelf` | 唯一键 `(user_id, book_id)` | 同上 → 需 `idx_shelf_book(book_id, added_at)` |
| `notes` | `idx_note_user_book(user_id, book_id)` | 按书查需 `idx_note_book(book_id)` |
| `book_reviews` | `idx_review_book_created(book_id, created_at)` | ✅ 已够 |
| `ai_sessions` | `idx_session_book_id(book_id)` | ✅ 已够 |

开发环境 `ddl-auto: update` 会自动建；生产同样能自动建，但要在部署清单里注明"本次含索引变更"，避免线上首查超时被误判为功能坏了。

**已注明**（2026-10-25）：`deploy/docs/deploy-guide.md` 第 7 节新增「本次发布含索引变更（图书 × 读者画像）」小节，列出 4 条索引名、阈值校准脚本 `deploy/sql/profile-threshold-audit.sql`、同镜像发布这条，以及 B.5 那条 `NOT NULL` 列必须走 Flyway 的同源风险。
**实现期的修正**（2026-10-25，见附录 T）：上表的索引**名字与一列**和实体声明不一致——编码时按 SQL 真正用到的列重建了索引，名字随之改了；`user_read_history` 那条实际是 `(book_id, created_at)`，因为画像 SQL 从不按 `action` 过滤，而窗口条件用的是 `created_at`。生效的是实体里的那四条（T.1），本表按各附录统一约定不回改原文。


### 7.4 缓存

- Caffeine 本地缓存（与 `ChatModelFactory` 同一套依赖，不引新库）：
  - `bookProfile`：key = `bookId|basis|rangeKey`，TTL 5 分钟，`maximumSize=500`。
  - `siteBaseline`：key = `dimKey|rangeKey`，TTL 10 分钟，`maximumSize=64`。全站基线变动慢，可比单书更久。
- 不做 Redis：管理后台低频、单实例读多写少，跨副本共享缓存收益抵不上序列化成本。
- 与看板现有的 5 分钟自动刷新节奏对齐，用户切 `basis` 时不会觉得卡。

**实现期的修正**（2026-10-25，见附录 H.7）：`siteBaseline` 实际是**单键 `site`、`maximumSize=4`**，不是 `dimKey|rangeKey`。原因不是偷工——基线那条 SQL 一次把 15 个维度全算出来（循环在 Service 里），且它的 `WHERE` 只有 `status='APPROVED'`、**没有任何时间谓词**，与 §3 里 `siteRatio = 全站同桶人数 / 全站该维度有效人数` 的人口口径一致。既然基线与时间窗无关，按 `rangeKey` 分键只会让同一份数据在缓存里重复存 15 份。`bookProfile` 那条按原设计实现，key 与 TTL 都一致。

**这一节曾有一个缺陷（2026-10-25，附录 Q），已按方案 A 修复（附录 Y）**：两条缓存的寿命仍是 5 / 10 分钟、互不知情，反查榜的分子也仍然不进缓存；区别是单书缓存键的第一段现在是**基线代号**——基线一重算，旧代号的条目整体失效，于是任何一个被返回的单书画像都由它那一次取到的基线算出，榜单的分子分母也在同一份快照下配对。代价是基线刷新后的第一波单书请求要重算（附录 Y.4 的 34→1→34 就是这个现象），Q.4 的方案 B（基线不缓存）不再考虑。

**这两个数已经改成配置项**（2026-10-25，附录 R）：`kbook.admin.book-profile.profile-cache-minutes`（env `KBOOK_PROFILE_CACHE_MINUTES`，默认 5）与 `…​.baseline-cache-minutes`（env `KBOOK_PROFILE_BASELINE_CACHE_MINUTES`，默认 10）。默认值没动，所以缺陷 Q 本身仍然存在——配置化只是把"把窗口压到 1 分钟"这条验证路径打开，让 Q.3 的复现不再需要先改代码。这两条键与 §11 的装配断言由 `BookProfileCacheConfigTest` 钉住。

---

## 8. 前端设计

### 8.1 文件

```
frontend/src/api/adminDashboard.ts                 扩展：BookProfileVO / ProfileBookVO / getBookProfile / getProfileBooks
frontend/src/components/admin/BookProfileDrawer.tsx 新增：右侧抽屉
frontend/src/components/admin/BookProfileSection.tsx 新增：抽屉内的图表组（可被反查页复用）
frontend/src/pages/admin/dashboard.tsx             改：内容热度书籍行加"查看读者画像"入口
frontend/src/pages/admin/book-profiles.tsx          新增（Phase 2）：画像反查独立页
```

### 8.2 抽屉结构

> 本节要点在 2026-10-25 做过逐条清点：多数早已满足，"环形/240px"随 B.2 的 DOM 比例条决定一并作废，"复用 `CollapsibleSection`"当时没做、由**附录 S** 补上（编译通过，运行时未验证）。按各附录的统一约定，上文原文不回改。

```
┌ 抽屉（宽 480，md 以下全宽） ─────────────┐
│ 书名 / 口径切换（触达·阅读·互动） / 时间范围继承主看板
│ 312 位读者 · 243 位填写了画像(78%)        ← coverage 常驻
│ ── 性别    环形图，中心显示女性占比
│ ── 年龄段  横向条形，右侧 lift 徽章
│ ── MBTI    4 条轴的对称条 + 16 型 Top6 条形
│ ── 婚育    两组小环形
│ 关键结论（最多 3 条，来自 highlights）
│ "这类读者还读什么 →"（Phase 2 跳反查页）
└──────────────────────────────────────────┘
```

要点：

- `sampleProtected=true` → 整块替换为提示「读者数不足 `min-cohort-size`，出于隐私不提供画像分布」，不画空图。阈值文案从接口返回（`VO.threshold`）而不是前端硬编码，否则 4.1 校准后调了配置、页面还在说"不足 10"。
- 每个桶同时显示 `count` 与 `ratio`，`lift` 用徽章：`>=1.3` 蓝、`<=0.7` 灰红、其余不上色。**不显著的桶不上色**，避免视觉误导。
- 数值格式化沿用看板现有的 `formatNumber/formatPercent`；注意 `undefined` 兜底 —— 现有 `formatTokens` 在收到 `undefined` 时会直接打印 "undefined"（`dashboard.tsx:43-48`，最后一行 `return String(n)`），新组件的格式化函数一律先 `if (!Number.isFinite(n)) return '—'`。这条是从生产事故里来的硬要求。
- 移动端：抽屉在 `<md` 全屏，图表高度固定 240px，避免之前遇到过的 0 高塌陷。
- 复用现有 `CollapsibleSection`，折叠状态记忆方式与主看板一致。

### 8.3 状态与数据流

`useQuery(['admin','book-profile',bookId, basis, rangeKey])`，`enabled=drawerOpen`，打开才请求；`placeholderData: keepPreviousData` 让切口径时不闪。区间字符串 `rangeKey` 复用主看板已有的 queryKey 生成逻辑，保证两边时间语义一致。

---

## 9. 性能估算

| 项 | 量级 | 结论 |
|---|---|---|
| 单书读者池 | 热门书最多约 10⁴ 级 user_id | 走 `idx_progress_book` 后为索引范围扫描 |
| 分布查询 | `IN (10⁴)` + 分组，输出 ≤ 1400 行 | 单条 SQL 可接受；若 `IN` 过长（>5000）改为子查询 `WHERE u.id IN (SELECT ...)`，让 MySQL 自己走半连接 |
| 全站基线 | 全表 `users`，量级 10⁴~10⁵ | 缓存 10 分钟，一天几十次，成本可忽略 |
| 首屏影响 | 抽屉懒加载，不进主看板 | 主看板零回归风险 |

Phase 1 上线后若单书查询 P95 > 800ms，再把 ② 改成 `EXPLAIN` 实测 + 子查询版本，不提前优化。

---

## 10. 分期

| 期 | 内容 | 验收 |
|---|---|---|
| **P1** ✅ 已完成（2026-10-24，见附录 B） | `book-profile` 接口 + 5 个维度（性别/年龄/MBTI 型/MBTI 轴/婚育）+ 索引 + k-匿名 + 抽屉 | 看板点开任意书能看到分布与 lift；读者 <10 的书走保护分支；`mvn compile` / `npm run build` 零错误；真实视口截图验证图表非空 |
| **P2** ✅ 已完成（2026-10-24，见附录 C） | `profile-books` 反查 + 职业归一化 + 收入/学历/创业/`book_chat_style` + 独立页 | 能回答"25-30 岁女性读完最多的 10 本"（注：反查是单维度接口，"25-30 岁女性"这类 AND 组合按第 4 节第 4 条**故意不支持**，只能分别看两个榜） |
| **P3** | 书侧 `target_reader_tags`（书标注的目标人群）与实际读者画像的**吻合度**打分；每日快照表 `book_profile_daily` 支持趋势 | 能输出"这本书标的是亲子人群，实际 60% 读者是未婚，标签错配"。**2026-10-25：口径定为单书版；接口、判定、抽屉区块与 `book_profile_daily` 每日快照全部完成并跑通（附录 Z、AB）** |

P3 的吻合度是这个功能真正的产品价值所在——`books` 表已有 `targetReaderTags` / `readerNeedTags`（Book 实体 64-72 行），把"声称的受众"和"实际的受众"并排，才让这套数据能指导选书和运营。

实测支持把它提前：`books` 22177 行里 `target_reader_tags` 与 `reader_need_tags` 非空各 **22174 行（99.98%）**，书侧数据完全就绪，不需要任何补录动作。
> **2026-10-24 复核修正**：「就绪」只到"列非空"这一层。展开词表实测（附录 E.2）后，`target_reader_tags` 有 **19797 个不同取值**、其中 70% 只出现过一次，`reader_need_tags` 几乎每本一套说法——**没有受控词表，就没有可比对的口径**。P3 的真正前置工作是标签归一化，不是样本量。
反过来，读者侧画像受 4.1 的小样本压制 —— **单书分布可能长期出不来，而"标签 → 全站人群结构"这条路不受影响**（全站 36/生产可能上万人为分母）。
因此建议的优先级重排：`P3 的吻合度拆成 P2.5，基于全站基线（`siteRatio`）先做"这本书标的是亲子人群，全站亲子人群占 X%"这种粗吻合，不依赖单书样本`；单书分布与反查按 4.1 校准结果再定先后。**需要产品决策，暂按原分期实施。**

---

## 11. 测试计划 ✅ 已完成（2026-10-24，见附录 D；当前构建上的整体重跑见附录 I；接口层那些断言已沉淀成可重跑脚本 `deploy/verify/admin-profile-matrix.mjs`，见附录 J，附录 L 加了需要外部真值的第 15 条；§6 的两端字段与参数名另有一条静态核对 `deploy/verify/profile-dto-parity.mjs`，见附录 P；单书与看板两侧的口径合并见附录 K、L、M；第 4 条的 SQL 一侧（维度取值形状）补于附录 O，缓存寿命与装配补于附录 R，§8 前端要点清点与抽屉折叠补于附录 S（折叠已由 S.5 在真实浏览器里应验，吻合度区块的可空分支由 AJ 复验），§7.3 索引名与实体的偏差、§9 首屏断言另见附录 T，文档里的路径引用由 `deploy/verify/doc-paths.mjs` 静态核对（附录 U），一次画像请求实际发几条 SQL 见附录 V，接口矩阵（含附录 L 第 15 行）在缓存配置化之后重跑 15/15、0 SKIP（附录 W），加入 P3 新端点、趋势端点、单格闸门与时间窗 11 行后 26/26（附录 Z.6、AB.6、AF.3、AG.2、AH.2、AS.1）；索引建成与 EXPLAIN 见附录 AC，逐节证据与强度见附录 X 的验证地图，当前 `mvn -o test` 247 例全绿（Q 的方案 A 见附录 Y，P3 单书版吻合度见附录 Z，每日快照见附录 AB，快照事务边界修复见附录 AD，定时路径应验见附录 AE，快照并发触发串行化见附录 AL，趋势展示见附录 AM），静态核对入口与验证脚本分层见附录 AA；看板折叠桶进饼图之后的真实视口回归与配色修复见附录 N）

`BookProfileCalculator` 是纯函数，单测覆盖：

1. `lift` 计算与 `siteRatio=0` 的除零保护。
2. Wilson 区间在 `count=9/10/20` 边界上的 `significant` 翻转。
3. k-匿名：`readers=minCohort-1` → 空 dimensions；`readers=minCohort+2` 且某桶 `count=minBucket-1` → 并入 `其他`；阈值由配置注入，测试用 10/5 与 3/2 两组各跑一遍（4.1 校准后阈值会变）。
4. 年龄分桶：`birthday=NULL`、未来日期、18 岁生日当天、60 岁边界。
5. MBTI 脏数据：`'INTJ'`、`'INTJ-A'`、`'intj'`、`'IN'`、空串。
6. `coverage=0` 时维度返回空桶而不是 100% 未知。
7. **归一化（实测出的必测项，5.1）**：
   - 注入串当脏值拦掉：`annual_income='{{7*7}}'`、`entrepreneurship='{{7*7}}'` 实测真实存在，必须落进 `脏值` 且不参与 lift/反查；
   - 大小写折叠：`Master`/`BACHELOR`/`Bachelor`（实测 education 混存）、`100k`/`100K`（实测 income 混存）归一后各只有 1 个桶；
   - 跨套枚举并存：`300K_500K`（区间制）与 `MEDIUM`（档位制）同时出现在 `annual_income`，`true`/`false`（布尔制）与 `NOT_INTERESTED`/`Startup`（枚举制）同时出现在 `entrepreneurship` —— 归一化表要能表达"同一列多套历史枚举"，未识别值一律进 `脏值`；
   - 逗号多值（`children_age_ranges`、`occupation`）一人进多桶，`ratio` 之和 > 1，断言不报错且不按 100% 归一。

接口层：

- `bookId` 不存在/已删除 → "图书不存在"；区间跨度 >366 报错文案与主看板一致；`days` 与 `start/end` 同时给以 `start/end` 为准。
- 读者池只含 `status='APPROVED'`（构造 1 个 PENDING 读者有进度，断言 `readers` 不计入它）。动态证据见 D.3（要写库，一次性）；静态一侧由附录 K 的 SQL 形状断言长期钉住——任何碰 `users` 的画像查询都必须带这条谓词，新加一条 SQL 忘了带就是测试红。
- **不要断言 `basis` 之间的单调关系。** 原文档写的 `TOUCH >= READ` 已被实测推翻（对 `bookshelf` 21/21 反例；对 `bookshelf ∪ user_read_history` 仍有 1/21 反例，见 2.1）。三口径各自独立取数，测试只断言"同一 `basis` 内并集去重后不重复计数"。
- 反查 `value` 传 DB 存储值（`FEMALE`）能命中、传展示名（`女`）应返回空而不是 500。

---

## 12. 风险与待决策

| 风险 | 处理 |
|---|---|
| ~~画像字段填表率可能很低~~ → **已确认成立**：实测全站 8.3%~86.1%，过滤 `APPROVED` 后学历/收入/创业只剩 2 个有效样本 | `coverage` 显式展示（3 节）；~~P2 前补"画像字段健康度"接口评估用户侧引导~~ **已落，见附录 E.1**；**收入/学历/创业三维度在生产库确认样本前不进 P2 榜单** |
| `reading_progress.updated_at` 语义导致"区间内读者"偏少（老读者不再更新就不进区间） | UI 注明"按最近阅读时间归属"；同时"全部"区间（`days=0`）作为默认可见口径。实测 30 天窗 `updated_at` 11 人 vs `created_at` 2 人，两口径都保留意义不大，坚持 `updated_at` 单口径 |
| 多维度交叉反查（`profile-books`）在小人群下等价于点名 | 严格套用第 4 节第 5 条的 `count >= min-bucket-size` 与榜单过滤 |
| `occupation` 自由文本污染榜单 | P2 前只做 Top 8 原样展示，不做基于它的反查；实测该列同时存分类与职位名（`TECH` 与 `hacker`），归一化未做完前反查会出假榜 |
| ~~**`users` 画像列没有取值校验**~~ → **写入端校验已落，见附录 F**；历史脏值按附录 AN 的决定保留原值、读取端归一（不做清洗） | 校验与看板分桶共用同一份白名单（`BookProfileDimension.canonicalForWrite`），新增「注册页有选项、看板当脏值」这类漂移已被单测钉住。**剩下的清洗是独立决策**：`education='SELF'`、`annual_income='MEDIUM'/'{{7*7}}'`、`entrepreneurship='Startup'` 语义已不可还原，NULL 化会永久丢信息，不能由看板顺手做 |
| 生产环境部署顺序（前端新、后端旧会整片 undefined，是本次已发生过的事故） | 本次改动含新接口 + 新索引，**必须同镜像发布**（`package/package.bat` 或 `deploy/download.sh` → `deploy/docker/build.sh` → `up -d`，不能只 `restart`），并在发布说明里写明 |
| **单书侧缓存 5 分钟、全站基线缓存 10 分钟，两条互不知情**：反查榜的分子每次现查、分母用 ≤10 分钟前的基线，`lift` 会在一段时间里是"新分子 / 旧分母"（dev 上审核 10 个账号能让它虚高约 1.8 倍），且 `significant` 不带任何可疑标记 | **已按方案 A 修复（附录 Y）**：基线代号进单书缓存键，基线重算即整体失效；运行时有 34→1→34 的正向对照与反向变异（去掉代号后第三条退回 17 条并返回旧基线算的图）。附录 Q/R 保留为缺陷记录、复现步骤与取舍依据 |
| **索引已建成并走对，但 dev 数据量小**：四条索引都在（`information_schema`），READ/notes/TOUCH 的按书查都是 `ref`；`bookshelf` 在聚合查询里仍被优化器选了全扫（6 行），生产要按同两条 `EXPLAIN` 复跑 | 证据见附录 AC（临时只读 Java 程序，复用后端 JDBC 驱动）；生产复跑需要数据量而非授权 |
| **§8.2 的抽屉折叠（附录 S.2）已应验，但证据强度不均**：共享组件抽取动了主看板 4 个面板的声明处；抽屉折叠的 11 条断言在真实浏览器里跑绿，其中只有第 7/8 条经变异证明会咬，第 9 条（换书不继承折叠态）经变异证明是空断言——`key` 里的 `bookId` 属于防御性写法 | 状态记在 S.3/S.4，结论在 S.5。不要把"11 条全绿"读成 11 条同等强度的证据；§8 其余要点仍是静态清点（S.1），没有逐条运行时覆盖 |

---

## 附录 A：设计验证基线（2026-10-24 实跑）

数据源：本地 `kbook-dev`（`localhost:3306`），用 MySQL 8.4 客户端逐条执行本文件里的 SQL 与等价校验查询。
**这是开发库，只有 3 个有阅读进度的真实账号，任何绝对量都不能当生产预期用。** 本附录的价值在于验证「结构、列名、索引、取值形态」这四类与数据量无关的假设；样本量相关的结论（4.1 阈值、`coverage`）必须在生产库重跑同一批 SQL 才算数。

### A.1 行数基线

| 表 | 行数 | 与本功能相关的切片 |
|---|---|---|
| `users` | 36 | `APPROVED` 12 / `PENDING` 23 / `BANNED` 1 |
| `books` | 22177 | `target_reader_tags` 非空 22174（99.98%）、`reader_need_tags` 非空 22174 |
| `reading_progress` | 247 | `progress > 0` 仅 21 行 = 21 本 = 3 人（且 3 人全为 APPROVED） |
| `bookshelf` | 6 | — |
| `user_read_history` | 133 | — |
| `book_reviews` | **0** | `DEEP` 口径目前无贡献（见 2.1） |
| `notes` | 4 | 未删除 2、带 `book_id` 2 |
| `ai_sessions` | 741 | 带 `book_id` 380 → `DEEP` 唯一主力来源 |

### A.2 假设对照结果

| 文档原假设 | 结论 |
|---|---|
| 零新增字段，画像列都在 `users` 上 | ✅ 成立（`education`/`annual_income` 等列名已逐个核对，注意实体属性名与列名不同） |
| 4 项索引缺口（`reading_progress`/`user_read_history`/`bookshelf`/`notes` 无 `book_id` 前导索引） | ✅ 成立，`information_schema.STATISTICS` 实测这四张表只有 `user_id` 前导的唯一键/索引；`book_reviews(book_id, created_at)`、`ai_sessions(book_id)` 确实已存在 |
| `books` 有 `deleted_at` | ❌ 已删（7.2 注释修正，删除走 `book_trash`） |
| 枚举存中文（`gender=女`） | ❌ 实存 `MALE`/`FEMALE`，反查参数改 `value=FEMALE`（5 / 6 节修正） |
| `TOUCH >= READ` 单调 | ❌ 推翻（2.1，11 节测试断言已删） |
| `MIN_COHORT_SIZE=10` / `MIN_BUCKET_SIZE=5` 可直接上线 | ❌ 会把 dev 的 21 本书全部打成保护态（4.1） |
| 时间窗按 `updated_at` 会少于 `created_at` 口径 | ❌ 实测相反：30 天窗 11 人 vs 2 人（2.2 修正为"活跃口径"表述） |
| 画像字段可信、可直接分桶 | ❌ 需强制归一化层（5.1）：`{{7*7}}` 脏值、大小写混存、同列多套枚举、`occupation` 分类与职位名混存 |
| P3 吻合度可实施 | ✅ 书侧标签 99.98% 覆盖，比单书分布更早可用（10 节建议提前） |

### A.3 生产上线前的最小复跑清单

在生产库执行，不改代码，只取数：

```sql
-- 1) 阈值校准：单书读者数分布，决定 min-cohort-size
SELECT CASE WHEN c < 5 THEN '1-4' WHEN c < 10 THEN '5-9' WHEN c < 20 THEN '10-19'
            WHEN c < 50 THEN '20-49' ELSE '>=50' END bucket, COUNT(*) books
FROM (SELECT rp.book_id, COUNT(DISTINCT rp.user_id) c
      FROM reading_progress rp JOIN users u ON u.id = rp.user_id AND u.status = 'APPROVED'
      WHERE rp.progress > 0 GROUP BY rp.book_id) t
GROUP BY bucket;

-- 2) 画像健康度：各维度在"有读者的人群"里的有效样本
SELECT COUNT(*) readers,
       SUM(u.gender IS NOT NULL AND u.gender <> '') gender,
       SUM(u.mbti IS NOT NULL AND u.mbti <> '') mbti,
       SUM(u.birthday IS NOT NULL) birthday,
       SUM(u.is_married IS NOT NULL) married,
       SUM(u.education IS NOT NULL AND u.education <> '') education
FROM users u WHERE u.status = 'APPROVED';

-- 3) 脏值体检：这三列实测存在注入串与多套枚举
SELECT education, COUNT(*) FROM users WHERE education IS NOT NULL AND education <> '' GROUP BY education;
SELECT annual_income, COUNT(*) FROM users WHERE annual_income IS NOT NULL AND annual_income <> '' GROUP BY annual_income;
SELECT entrepreneurship, COUNT(*) FROM users WHERE entrepreneurship IS NOT NULL AND entrepreneurship <> '' GROUP BY entrepreneurship;
```

判定规则：第 1 条里 `>=10` 档若不足总书数的 20%，先做人群选书（`profile-books`）再做单书画像；第 2 条里任一维度有效样本 `< 30` 则不进 P1 的五个维度；第 3 条出现任何非白名单值 → 归一化表按实测值补齐后再开工。

**已做成可执行脚本**（2026-10-25，见附录 G.6）：`deploy/sql/profile-threshold-audit.sql`。上面这段是手写查询、比例要人脑算，脚本把三条判定算在 SQL 里，并补了两处原文没有的东西：三口径各自的分布（原文只有 `READ`）与脏值的 `status` 拆分。已在 dev 跑通，纯只读。

---

## 附录 B：P1 实现落点与文档偏差（2026-10-24 已完成）

### B.1 实际落地文件

| 层 | 文件 | 说明 |
|---|---|---|
| 维度口径 | `backend/src/main/java/com/kbook/service/admin/BookProfileDimension.java` | 8 个维度枚举 + `toToken()` 归一化（5.1 的强制环节全在 Java 侧） |
| 纯函数 | `backend/src/main/java/com/kbook/service/admin/BookProfileCalculator.java` | 分桶/lift/Wilson/k-匿名/结论文案，单测对象 |
| 编排 | `backend/src/main/java/com/kbook/service/admin/BookProfileService.java` | 三口径取数、缓存、保护分支、区间解析 |
| DTO | `backend/src/main/java/com/kbook/dto/admin/BookProfileVO.java` | 含内部类 `Dimension` / `Bucket` |
| 接口 | `controller/AdminDashboardController.java` `GET /api/admin/dashboard/book-profile` | 只做参数路由 |
| 索引 | `ReadingProgress` / `UserReadHistory` / `Bookshelf` / `Note` 实体注解 | 4 条 `(book_id, 时间列)` 复合索引，dev 库实测已建 |
| 配置 | `application.yml` `kbook.admin.book-profile.*` | 4 个阈值，各自带 env 覆盖 |
| 前端 | `components/admin/BookProfileDrawer.tsx`、`BookProfileSection.tsx`、`api/adminDashboard.ts`、`pages/admin/dashboard.tsx` | 入口在「内容热度」书籍行右侧「画像」按钮 |
| 单测 | `backend/src/test/java/com/kbook/service/admin/BookProfileCalculatorTest.java` | 11 例 |

### B.2 与设计的偏差（都是实现期判定，不回改上文）

1. **MBTI 轴不是一个维度而是四个**：`MBTI_EI / MBTI_SN / MBTI_TF / MBTI_JP` 各自独立成维度（第 6 节写的 `MBTI_AXIS` 未采用）。理由：合成一个维度会让 4 个轴挤在同一张 Top-N 榜里，`lift` 语义也不同（轴是二分、型是 16 类）。
2. **`ratio` 分母 = 该维度有效样本**，未填写与脏值都不进桶列表，只以 `unknownCount` / `dirtyCount` 两个字段表达（第 6 节示例里的 `未填写` 桶取消）。理由：把"未填写"当桶会让它常年是最大桶，视觉压过真实分布。
3. **全站基线只有一个缓存键**（`site`，10 分钟），不是按维度分键；单书结果键为 `bookId|basis|windowKey`（5 分钟）。第 7.4 节的"按维度缓存"没必要——基线一次查询出全部维度。
4. **图表用 DOM 比例条而非 recharts 环形**：抽屉宽 480px、维度 8 个，环形图在这个尺寸下读不出差异，且 DOM 条便于逐桶挂 `lift` 徽标。第 8.2 节的"环形"作废。
5. **索引名与 7.3 节表格不同**（实际为 `idx_progress_book_updated` / `idx_history_book_created` / `idx_shelf_book_added` / `idx_note_book_created`），时间列都进了复合索引，`user_read_history` 用 `created_at` 而非 `action`。
6. **配置键前缀是 `kbook.admin.book-profile.*`**（env：`KBOOK_PROFILE_MIN_COHORT_SIZE` 等 4 个）。

### B.3 P1 验收证据

- 单测：`mvn -o -Dtest=BookProfileCalculatorTest test` → 11/11 通过（覆盖第 11 节 1~7 项，含 `{{7*7}}` 脏值、年龄边界、Wilson 上下界、`count=9/10` 显著性翻转、`siteRatio=0` 除零、小桶并入其他、Top-6 截断、`coverage=0`）。
- 接口：`bookId` 不存在 → `{"code":1,"message":"图书不存在"}`；非法 `basis` → "读者口径不合法，可选值：READ / TOUCH / DEEP"；区间 >366 天 → 文案与主看板逐字一致。
- 前端：`npm run build` 零错误；无头浏览器 1440×1000 与 390×844 两视口共 46 项断言全绿，0 页面错误、0 控制台错误；保护态与分布态、显著分桶 `lift` 徽标都有截图（`profile-{pc,mobile}-{protected,distribution,lift}.png`）。
- 断言里刻意包含"抽屉内不出现 `undefined`/`NaN`/`Infinity`/`[object`"——这是本周生产事故的回归位。

### B.4 dev 库跑通的前提（生产必须重新校准）

dev 库读者池最大 12 人，按 4.1 的默认阈值 10/5/10/20 会把页面清成保护态，因此验证时用 env 把 4 个阈值全降到 1。**这不代表生产也用 1**：上线前按 A.3 的三条 SQL 出分布，再定阈值。UI 上保护态的阈值数字来自接口 `minCohortSize`，改配置不需要改前端。**分布态**那一侧的回归见附录 G。

### B.5 顺带修掉的一个真实 500（与本功能无关但同源风险）

验证时发现 `GET /api/notes/timeline?sort=occurred` 与 `/api/notes/stats` 直接 500：`notes.occurred_at` 这一列**从来没建出来**。根因是 `@Column(nullable=false)` 的新列，Hibernate `ddl-auto: update` 只会发 `alter table ... add column occurred_at datetime(6) not null`，在有存量行的表上被 MySQL 严格模式拒绝（`Incorrect datetime value: '0000-00-00 00:00:00'`），异常被吞成启动期 WARN，列缺失又连带索引 `idx_note_user_occurred` 建不出来。

修复：`backend/src/main/resources/db/migration/V3__add_note_occurred_at.sql`（可空补列 → 用 `created_at` 回填 → 收紧 NOT NULL → 建索引），dev 库实测 0.42s 应用成功，两个接口回到 `code:0`。

对本功能的启示（P2/P3 期间一直有效）：**凡是给有存量数据的表加 `NOT NULL` 列，必须写 Flyway 迁移，不能指望 `ddl-auto: update`**；只加索引（本次 4 条）不受影响，实测能自动建出来。

---

## 附录 C：P2 实现落点与实测发现的两个真 bug（2026-10-24 已完成）

### C.1 落地文件

| 层 | 文件 | 说明 |
|---|---|---|
| 维度 | `service/admin/BookProfileDimension.java` | 由 8 个扩到 15 个：新增 `CHILDREN / CHILD_AGE / OCCUPATION / EDUCATION / INCOME / ENTREPRENEURSHIP / CHAT_STYLE`；`toTokens()` 支持逗号多值一人进多桶；`isQueryable()` 表达反查白名单 |
| 纯函数 | `service/admin/BookProfileCalculator.java` | `normalize()` 按人计数（分母是有效人数不是桶次数）；`Dimension` 输出 `multiValue` / `queryable` 两个标记 |
| 编排 | `service/admin/BookProfileService.java` | `getProfileBooks()` 反查（分子=该桶∩该书读者，分母=该书同口径总读者）；`getSiteDimensions()` 给前端当选择器数据源 |
| 接口 | `controller/AdminDashboardController.java` | `GET /profile-books`、`GET /profile-dimensions` |
| 前端 | `pages/admin/profile-books.tsx`（新）、`components/admin/BookProfileDrawer.tsx`、`BookProfileSection.tsx`、`api/adminDashboard.ts`、`router/index.tsx`、`pages/profile/index.tsx` | 独立页「人群选书」+ 入口两处（我的-管理菜单、画像抽屉页脚与保护态） |

### C.2 实测发现并修掉的两个真 bug（都不是断言放宽解决的）

1. **白名单没有在归一化出口收口**：`singleToken()` 对 `GENDER`/`CHAT_STYLE` 只做 `upper()`、对 `MBTI_TYPE` 只截 4 位，都不查白名单，于是 `{{7*7}}`、`XNTJ` 会作为**真实桶**出现在看板上——正是 5.1 第 1 条要挡的那件事，而且 `{{7*7}}` 实测就在库里。修法：折叠逻辑照旧，出口统一 `allowed.containsKey(token) ? token : DIRTY`，新增维度不必再各自记得查表。回归位：`BookProfileCalculatorTest.whitelistIsEnforcedCentrally`。
2. **`BIT(1)` 列被 `CAST(... AS CHAR)` 打成裸字节**：`is_married`/`has_children` 在库里是 `BIT(1)`（不是文档第 5 节写的「存 1」那种 tinyint 语义）。`CAST(bit AS CHAR)` 得到 0x01/0x00，MySQL 客户端显示成空串和 `\0`，Java 侧 `String.trim()` 把它们剪成空串 → **填了的人全被判成未填写**，`MARITAL`/`CHILDREN` 覆盖率恒为 0 且 `dirtyCount` 也是 0（静默）。修法：`CAST(CAST(col AS UNSIGNED) AS CHAR)`。修后实测 `MARRIED=3 UNMARRIED=2`、`HAS_CHILD=3 NO_CHILD=2`，与 `COUNT(is_married)=5` 对得上。
   > 教训：`COUNT(col)` 只证明「列非空」，不证明「接口读得到」。这类跨 JDBC 的取值形态，必须拿接口返回值反查 DB 才算验证过。

### C.3 与设计的偏差

1. **新增 `/profile-dimensions`（第 6 节未列）**：反查页的分桶选项必须与反查用同一套归一化口径，前端自己维护一份维度表一定会漂。全站基线本来就已在 `siteBaseline()` 里算好，多一个只读端点比在前端复制枚举便宜。
2. **`sortBy=LIFT` 的小样本门槛用 `min-bucket-size`**（第 6.2 节的原话），`READERS` 榜只要求 `count >= 1`；另外人群本身 `< min-cohort-size` 时直接空榜（第 4 节第 5 条的加强：分母都不够，出榜等于点名）。
3. **反查白名单落在枚举里而不是配置里**：`isQueryable() = !multiValue && 非(学历|收入|创业)`。前一半是第 5.1 条第 3 点（多值占比之和 > 100%，榜单无法解释），后一半是第 12 节那条「生产库确认样本前不进 P2 榜单」。前端从 `dimensions[].queryable` 读，不硬编码维度名。
4. **多值维度不画两桶堆叠条**：`BookProfileSection` 里两桶堆叠条的前提是「占比之和=1」，多值维度会画出错觉，改为只在单值维度画。

### C.4 P2 验收证据

- 单测：`mvn -o -Dtest=BookProfileCalculatorTest test` → **18/18 通过**（新增 7 例：职业折叠、历史枚举进脏值、注入串不带 lift、孩子年龄段多值、多值占比之和 > 1、反查白名单、白名单收口）。
- 接口（dev 库，阈值用 env 全降到 1，见 B.4）：
  - `/profile-dimensions` 返回 15 个维度，`CHILD_AGE/OCCUPATION` 带 `multi=true q=false`，`EDUCATION/INCOME/ENTREPRENEURSHIP` 带 `q=false`；
  - `?dim=GENDER&value=MALE&days=0&sortBy=READERS` → 10 条榜（`1/1 x1.5` 这种形态说明分子分母与 lift 都算出来了）；
  - `?dim=GENDER&value=女`（展示名）→ `code:0` 空榜，不 500；
  - `?dim=OCCUPATION&value=TECH` → `code:1 职业分类不支持反查榜单：多值维度或有效样本过薄`。
- 前端：`npm run build` 零错误；无头浏览器 1440×1000 与 390×844 两视口下 `[role="listitem"]=10`、`button[aria-pressed="true"]=6`、`p.text-danger=0`，页面错误与控制台错误均为 0；主看板同视口回归 `查看《…》读者画像` 按钮 6 个、无报错。
- 环境备注：本机 8181/15173 属于另一份代码副本（`D:\item\kbook`），8182 也被第三个 KBook 实例占用，本次验证跑在自建的后端 8191 + Vite 15181（临时 `vite.verify.config.ts`，验证后删除）。

### C.5 遗留

- 第 4.1 条的阈值校准 SQL 仍需在**生产库**跑一遍才能定档，dev 的 1/1/1/1 只是验证用。
- P3（`target_reader_tags` 吻合度 + `book_profile_daily` 快照）未动；按 4.1 第 4 条，如果生产校准后多数书仍落在保护分支，P3 的「标签→全站人群结构」应提前。
- `users` 画像列的取值校验与历史脏值清洗（第 12 节最后一条）当时未单开改动，本功能只是挡住了展示。**校验已于附录 F 补齐（2026-10-25），历史脏值清洗仍未做**。

---

## 附录 D：第 11 节测试计划补齐（2026-10-24 已完成）

### D.1 单测：补 3 例，并修掉一处口径重复

`mvn -o -Dtest=BookProfileCalculatorTest test` → **21/21 通过**。新增：

| 用例 | 对应第 11 节 |
|---|---|
| `cohortGate` | 第 3 条 `readers=minCohort±1` 的边界，10/5 与 3/2 两组阈值各验一次 |
| `significanceFlipsWithSampleSize` | 第 2 条的 20 边界：12/20 对 0.5 基线**不显著**，60/100 才越过 Wilson 下界 |
| `highlightCountBoundary` | 19 人显著但不进结论、20 人进；同一份数据换成 3/2/3/5 阈值直接出结论 |

顺带把第 4 节的人群闸门抽成 `Thresholds.isSampleProtected(cohortSize)`：原先单书（`readers.size() < minCohortSize`）与反查（`bucketUsers < minCohortSize`）各写一遍比较，阈值实例也在三条取数路径上各 `new` 一次。现在统一由 `BookProfileService.thresholds()` 构造、由同一函数判定，4.1 校准改配置时不会只改到一半。

### D.2 接口层：6 条逐条实测（自建后端 8191，阈值 env 降到 1）

| 第 11 节条目 | 请求 | 结果 |
|---|---|---|
| `bookId` 不存在 | `/book-profile?bookId=999999` | `code:1 图书不存在` |
| 区间 >366 | `/book-profile?bookId=102&start=2020-01-01&end=2025-01-01` 与主看板 `GET /admin/dashboard` 同参数 | 两边**逐字相同**：`自定义区间最长支持 366 天，全部数据请使用「全部」`（共用 `AdminDashboardService.MAX_CUSTOM_RANGE_DAYS`） |
| 只给 start / 起止倒置 | `start=2026-09-01`；`start=2026-10-24&end=2026-09-01` | `自定义区间需要同时提供开始和结束日期`；`开始日期不能晚于结束日期` |
| `days` 与 `start/end` 同时给 | `?bookId=102&days=7&start=2026-09-01&end=2026-10-24` | `rangeStart/rangeEnd` 为自定义值、`rangeDays=54` → 以 `start/end` 为准。**这条已从接口层下沉为单测**（G.9：`AdminRangeRules.resolve` + `customRangeOverridesDays`） |
| 读者池只含 APPROVED | 见 D.3 | 插 1 个 PENDING 读者的进度行后，`readers` 仍为 1 |
| 同一 basis 并集去重 | 见 D.3 | 同一 APPROVED 读者在 `bookshelf` + `user_read_history` 各 1 行（原始 3 行），`readers` 仍为 1 |

> 「已删除」这一支在本 schema 下与「不存在」是同一条：`books` 表没有软删列（实测 `Unknown column 'deleted_at'`），书是硬删。第 11 节原文的两种情况实际只有一个分支。

### D.3 构造数据与其回收（这两条在现网数据上观测不到）

dev 库现在只有 **3 个 APPROVED 账号有阅读进度，且每本书最多 1 个读者**——APPROVED 过滤与并集去重都不是自然可观测的现象，必须按第 11 节说的"构造 1 个 PENDING 读者有进度"。做法与回收：

1. `book_id=102` 基线：进度 1 行（user 1，APPROVED）、书架 0 行、阅读记录 1 行（user 1）→ 窗口 `2026-05-01..05-31` 下 READ=1 / TOUCH=1 / DEEP=0。
2. 插 3 行，时间戳一律落在窗口内：`reading_progress(user 5082353 PENDING)`、`bookshelf(user 5082353 PENDING)`、`bookshelf(user 1 APPROVED)`。原始行数变为进度 2 / 触达 3。
3. 换窗口 `2026-05-02..06-01` 复查：READ=1、TOUCH=1（两个断言同时成立，才分别证明"排除非 APPROVED"和"并集去重"）；`profile-books?dim=GENDER&value=MALE&basis=TOUCH` 里 102 也是 `count=1 readers=1`。
4. 按主键删除这 3 行：`back_progress=1 back_shelf=0 leftover_rp=0 leftover_shelf=0`，窗口 `2026-05-03..06-02` 再读仍是 READ=1 / TOUCH=1。

**必须换窗口**这件事本身是结论的一部分：`bookProfile` 缓存 key 是 `bookId|basis|rangeKey`、TTL 5 分钟，同一窗口连查两次拿到的是第一次的缓存，会伪装成"过滤生效"。任何改动读者池的验证都要用没查过的区间（或等 TTL 过期）。

### D.4 现在的状态

第 11 节全部条目已覆盖（单测 21 例 + 接口 6 条）。C.5 的三项遗留中，`users` 画像列取值校验已由附录 F 落地（清洗与生产阈值校准仍在），剩下的仍是两项：生产阈值校准（A.3）、P3 吻合度。

> 本节这 6 条接口层证据此后不再靠手敲：附录 J 把它们与 H 的隐私攻击面合成 `deploy/verify/admin-profile-matrix.mjs`，并加了「前置不满足 → exit 2」的闸门。

---

## 附录 E：健康度面板与 P3 前置实测（2026-10-24）

### E.1 第 12 节要求的「画像字段健康度」落点

第 12 节第一条要求"P2 前补画像字段健康度接口评估用户侧引导"。**没有新接口**：`/profile-dimensions` 返回的 15 个维度里已经带 `coverage / unknownCount / dirtyCount / queryable`，缺的只是让人看见的地方。落点选在「人群选书」页榜单下方的折叠表（`pages/admin/profile-books.tsx`，`details[data-profile-health]`），理由是运营在这里正好会问"这个桶怎么才 4 个人"，同一屏就能看出是人群真的小还是那一列没人填。

- 填写率 <30% 的行标 `text-warning`；多值维度标「多值」；不可反查的维度用 `—` 而不是隐藏，隐藏会让人以为功能坏了。
- 脏值单列一栏：`{{7*7}}`、`SELF`、`Startup` 这类值被归一化挡在分布之外，但**引导补全需要知道它们有多少**，所以这一列只在健康度表里出现。
- 验证：`npm run build` 零错误；无头浏览器 1440×1000 下 `details[data-profile-health]=1`、`tbody tr=15`、`[role="listitem"]=10`、`p.text-danger=0`，页面/控制台错误 0；390×844 同结论；主看板同工具回归 `.recharts-surface=8`、无报错。

### E.2 P3 前置实测：书侧标签词表

`JSON_TABLE` 展开 `books.target_reader_tags`（22174 本书有值）：

| 指标 | 实测 |
|---|---|
| 不同标签数 | **19797** |
| 只出现过 1 次的标签 | **13893（70.2%）** |
| 出现在 ≥50 本书上的标签 | 230 |
| 这 230 个头部标签覆盖的书 | **20010 / 22174 = 90.2%** |
| `reader_need_tags` | 头部取值每本只出现 1 次（"学习印刷知识""探索宇宙规律"），基本每本一套说法 |

头部标签的形态决定了映射怎么做：`学生 3384 / 历史爱好者 2825 / 文学爱好者 2675 / 教育工作者 1575 / 创业者 1516 / 教师 1169 / 大学生 1109 / 管理者 1100 / 职场人士 977 / 家长 686 / 青少年 696 / 女性读者 552`。

- **能直接映射到现有维度桶的**：学生/大学生→`OCCUPATION=STUDENT`、教师/教育工作者→`OCCUPATION=EDUCATION`（白名单里没有 TEACHER 这个 token，别照抄直觉）、创业者→`ENTREPRENEURSHIP`、家长→`CHILDREN=HAS_CHILD`、青少年→`AGE=<18|18-24`、女性读者→`GENDER=FEMALE`。
- **映射不到的**：`历史爱好者 / 文学爱好者 / 小说读者 / 心理学爱好者` 这类**兴趣**标签——`users` 表里没有对应字段，它们只能与书的分类比对，不属于本功能的画像口径。
- 结论：吻合度分母只能是"可映射的那部分标签"，其余必须显式记为「未映射」并展示占比，否则分数会被兴趣标签稀释成一个没有解释力的数字。

### E.3 因此 P3 的开工顺序（不是决定，是实测逼出来的先后）

1. ~~**先做受控映射表**~~ **已完成，见 E.4**（2026-10-25）：没有做成 230 行枚举字典，而是做成关键词规则表——枚举字典覆盖不到 70.2% 的尾巴，规则表能覆盖「政治学学生」这类组合词。
2. 再定吻合度口径：单书版（该书读者里该桶占比 vs 声称，等价于把 `profile-books` 的 lift 反过来用）还是全站版（该桶在全站的规模）。**这一条仍待产品决策**——第 10 节原话是"需要产品决策，暂按原分期实施"，E.2 的实测没有改变这个判断，只是说明无论选哪个都得先有第 1 步。
3. `book_profile_daily` 快照表排在最后：趋势的前提是口径稳定，口径没定就建表等于建一张要重跑的表。

### E.4 受控映射表落地与全量实测（2026-10-25，E.3 第 1 步完成）

落点 `backend/src/main/java/com/kbook/service/admin/TargetAudienceRules.java`（纯静态规则表，无接口、无依赖注入、无 LLM）。单个标签分为互斥的五类，只有 `AUDIENCE` 参与吻合度：

| Kind | 含义 | 判定位置 |
|---|---|---|
| `AUDIENCE` | 声称的人群能落进画像桶 | 人口学词 ∪ 人称职业词，可多桶 |
| `INTEREST` | 题材/兴趣，`users` 侧无对应列 | `爱好者/发烧友/迷/粉丝/读者/书友/关注者/思考者` |
| `GENERIC` | 泛化到等于没说 | `普通读者/所有人群/成年读者…`，**必须在 INTEREST 之前**，否则「普通读者」被后缀判成兴趣 |
| `NO_BUCKET` | 身份明确但现有桶表达不了 | `律师/上班族/白领/职场` + 人称后缀 `从业者/工作者/人士/人员/职员/专员` |
| `UNRECOGNIZED` | 规则没覆盖 | 兜底 |

优先级实测出来的三条硬规则，写反了就会虚高：

1. **人口学 > 兴趣后缀**：`女性读者` 必须是 `GENDER=FEMALE`，不能被「读者」判成 INTEREST。
2. **职业只用「人称名词」，不用领域词**：规则里写 `金融` 就会把 `金融爱好者` 判成 `OCCUPATION=FINANCE`，而它说的是题材；改成只收 `投资者/金融从业者/银行职员` 之后这类标签落到 INTEREST。判定依据是「兴趣 vs 职业不是同一列」，不是命中率——**这里少算比多算好**。
3. **亲属词屏蔽职业叠加**：`学生家长` 声称的是家长（`HAS_CHILD`），若同时叠加 `学生` 会凭空多出 `STUDENT`。

跑在全量 dev 词表上（`books.target_reader_tags` 经 `JSON_TABLE` 导出 97684 条 书×标签）：

| 分类 | 不同标签 | 书×标签命中 | 占比 |
|---|---|---|---|
| AUDIENCE | 5075 | 41795 | **42.8%** |
| INTEREST | 6789 | 32000 | 32.8% |
| UNRECOGNIZED | 6149 | 14948 | 15.3% |
| NO_BUCKET | 1457 | 6963 | 7.1% |
| GENERIC | 331 | 1978 | 2.0% |

**至少能比对一个桶的书 = 18783 / 22174 = 84.7%**（E.2 预估 90.2%，差在尾巴上的组合词）。声称人群集中在两个桶：`OCCUPATION=EDUCATION` 10217 本（46.1%，主要来自 研究者/学者/教师 三族）、`OCCUPATION=STUDENT` 9730 本（43.9%）；其余 `MANAGEMENT 3172 / AGE=18-24 2609 / GENDER=FEMALE 2340 / AGE=<18 1918 / ENTREPRENEUR_OR_WANT 1664 / HAS_CHILD 1236 / FINANCE 903`，`RETIRED` 只有 17 本。

两个只有跑全量才暴露的坑：

- **子串陷阱**：`初学者` 含 `学者`，第一版把「理财初学者 66 / 经济学初学者 42 / 烹饪初学者 39 / 历史初学者 38 / 初学者 36」这类入门标签算进 `OCCUPATION=EDUCATION`。修法是匹配前把 `初学者` 改写成中性词：全量 227 个含「初学者」的标签现在 221 个落 UNRECOGNIZED，剩 6 个是因为标签里另有真实人称（`初学者程序员`→TECH、`编剧初学者`→ARTS、`女性瑜伽初学者`→FEMALE），不是子串误命中。入门表达的是熟练度，画像里没有这一列，**宁可不算也不能算错**。
- **`儿童文学爱好者` 这类「题材+人群词」**会被判成 `AGE=<18`（45 本）。已知粗糙，量级可忽略，留给复核。

复核产物（**临时目录，不入库**，避免把一次性的数据快照变成仓库资产）：`%TEMP%\kbook-verify\tag-mapping.csv` 19801 行 `tag,book_count,kind,buckets` 按书数降序，人只要从头往下看 AUDIENCE 的前几百行即可；同目录 `rules-coverage.txt` 是上面这张表的原始输出。

**为什么现在还不能端到端验证**：读者侧同样是稀的。dev 库 12 个 APPROVED 用户的填写数是 性别 6 / 生日 6 / 职业 4 / 婚姻 5 / 孩子 5 / 创业 2。书侧 84.7% 可比对，读者侧却连一个 `min-cohort-size=10` 的桶都凑不满，所以吻合度的数值必须在生产库上跑（A.3 清单），本地只能验证「规则表 + 归一化不越界」。

**测试**：`TargetAudienceRulesTest` 13 例，含一条不变式——任何映射出的 `(维度, token)` 必须命中 `BookProfileDimension.getAllowed()` 白名单，非 AUDIENCE 分类必须零桶（这条是 P2 里 `{{7*7}}` 变成真实分桶那类 bug 的反向拦截）。连同 `BookProfileCalculatorTest` 21 例共 **34 例全绿**，`mvn -o test` 零错误。

E.3 第 2 步（单书版 vs 全站版口径）仍待产品决策；映射表已经把「可比分母」定死在 42.8% 标签 / 84.7% 书这两个数上，两种口径都直接受它约束，不必等口径再改规则表。

**当前没有任何调用方**：`TargetAudienceRules` 只被自己的单测和一次性统计脚本用到，没有接口、没有 Service 引用。这是刻意的——吻合度口径没定之前把它接进 `BookProfileService`，等于把一个还没确认的指标写进线上响应。

---

## 附录 F：画像列写入端取值校验（2026-10-25，第 12 节那条独立小改动）

第 12 节一直挂着「本功能只挡住展示、脏数据仍在库里」。这次补的就是那半句：把枚举校验做进写入端，并且**与看板分桶共用同一份白名单**，不再抄第二份取值表。

### F.1 落点

| 文件 | 改了什么 |
|---|---|
| `service/admin/BookProfileDimension.java` | 新增 `canonicalForWrite(raw)`：折叠大小写与别名后按 `allowed` 收口，非法返回 null；多值逐段校验，任一段非法即整体拒绝，合法段按输入顺序去重再拼 |
| 同上 | `GENDER` 白名单补 `OTHER`；`CHILD_AGE` 新增 `childAgeToken()` 把裸值 `no_children` 折进 `CHILDREN_NO_CHILDREN`；`EDUCATION`/`INCOME` 的 label 改成「学历（当前/目标）」「年收入（当前/期望）」 |
| `service/user/ProfileValues.java`（新增） | `require(dim, raw)`：null/空白 → null（该列留空），非法 → `BusinessException(dim.getLabel() + "取值不合法")`。报错文案与看板列名、AI 摘要同源 |
| `service/user/UserService.java` | `updateTraits` 七个枚举列全部走 `require`；`updateBookChatStyle` 校验 `CHAT_STYLE` 并保留空值回落 `DEEP`；删掉私有 `VALID_MBTI`（原来那份就是第二份取值表）；被校验的列不再走 `sanitizeHtml`——白名单 token 不可能带标签 |
| `service/auth/AuthService.java` | `register` 的 `gender`/`mbti` 同样收口 |
| `pages/auth/onboarding.tsx`、`pages/profile/ProfileTraitsSheet.tsx` | 「没有孩子」改发 `children_no_children`；新增 `parseChildRanges()` 回显归一 |

### F.2 三个只有走全链路才暴露的真 bug

追法是把每列从「UI 选项数组 → 请求体 → Service setter → `@Column` → 看板 SQL」走一遍，缺任一环即口径断裂。

1. **`gender=OTHER` 不在读取端白名单**。注册引导页有「其他 ○」，`User.gender` 的注释也写了三种取值，但 `GENDER.tokens(...)` 只收了 `MALE/FEMALE`。选了「其他」的人在看板上进「脏值」桶——填了却等于没填，且 `dirtyRate` 把它算成不规范而非有效样本。dev 库里恰好没人选（MALE 10 / FEMALE 2 / 空 24），所以这个 bug 在本地完全静默。
2. **`children_age_ranges` 的裸值 `no_children`**。前端两处表单在 `hasChildren=no` 时发裸值，而该列其余取值全在 `children_*` 命名空间里；读取端 `upper()` 后查表得到 `NO_CHILDREN`，不在白名单 → 脏值。**这条差点被本次改动变成硬故障**：加完校验后 `no_children` 会被拒，用户点「没有孩子」保存就吃 400，onboarding 走不完。修法两头都做——后端把裸值折进 `CHILDREN_NO_CHILDREN`（生产库若已有裸值，仍然可比分桶），前端改发 `children_no_children`。前端回显路径本来就在做 `v.startsWith('children_') ? v : 'children_' + v` 的补前缀，说明发裸值是自己跟自己对不上。
3. **大写归一化打断回显**。`canonicalForWrite` 返回白名单 token（`CHILDREN_0_2`），而选项数组是小写 value，`startsWith('children_')` 认不出自己的输出 → 勾选框不再预置、再保存会把已存的段丢掉。收进 `parseChildRanges()`：先 `trim().toLowerCase()` 再补前缀，顺带兼容库里三种历史写法。

### F.3 校验语义（定了就别再改的口径）

- 传 `null` = 不改该列；传空白串 = 清除该列。注册引导允许只填一部分，靠的就是这条。
- 非法值**拒绝整次请求**，不是丢弃该字段：`updateTraits` 带 `@Transactional`，实测 `{gender:"MALE", mbti:"XNTJ", occupation:"LAWYER"}` 报「MBTI 类型取值不合法」且 `gender` 未变。表单提交要的是「要么全存要么不存」。
- 报错只报第一个非法字段。最坏情况用户逐个修、提交 N 次。已知取舍：UI 只产出白名单内的选项，这条只在第三方客户端乱发时才连续触发，暂不做多字段聚合报错。
- 多值列任一段非法即整体拒绝，不做「保留合法段」——静默丢段比报错更难查。

### F.4 测试与运行时证据

**单测 107 例全绿**（`mvn -o test`）：`ProfileValuesTest` 73 + `BookProfileCalculatorTest` 21 + `TargetAudienceRulesTest` 13。`ProfileValuesTest` 里两条是结构性的，不是重复断言：

- 不变式「放行 ⇒ 可分桶」：对 8 个有字符串写入形态的维度，逐个把 `allowed` 的 key 反喂 `require` 再喂 `toTokens`，要求放行且成同一桶。`AGE/MARITAL/CHILDREN` 不在这份列表里——它们由 `birthday` 与两个布尔列派生，没有字符串写入形态。
- UI 契约：把 `onboarding.tsx` 与 `ProfileTraitsSheet.tsx` 的选项数组逐值列出（含 `children_no_children`、多值 `children_0_2,children_13_17`、`STUDENT,TECH,ARTS`）喂给写入端，要求放行且入库后成桶。上面第 2、3 条 bug 就是被这条钉住的——它验的不是「白名单自洽」，而是「UI 能选出来的值不会被 400」。

**接口层**（自起后端 8195，本地签 token 只读验证，用完即弃、未写进仓库）：

| 请求 | 结果 |
|---|---|
| `gender={{7*7}}` | `code:1` 性别取值不合法 |
| `mbti=XNTJ` / `occupation=TECH,LAWYER` | `code:1` MBTI 类型取值不合法 / 职业分类取值不合法 |
| `aspirationEducation=SELF` / `aspirationIncome=MEDIUM` | `code:1`，文案用新 label「学历（当前/目标）」「年收入（当前/期望）」 |
| `bookChatStyle=hacker` | `code:1` 共读风格取值不合法 |
| `gender=other`（小写） | 放行，入库 `OTHER` |
| `childrenAgeRanges=no_children`（历史裸值） | 放行，入库 `CHILDREN_NO_CHILDREN` |
| `childrenAgeRanges=children_0_2,children_13_17` | 放行，入库 `CHILDREN_0_2,CHILDREN_13_17` |
| `occupation=engineer,TECH` | 放行并折叠去重，入库 `TECH` |

`npm run build` 两端零错误。验证用的 5082349 那行画像列已复原为 NULL、风格复原为 `DEEP`。

### F.5 还没做 / 不受影响

- **dev 库 dirty 计数不变**：`gender` 实测只有 MALE 10 / FEMALE 2 / 空 24，`children_age_ranges` 只有 `children_3_6`(2) 与 `children_no_children,children_3_6`(1)，没有裸值也没有「其他」。所以 `OTHER` 与 `no_children` 两个修复在 dev 上量不出差别，它们的价值要在生产库看——这也再次说明附录 A.3 那份阈值/分布清单必须在生产上重跑。
- **历史脏值清洗没做**（第 12 节那条的后半）。`SELF/MEDIUM/Startup/{{7*7}}` 语义不可还原，NULL 化等于永久丢信息；「清空」还是「保留但在看板上标不规范」是产品决策，不该由这次校验顺带替用户决定。
- **发布顺序风险已消**：新后端 + 旧前端（发裸值）由 alias 兜住；旧后端 + 新前端（发 `children_no_children`）本来就能收。两端可以任意先后，不需要同镜像原子发布——这一条与第 12 节「必须同镜像发布」的那条不同，那个是针对新接口 + 新索引的。
- `User.java` 的注释把这两列写成「期望学历 / 期望年收入」，而 UI 题干是「当前/目标学历」「当前/期望年收入」。题干本身混了现状与期望，一个字段表达不了两件事；本次只把看板和 AI 摘要的措辞改成不替用户断言，题干拆分留给问卷侧。




---

## 附录 G：分布态运行时回归与 A.3 清单预演（2026-10-25，不改代码）

### G.1 为什么还要再验一次

B.4 记过「默认阈值会把页面清成保护态」，F.4 之后两列 label 改名（学历/年收入）。这两件事叠加后缺一次**分布态**（非保护态）的运行时回归：新 label 是否真的进了抽屉、15 个维度的条是否真画出来。本次只动启动参数与一次性环境，不改代码、不写库。

### G.2 dev 里跑得动分布态的书（实测，决定了验证路径）

| 口径 | dev 全书最大读者数 | 达标的书 |
|---|---|---|
| `READ` | **1** | 无（连 ≥2 都没有） |
| `TOUCH` | 3（全部时间窗） | `24653 李诞脱口秀工作手册` |
| `DEEP` | 10 | `24715 生命密码_你的第一本基因科普书` |

抽屉入口挂在主看板的书列表上，而那个列表是 **AI 活跃 Top10**、不是读者榜：逐一探测后 10 本里只有 `24715` 达标，所以回归走它 + `互动`（= `DEEP`）口径。`24653` 不在榜上，它的 TOUCH=3 只能在接口层看到（`days=0&basis=TOUCH` → `readers=3, sampleProtected=false`，15 维度全出）。

阈值靠启动参数降，不写库不改码：`--kbook.admin.book-profile.min-cohort-size=3 --min-bucket-size=1 --significant-min-count=1`。

### G.3 运行时断言（1440×1000 无头浏览器，8195 后端 + 15195 临时 vite）

| 断言 | 结果 |
|---|---|
| 抽屉内 `section` 数 | **15**（15 个维度全部渲染，无缺列） |
| DOM 比例条（带内联 `width` + `background`） | **42** |
| 两桶维度的堆叠条 | **7** |
| 空态文案（`该维度无人填写` / `暂无有效样本`） | **0** |
| `/api` 4xx·5xx | **0**；页面错误与控制台错误 **0** |
| 维度标题 | 出现「学历（当前/目标）」「年收入（当前/期望）」，改名生效 |
| 头部文案 | `10 位读者 / 8 位填写了画像`，`basisNote` 逐字来自接口 |

两个观测坑，都不是放宽断言解决的：

- **`.recharts-surface` 计数为 0 不等于没渲染**。抽屉用的是 DOM 比例条（B.2 第 4 条），根本没有 recharts 实例。渲染产物要断言 `section` 与带内联 `width` 的条——第一次探测选了错的选择器，改的是断言对象而不是组件。
- **降阈值后 `significant` 仍全为 false**：首维度显示灰色 `×0.90 / ×1.20` 而不是「全站 ×」徽标，因为显著性还要过基线侧的 `min-bucket-size`。本次没把显著徽标列进验收项，它已由 B.3 的 lift 截图覆盖。

### G.4 A.3 三条 SQL 在 dev 预演（生产可直接执行，纯只读）

语法与输出形状都通过，dev 结果对照 A.3 的判定规则：

| A.3 条 | dev 实测 | 判定规则会得到 |
|---|---|---|
| 1 单书读者数分布 | 只有 `1-4` 一档，21 本；`>=10` 档 **0 本（0%）** | `<20%` → 先做人群选书、再做单书画像 |
| 2 画像有效样本 | readers 12；gender 6 / mbti 5 / birthday 6 / married 5 / education 2 | 全部 `<30` → P1 那五个维度都不该进 |
| 3 脏值体检 | `education`：`SELF`×4 `Master`×2 `PhD`×1 `Bachelor`×1；`annual_income`：`MEDIUM`×4 `{{7*7}}`×1 `500K`×1 `100k`×1 `100K`×1；`entrepreneurship`：`false`×4 `{{7*7}}`×1 `true`×1 `Tech Startup`×1 `Startup`×1 | 非白名单值全列都有 → 归一化先按实测补齐 |

结论两条：**这份清单本身可用**（不至于到生产才发现 SQL 写错）；**dev 三条判定一致指向样本不足**，所以 P1/P2 现在这个形态（保护态 + 全站基线）是对的，而 P3 吻合度的任何数值只能在生产库上出，阈值必须按 A.3 在生产重跑一次才能定。

### G.5 一次性环境

后端 8195 + 临时 `frontend/vite.verify.config.ts`（端口 15195，proxy → 8195），用完删除；只停自己启动的两个进程，8181 / 15173 / 8182 未动。本轮无写库操作，5082349 保持 F.4 复原后的 NULL 画像列状态。

### G.6 A.3 做成可执行脚本：`deploy/sql/profile-threshold-audit.sql`

为什么要做成脚本：A.3 那三段查完还要人脑算比例、算「够不够 20%」——最容易出错的恰是这一步，而且它发生在生产库上、只跑一次。另外原文只测 `READ`，但抽屉能切三种口径，`min-cohort-size` 定得对不对取决于三种口径下分别有多少本书达标。

相对 A.3 的增量：

| 增量 | 说明 |
|---|---|
| 三口径分布 + 判定内置 | `1a/1b/1c` 各出 bucket 分布，`1d` 直接给 `pct_over_min_cohort` 和结论文案；阈值写在开头 `SET @min_cohort := 10`，一处改，与 `kbook.admin.book-profile.min-cohort-size` 对齐 |
| 脏值按 `status` 拆 scope | 见下面那条只有跑脚本才暴露的发现 |
| 11 行维度非空率 | 覆盖 15 个维度用到的全部列（MBTI 四轴共用 `mbti` 列，不重复列） |
| 生日异常单列 | `AGE` 的脏数据来自 `birthday > CURDATE()`，与取值脏值不是一类问题，分开看 |

**刻意没做**：不把白名单抄进 SQL（附录 F 刚拆掉「第二份取值表」这个隐患）。脚本只列原始取值，有效样本与 `dirtyCount` 仍然读 `GET /api/admin/dashboard/profile-dimensions`——判定口径只留 `BookProfileDimension` 一处。

dev 跑通（exit 0），结果与 G.2/G.4 的独立测量互相咬合：

| 口径 | 有读者的书 | 读者 ≥10 的书 | 判定 |
|---|---|---|---|
| `READ` | 21 | 0（0.00%） | 低于 20% → 先做人群选书 |
| `TOUCH` | 109 | 0（0.00%） | 同上 |
| `DEEP` | 147 | 1（0.68%，即 `24715`） | 同上 |

**跑脚本才暴露的一条**：脏值几乎全在 `status <> 'APPROVED'` 的行里。dev 的 `SELF / MEDIUM / {{7*7}} / Startup / Tech Startup / 100k / Engineer / hacker` 一条都不在 APPROVED 里；APPROVED 侧只有会被正常折叠的值（`engineer`→`TECH`、逗号多值→进多桶）。A.3 原文的脏值体检不带 `status` 条件，会把两个不同的问题算成一个数：

- 对**看板**：当前分布确实是干净的，健康度面板 `dirty=0` 是真的（与 F.5 相互印证，不是面板漏算）。
- 对**清洗决策**：那些人是待审核账号，一旦审核通过就进看板。所以「历史脏值要不要 NULL 化」不能按当前 APPROVED 的量来定，得按全库量定——生产的第 3 条要读两个 scope 的数字再决策。

### G.7 时间窗继承核对（负结果）

前端 `RANGE_OPTIONS` 只发 `7 / 30 / 90 / 0` 或 `start+end`；`BookProfileService.resolveRange` 与 `AdminDashboardService.normalizeDays` 的 switch 分支完全一致（含 default→7、366 天上限、报错文案逐字相同）。所以抽屉的窗口与主看板同源，不存在「看板 30 天、画像 7 天」的分裂。

⚠️ 隐含约束记一条：`resolveRange` 的语义是「不在 `{30,90,0}` 里就当 7 天」。将来若给看板加一档（比如近 15 天），画像接口会静默按 7 天算而主看板按 15 天算——加分支时必须同时改两处，单测钉不住这种漂移。**这条已在 G.8 按掉**，不再是靠人记住的约束。

### G.8 把 G.7 那条约束按掉：时间窗判定合并成 `AdminRangeRules`

G.7 的处置本来是「加分支时必须同时改两处」——这正是这个功能一路踩过的「第二份口径」（D.1 的口径重复、F 的第二份取值表）。留一句提醒等于把 bug 寄存在下一次改动里，所以本轮把它消掉。

| 文件 | 改动 |
|---|---|
| `service/admin/AdminRangeRules.java`（新） | 包内静态类，只装两条共享判定：`normalizeDays`（30/90/0，其余一律 7）与 `requireValidCustomRange`（单边日期 / 起止倒置 / 366 天上限，三条报错文案逐字保留——它们直接展示给管理员） |
| `AdminDashboardService.java` | `resolveRange` 改调共享判定，私有 `normalizeDays` 删除 |
| `BookProfileService.java` | 同上，删掉内联 switch 与重复的上限校验 |

**区间对象没有合并**，这是刻意的：`DateRange` 携带环比窗口与趋势粒度（`previous()`、`resolveGranularity`），被服务里 30 多个方法签名引用；`Window` 携带缓存键。为了合并它们要动的面，比这次要防的漂移大得多——共享「判定」就够了，共享「载体」是另一件事。

验证：

- 新增 `AdminRangeRulesTest` 12 例：档位归一化（含 `15 / 365 / -1` 三种落回 7 的输入）、单边日期、起止倒置、366 天通过、367 天拒绝且文案带常量值、同日区间。
- 全量 `mvn -o test` **119 例全绿**（`ProfileValuesTest` 73 + `BookProfileCalculatorTest` 21 + `AdminRangeRulesTest` 12 + `TargetAudienceRulesTest` 13），0 失败 0 错误。
- 自建 8197 后端跑 12 条接口对照，证明重构前后行为一致：同一个 `days` 在画像与主看板得到**同一个窗口**（`days=15` → 两侧都 `rangeDays=7, 2026-10-19..2026-10-25`；`days=30` → 两侧都 `2026-09-26..2026-10-25`；`days=0` → 两侧都全时间），三条报错文案逐字相同。
- GitNexus MCP 本机未连接（工具列表为空），改前改用 grep 列全引用点（R3）：`normalizeDays` 只有服务内 1 处调用，`resolveRange` 每服务 1~2 处，`MAX_CUSTOM_RANGE_DAYS` 是 public 常量、签名未动，前端与 DTO 无涉及，因此不需要重编前端。

**G.9 把 G.8 剩下的另一半（区间组装与优先级顺序）也合掉了**，本节这条「共享判定」的结论仍然成立。

---

### G.9 区间组装也只剩一份：`resolve` 进 `AdminRangeRules`，§11 的优先级规则从此有单测

G.8 合并的是两条**判定**，但两个服务里仍各留一份**组装**：`if 自定义区间 → else 档位窗口` 的分支顺序，和 `minusDays(normalized - 1L)` 这个「闭区间含今天」的算法。后果是 §11 那条「`days` 与 `start/end` 同给时以 `start/end` 为准」只有运行时证据（I.2 第 4 行），单测一条都没钉住——而「谁先判」恰恰是下一次顺手改动最容易翻转的东西。

| 文件 | 改动 |
|---|---|
| `AdminRangeRules.java` | 新增 `Range` record 与 `resolve(start, end, days, today)`：分支顺序、档位归一化、`N-1` 闭区间算法各只剩一份。`today` 由调用方注入而不是就地取——否则这条规则只能靠起服务打接口来验，而且跨零点时两端各取各的今天会错位 |
| `AdminDashboardService.java` / `BookProfileService.java` | `resolveRange` 各收缩成一行委托，把 `Range` 装回自己的 `DateRange` / `Window`。载体仍不合并，理由与 G.8 相同 |

引用点（R3，grep 代 GitNexus）：`resolveRange` 是 private，共 3 处调用（`AdminDashboardService:57`、`BookProfileService:104`、`:179`），签名未动。

验证三层，不靠「看起来一样」：

- 单测：`AdminRangeRulesTest` 12 → 21 例，全量 `mvn -o test` **135 例全绿**（此前 126）。新增覆盖 `days` 与 `start/end` 同给（`days=0/7/15` 三种取值都不许生效）、档位窗口含今天（`7→today-6`、`30→today-29`、`90→today-89`、`15/1/-1→today-6`）、`days=0` 且无自定义才是全时间、单边日期必须抛错而不是被 `days` 静默兜掉。
- 变异测试（故意放松，确认新测试真的会红）：

| 变异 | 期望 | 实测 |
|---|---|---|
| `normalizeDays` 判在自定义区间之前（`days=0` 吞掉区间） | `customRangeOverridesDays` 红 | exit 1，`customRangeOverridesDays:89 FAILURE` |
| `minusDays(normalized - 1L)` → `minusDays(normalized)`（窗口少一天） | `presetWindowIsInclusiveOfToday` 红 | exit 1，`:102 FAILURE` |
| `resolve` 里删掉 `requireValidCustomRange` 调用 | `halfGivenRangeThrowsInsteadOfFallingBackToPreset` 红 | exit 1，`:118 FAILURE` |

  三个变异分别被对应测试打掉；源码还原后与变异前逐字节相同（`diff` 验证），再跑仍绿。
- 运行时中性：自建 8300（默认阈值）/ 8301（`KBOOK_PROFILE_MIN_COHORT_SIZE=1`）重跑 I.2 的 14 条矩阵，**FAIL 0/14**。优先级那行拿到真实响应 `rangeDays=5, 2026-10-20..2026-10-24` 对比 `days=0 → rangeDays=0` 全时间，与单测同源。两台实例验证后关闭，8181/15173 归属核对为其他进程、未动。

⚠️ 本轮踩到的一条**验证前置**（不是业务 bug，但长得一模一样）：自签 token 的 secret 必须取 active profile 那份。`application.yml:149` 与 `application-dev.yml:107` 的 `jwt.secret` 默认值**不是同一个**（长度 79 vs 58 的占位符，解析后 65 vs 44），从前者签出的 token 打任何端点都是 401，与「权限闸门失效」无法区分。判据只有一条：跑矩阵前先打 `/api/user/me`，`code:0` 才继续。

---

## 附录 H：§4 隐私硬性要求的接口层逐条取证（2026-10-25）

### H.1 为什么单独验这一段

§4 全文写着「硬性要求」，但此前的验收（B.3 / C.4 / D.2）测的是**功能分支对不对**（保护态出没出保护态、lift 算得对不对），没有一条正面证明过「不返回标识符」「不能 AND 下钻」这类**否定式**断言。否定式要求只能靠主动攻击来取证。这一节把 5 条规则逐条钉上证据，并在 H.3 记下一条真实偏离。

### H.2 取证矩阵（默认阈值 10/5/10/20，自建 8197 后端，只读）

| §4 规则 | 攻击 / 观测方式 | 实测结果 | 判定 |
|---|---|---|---|
| 前置：谁能读 | 4 个端点各发三种身份：无 token / USER 角色 token / ADMIN token | 四个端点全部 `无token=401 / USER=403 / ADMIN=200 且 code=0` | ✓ 越权拿不到数据（`AdminDashboardController` 类级 `@PreAuthorize`） |
| 1 保护态最小暴露 | 读者数 1 < min-cohort 的书 `12638 (TOUCH)` | 只给 14 个字段（`bookId,bookTitle,basis,rangeDays,rangeStart,rangeEnd,readers,minCohortSize,sampleProtected,profiledUsers,siteReaders,basisNote,dimensions,highlights`），`dimensions=[] highlights=[] profiledUsers=0`，`readers=1` 与 `minCohortSize=10` 同时给出 | ✓ 与 §4.1 第 3 条要求的降级展示一致：管理员知道是被保护而不是没人读 |
| 2 小桶并入「其他」 | 分布态的书 `24715 (DEEP)`，readers=10 / profiled=8 | 15 个维度里 **14 个只剩一个「其他」桶**（男/女、各年龄段等全部 `<5` 被并掉），只有 `CHAT_STYLE` 保住 `DEEP:5` | ✓ 单桶都不低于 `min-bucket-size` |
| 3「其他」不给 lift | 同上 | 14 个「其他」桶 `lift=null, significant=false`；被保留的真实桶 `DEEP lift=1.17`（未过 `significant-min-count`） | ✓ |
| 4a 不返回标识符 | ① 把 4 个端点在三种身份下的**全部响应键**收集起来，用 `user_?id\|nickname\|email\|phone\|avatar\|password\|token` 正则扫；② 反过来扫**值**：我自己就是那 12 个 APPROVED 读者之一（`/user/me` 的 15 个字符串字段全拿来当探针：昵称「天上月」、签名、邮箱、头像路径、生日 1994-10-11、MBTI=ENTJ…），再看邮箱/手机号/头像路径三种形态 | ① 命中禁用键 `[]`；② 全部画像响应的字符串值总数只有 34~70 种，逐一枚举后值域就是「状态文案 + 书名/作者 + 维度 key 与显示名 + 桶标签 + 口径说明」，我的昵称/签名/邮箱/头像/生日 **一次都没出现** | ✓（桶取值有例外，见 H.2.1） |
| 4b 不能 AND 下钻 | ① 追加未知参数 `&dim2=AGE&value2=FEMALE`；② 重复已知参数 `&dim=AGE&value=31-40`（想拼出两维）；③ 往 `value` 里塞逗号 `"FEMALE,31-40"` | ① 响应与基线**逐字节相同**（未知参数被丢）；② `Spring` 把重复参数并成 `"GENDER,AGE"`，白名单直接拒：「画像维度不合法，可选值见 /book-profile 返回的 dimensions[].key」——**硬失败，不是静默降级**；③ 只在同一维度内解释，返回空榜 | ✓ 跨维交集在接口层无法构造 |
| 5 反查读者数下限 | 见 H.3 | 修复前 `sortBy=READERS` 可绕过；修复后按 `min-bucket-size` 生效 | **原实现偏离，已改正** |

补充：`highlights` 在该书上是 `["读者结构与全站接近，无明显人群偏好"]`——14 个维度全塌成「其他」时结论仍然诚实，没有编出人群偏好。

#### H.2.1 桶取值不是标识符——但扫描必须这样写才对

第一版扫描脚本按「7 位纯数字」找用户 ID，是**错的**：dev 的 `users.id` 是自增小整数（`/user/me` 返回 `id:1`），7 位数字反而是书名/ID 之外的东西，那条断言等于没测。改成拿自己的真实字段当探针之后，撞出一个必须讲清的边界：

把 `min-bucket-size` 降到 2 时，`book-profile` 的桶会带出 `value: "MALE"`、`"TECH"` 这类**存储取值**（前端要用它去发反查请求，无法省掉），而我的 `gender` 恰好就是 `MALE`、`occupation` 恰好是 `TECH`——脚本于是报「身份命中」。这不是泄漏：**桶取值标识的是一个桶，不是一个人**，全站任何一个人的邮箱也不可能等于 `MALE`。真正的个体级标识（昵称、签名、邮箱、头像路径、生日、只有我一个人有的 MBTI 组合）在四个端点的任何响应里都没有出现过。

所以这条要求的正确测法是「个体级标识符 + 无用户维度的键」，不能写成「响应里不许出现与用户字段同名的字符串」——按后者写出来的断言会在阈值一降就全线红，接着就会有人去放宽断言而不是修测试（R 系列踩过的老路）。脚本 `%TEMP%\kbook-verify\h2b-identity.js` 保留了这个区分：键扫描 + 个体值探针 + 邮箱/手机号/头像三种形态，桶取值单列不判失败。

### H.3 规则 5 的实现偏离：隐私下限挂在排序参数上

原代码 `int minCount = byLift ? minBucketSize : 1;`（[BookProfileService.java:199](file:///c:/server/kbook/backend/src/main/java/com/kbook/service/admin/BookProfileService.java#L199) 之前的一行）把 §6.2 的降噪理由当成了适用条件：只有 `sortBy=LIFT` 才要求 `count >= min-bucket-size`，`READERS` 的下限是 **1**。

要命的地方在于**前端默认排序就是 `READERS`**（[profile-books.tsx:72](file:///c:/server/kbook/frontend/src/pages/admin/profile-books.tsx#L72)），而且页面上那行说明原文写的是「集中度榜要求该书落在该桶的读者不少于阈值」——等于明示管理员：换到「按人数」就没有这道门槛。

把 `min-cohort-size` 用 env 降到 1（只改启动参数，不改代码、不写库）隔离出这条分支后，实测响应里直接出现点名级条目：

```
GENDER=MALE   READ  榜=20 本，count<5 的有 20 本（最小=1）   例：聚宝盆 count=1 readers=1 ratio=1 lift=1.5
GENDER=FEMALE DEEP  榜=2 本，count<5 的有 2 本（最小=1）    例：清醒思考的策略 count=1 readers=2 ratio=0.5
AGE=31-40     TOUCH 榜=50 本，count<5 的有 50 本（最小=1）
```

`count=1 / readers=1 / ratio=1` 的语义是「这本书的全部读者就是那 1 个人，且已知他属于该人群」——§4 开头举的反面例子（3 个读者都是女性 31-40 岁 = 点名）在这里是 1 个读者，比反面例子更糟。

**为什么默认阈值下在 dev 上看不见**：`bucketUsers < min-cohort` 先被 [BookProfileService.java:187](file:///c:/server/kbook/backend/src/main/java/com/kbook/service/admin/BookProfileService.java#L187) 挡住，而 dev 只有 12 个 APPROVED 用户，没有任何人群桶能到 10 人。这条漏是**潜伏**的：生产上 `CHAT_STYLE=DEEP` 这类人群轻松过 10 人，而段内按书的读者数常年是 1~2，默认阈值 10/5 下就会真的出榜。这也是本附录拒绝把「dev 没复现」当成「不存在」的原因。

**改动**（判定依据：`sortBy` 是客户端随便传的，一条隐私硬要求不能由调用方选择是否生效；这不是产品取舍，是漏实现）：

| 文件 | 改动 |
|---|---|
| `BookProfileService.java` | `int minCount = byLift ? ... : 1` 删除，候选过滤恒用 `>= minBucketSize`；方法 javadoc 补两句「两道闸门都与排序无关」；保留一行 why 注释防止后人把条件加回来 |
| `profile-books.tsx` | 阈值说明不再只在 `LIFT` 时出现，两种排序都写明「只收录达到隐私阈值的条目」；空榜文案原来暗示「换时间/换口径就有数据」，改成先说明是隐私下限拦住的 |
| 本文档 §6.2 | 该句从「`sortBy=LIFT` 时后端内部要求」改为「与 `sortBy` 无关的硬闸门」，并注明降噪是副产品不是适用条件 |

签名、DTO、返回结构都没动，`getProfileBooks` 只有 Controller 一处调用（R3 grep 确认），因此不涉及其他引用点。
**验证（成对，只动 env）**——关键是同时证明「下限跟着配置走」而不是「把页面改空」：

| 实例 | cohort | bucket | `sortBy=READERS` 行数 | count 直方图 | 低于下限的行 |
|---|---|---|---|---|---|
| 8199（修复后） | 1 | 2 | 30 | `2:22 3:4 4:2 5:2` | **0** |
| 8198（修复后） | 1 | 5（=生产默认） | 2 | `5:2` | **0** |
| 8198（修复前） | 1 | 5 | 单个人群最多 50 行，全部 `count<5` | 最小 1 | 大量 |

`LIFT` 榜与 `READERS` 榜的行数在两个实例上都**完全相同**——修复后两种排序只差排序键，不再差成员集合。

**没做的事 → 已补（2026-10-25，见附录 H.9）**：这条闸门当时只有运行时证据、没有单测，理由是 `getProfileBooks` 要 `EntityManager` 原生 SQL 桩。补法就是当初记下的那条：把候选过滤抽成 `BookProfileCalculator` 的纯函数再测，不引 Mockito。

**校准代价**：dev 上 `bucket` 从 2 提到 5 让这张榜从 30 行缩到 2 行。生产上线前不要猜：挑 3 个人群（一大一中一小）直接打 `/profile-books?sortBy=READERS` 看还剩几行就够。**不要**为此在 `profile-threshold-audit.sql` 里重算人群归一化——那会造出第二份取值口径，正是 F 与 G.8 一直在消的东西。

### H.4 顺带量到的一件事：默认阈值下「人群选书」的选择器是空的

`profile-dimensions`（前端拿它当维度/分桶选择器）在默认阈值 10/5 下，15 个维度有 **14 个只剩 `value=null` 的「其他」**，唯一带真实取值的是 `CHAT_STYLE: DEEP(6)`。而「其他」不在白名单里（H.2 的 4b 第 ② 条），所以选中它必然空榜；这些维度却又照样报 `queryable=true`。结论：dev 上这个页面从选择器这一步就没有可用入口，不只是榜单为空。

这与 §4.1 第 4 条是同一族问题（样本薄 + 阈值按生产拍），**没有动代码**：是否在阈值 10/5 下隐藏「无人可选项」的维度、或在选择器里标注「该维度全部取值都低于出榜阈值」，属于产品决策，记在 H.5。

### H.5 需要拍板的两条（2026-10-25 已拍板：①保持 bucket=5；②退化维度自动不可反查——附录 AN）

1. **段内交集的下限用哪个阈值**：现在按 §4 规则 5 用 `min-bucket-size`(5)。更严的选法是 `min-cohort-size`(10)——理由是「某书在该段里只有 5 个读者」本身仍是 5 个人的集合，而榜单同时给了 `readers` 总数，`count=5 / readers=5` 等于说「这本书的全部读者就是这 5 个人」。改成恒用 `min-cohort-size` 是一行，但会让长尾人群彻底出不了榜。
2. **H.4 的选择器退化怎么处理**：隐藏无可用取值的维度 / 加标注 / 维持现状（认为生产不会出现）。

### H.6 环境与回收

本轮自建 8197 / 8198 / 8199 三个后端实例，阈值一律用 `KBOOK_PROFILE_*` env 下发（`minCohortSize` 在 `book-profile` 响应里回显，用来确认 env 真的生效），**没有改过任何一行配置默认值，没有写库**。用户自己的 8181 / 8182（`C:/server/kbook`）与 `D:/item/kbook` 全程未动，关停前逐个核过 `CommandLine`。脚本与响应快照都在 `%TEMP%\kbook-verify\`（`privacy-audit.js`、`rule5-diff.js`、`rule5-paired.js`），按约定不入库。

### H.7 §7.4 缓存核对：实现比设计更合理，所以改的是文档

顺着「隐私阈值是配置项」这条去看缓存键，发现 §7.4 写的 `siteBaseline` key = `dimKey|rangeKey` 与实现（单键 `site`）不一致。判定谁错之前先看了基线那条 SQL：

```java
"SELECT COUNT(*) FROM users u WHERE u.status = 'APPROVED'"          // 分母，无时间谓词
"SELECT " + dim.getValueSql() + " AS bucket, COUNT(*) AS cnt"
    + " FROM users u WHERE u.status = 'APPROVED' GROUP BY bucket"    // 15 个维度一次循环算完
```

`WHERE` 里只有 `status='APPROVED'`，**没有任何时间条件**——与 §3 对 `siteRatio` 的定义（全站同桶人数 / 全站该维度有效人数，人口口径）一致。所以：

1. 按 `rangeKey` 分键是错的：同一份基线会按区间重复存 N 份，`maximumSize=64` 这个数字本来就是按重复存设计的。
2. 按 `dimKey` 分键也没意义：SQL 在 Service 里一次循环算全维度，缓存的是整个 `SiteBaseline` 对象，拆键等于把一次 SQL 拆成 15 次。
3. 因此实现（单键 + `maximumSize=4`）是对的，**改的是 §7.4 的文本**，已在原文下方以「实现期的修正」记下，不改代码。

`bookProfile` 那条与 §7.4 一致（`bookId|basis|window.key()`，TTL 5 分钟，`maximumSize=500`）；`Window.key()` 对自定义区间产出 `start~end`，所以同书同 basis 的不同区间不会互相污染。另外阈值只能启动时用 env 下发、改阈值必须重启，因此不存在「新阈值配旧缓存」的错位窗口。

### H.8 修复后的两处文案在浏览器里复核（2026-10-25）

H.3 只证明了接口返回值，文案改动还得在页面上看一眼。环境：8198（修复后的代码，`KBOOK_PROFILE_MIN_COHORT_SIZE=1`、`min-bucket-size` 用默认 5）+ 同源静态服务器 15196，服务的是 `vite build --mode verify` 产物（不带 `.env.production`，响应里 API 走相对 `/api`——上一轮误用生产构建加载本地 token，已经把这条列成硬性前置）。

取「共读风格 → 深度分析 → 深度互动的」这个当时唯一非空的榜，同一屏切两种排序：

| 断言 | 结果 |
|---|---|
| 停留在 `/admin/profile-books` | 通过 |
| `READERS` 与 `LIFT` 的**行成员集合**一致 | 通过（都是同一行 `生命密码…` 5 人 / 共 10 人 / ×0.58） |
| 每行 count ≥ 5 | 通过 |
| 两种排序都写明隐私阈值 | 通过。人数榜：「人数榜只收录该书在该桶读者数达到隐私阈值的条目，小样本不进榜」；集中度榜同句换开头 |
| 无 4xx / JS 错误 | 通过（`console` + `pageerror` + `/api` 响应码三路监听，0 条） |

其中第 3 条断言一开始写成「页面上要有『与排序方式无关』」，FAIL 了——这句只在**空榜**文案里，有榜时的 hint 不带它。这是断言选错了字符串，不是 UI 漏渲染：有榜的两句话都没有暗示阈值随排序变，所以不补文案（加了等于把隐私闸门又跟 sortBy 在观感上绑一次）。

**顺带量到一个既有行为，不是本次改动引入**：直接冷加载 / 刷新任何 `/admin/*` 深链会被送回 `/home`，而从 `/profile` 点「人群选书」做客户端导航正常进入。当时排除的：角色守卫不符（`AdminGuard` 只看 `role`）、我改坏了（本次只动了 `BookProfileService` 的一行过滤和两个 `<p>` 文案，守卫与路由一行没碰）、注入时机太晚（`hydrate()` 在 [main.tsx:36](../../frontend/src/main.tsx) 是模块顶层同步调用，早于 render）。当时以为也排除了「登录态没注入」——理由是 `localStorage` 里 `kbook_token`、`kbook_user_info` 都在、串里能看到 `role: ADMIN`、`/api/user/me` 也返回 ADMIN。

**这个排除是错的，根因就在这条上，已在附录 I.8 定位**：`addInitScript` 传参时 `userInfo` 已经被 `JSON.stringify` 过一次，脚本内又 `JSON.stringify(u)`，落进 `localStorage` 的是**双重编码**串。`hydrate()` 的 `JSON.parse` 因此返回一个**字符串**而不是对象，`userInfo?.role` 为 `undefined`，`AdminGuard`（[AuthGuard.tsx:79](../../frontend/src/components/auth/AuthGuard.tsx)）跳 `/home` 是**正确行为**。"串里能看到 `role: ADMIN`"这个判据只证明了文本存在，没证明它能被解析成对象——所以它当时没能证伪我。`/admin/*` 深链本身没有问题，I.8 用同一台后端、同一个 token、只改注入方式做了 A/B。

### H.9 把隐私闸门抽成纯函数，并用变异测试证明这条防线会叫（2026-10-25）

H.3 留的那条「没有单测」补上了，做法就是当初记的那条：抽成 `BookProfileCalculator` 的纯函数，不引 Mockito。

| 位置 | 改动 |
|---|---|
| `BookProfileCalculator.Thresholds` | 新增 `isCountPublishable(long)`（`count >= minBucketSize`），与既有的 `isSampleProtected` 配成一对：人群闸门 + 格子闸门 |
| `BookProfileCalculator` | 新增静态 `rankingCandidates(bucketCounts, thresholds, poolSize)`：先过下限、再按人数降序、最后截断到候选池 |
| `BookProfileService.getProfileBooks` | 内联的那段 stream 换成一次 `rankingCandidates(...)`；`minBucketSize` 字段仍只经 `thresholds()` 进入计算，Service 里不再直接读它 |
| `BookProfileCalculator.buildDimension` | 单书并桶的 `kept.removeIf` 也改用 `isCountPublishable`——两处视图从此共享同一个判定，同一个人头数不会出现「分布里被藏起来、反查榜上单独成行」 |

签名、DTO、接口路径都没动，`getProfileBooks` 的调用点仍只有 Controller 一处（R3 grep 复认）。

**新增 3 条单测**（`BookProfileCalculatorTest`，§11 范畴）：边界（`== 阈值` 出榜、差 1 不出）、阈值下调后新增的只有刚好过线的那本、候选池截断发生在下限之后、以及「同一个 4 人格子在单书分布里进『其他』且 `lift=null`，在反查侧不出榜」。

**变异测试**——只加用例不验证用例是假的，所以把判定故意改错两次看谁红：

| 变异 | 变红用例 |
|---|---|
| `>=` → `>`（过严，会误杀刚好达标的书） | 3 条新用例全红 |
| `>=` → `>= minBucketSize - 1`（过松，即历史泄漏的方向） | 3 条新用例 + 既有的 `smallBucketMerge` 一起红 |

第二行是这次抽取真正买到的东西：阈值一旦被放松，单书并桶与反查出榜**同时**告警，不再需要有人记得改两处。变异均已还原，还原后 `mvn -o test` **122 例全绿**（此前 119）。

**运行时差分**（重构前实例与重构后实例同环境对打，证明抽取没改行为）：

| 对比 | 内容 | 结果 |
|---|---|---|
| 8198（重构前）vs 8200（重构后），cohort=1 / bucket=5 | 12 个人群对 × 3 个 basis × 2 种排序 = 72 个榜单响应，加 3 本书 × 2 个 basis 的 6 个 `book-profile` 响应，逐字段比 `[bookId,count,readers,ratio,lift]` 与整对象 | **78 例 0 差异**；新实例全局最小 `count=5`，`count<5` 的泄漏行 0 |
| 8199（重构前）vs 8201（重构后），cohort=1 / bucket=2 | `rule5-paired.js` 全量输出 | 除首行端口号外**字节相同**：合计 30 行、直方图 `2:22 3:4 4:2 5:2`、低于下限 0 |

顺带纠正 H.3 那张表的读法：「30 行」是 11 个人群对 × 两种排序的**合计**，不是单个人群榜的长度；单个人群在该阈值下最多 3 行。

**仍然没做的**：没给 Controller 加 `@WebMvcTest`。阈值是 env 下发的启动期配置，能被测到的最小完备单元就是这个纯函数；为了「 sortBy 传什么都一样」这条再养一套 Spring 切片测试，成本高于收益——何况 H.8 已经在浏览器里把两种排序的成员集合比过一次。

---

## 附录 I：当前构建上的 §11 + §4 全量重跑（2026-10-25，抓到 1 个真 bug）

### I.1 为什么要在「已经全绿」之后重跑

附录 D（§11）、G（分布态运行时）、H（§4 逐条取证）三份证据分别取于 F（写入端校验）和 H.9（闸门抽纯函数）这两次代码改动**之前**。测试数从 107 涨到 122、接口消息也动过（H.3 的硬失败文案），旧记录里的"实测值"因此已经不代表当前构建——**"当时测过"会随改动过期，这是唯一的重跑理由**。这一轮不改功能，只把 §11 接口层与 §4 规则 1–5 的断言合成一个脚本（`regress-current.js`，一次性产物，不进仓库）在**同一个构建**上重跑。

### I.2 重跑矩阵：14 条，FAIL 0

8300 = 默认阈值 10/5/10/20，8301 = `KBOOK_PROFILE_MIN_COHORT_SIZE=1`（bucket 仍 5），只读 dev 库。

| 断言（出处） | 实测值 |
|---|---|
| 不存在的 `bookId` → "图书不存在"（§11） | `code≠0`，消息 `"图书不存在"` |
| **入参笔误给可读的 400，而不是 500 兜底**（§11，本轮新增） | 空 `bookId`=`HTTP 400 "参数「bookId」取值不合法"` ｜ 脏 `basis`=`HTTP 200 "读者口径不合法，可选值：READ / TOUCH / DEEP"` ｜ 缺 `days`=HTTP 200（有默认值） |
| 跨度 >366 文案与主看板逐字一致（§11） | 两侧同串：`"自定义区间最长支持 366 天，全部数据请使用「全部」"` |
| `days` 与 `start/end` 同给以 `start/end` 为准（§11） | `days=0&start=2026-10-20&end=2026-10-24` → `rangeDays:5`；单 `days=0` → `rangeDays:0`（同 cohort/dims，只差区间口径） |
| 反查传存储值命中、传展示名返回空而非 500（§11） | `value=MALE` 行数 0（dev 样本不足，非报错）；`value=男` → `[]`，HTTP 200 |
| 非法维度硬失败并给出可选值（§11 / H.3） | `"画像维度不合法，可选值见 /book-profile 返回的 dimensions[].key"` |
| 三种 `basis` 各自可用，**不**断言单调（§11） | `{READ:0, TOUCH:0, DEEP:10}`——单调关系不成立，与 2.1 的实测一致 |
| 4 个端点 + 看板主接口：无 token=401 / USER=403 / ADMIN=200（§4 第 6 条） | 5 条全部 `401/403/200` |
| 保护态只给总数与阈值（§4 第 1 条） | `readers=1`，`dimensions=[]`，未知字段 `[]`（即除白名单字段外什么都没漏） |
| 小桶并进「其他」且「其他」无 lift（§4 第 2/3 条） | 15 个维度，违规小桶 `[]` |
| 响应无个体级标识符键（§4 第 4 条） | 命中 `[]`，扫描键总数 31 |
| AND 下钻不可行（§4 第 4 条） | 未知参数被忽略；`value=A,B` → `"缺少必填参数「value」"`；重复 `dim` → 硬失败文案 |
| 出榜下限=5 且与 `sortBy` 无关（§4 第 5 条 / H.3） | 总行数 2，最小 `count=5`，泄漏 0，两种排序成员差异 `[]` |
| H.4 记录项（不断言） | 默认阈值下 15 个维度里 14 个只剩「其他」，其中 9 个仍 `queryable=true` → 仍是 H.5 那条待拍板 |

### I.3 重跑真正买到的东西：`?bookId=` 是一条裸 500

第 2 行原本 **FAIL**：`/book-profile?bookId=&basis=READ&days=0` 返回 `HTTP 500`，堆栈是 `NumberFormatException: For input string: ""`。

原因不在画像模块：`MethodArgumentTypeMismatchException` / `MissingServletRequestParameterException` 在进 Controller 方法体**之前**由 Spring 抛出，落不到 `BusinessException` 分支，于是一路掉进兜底的 `SYSTEM_ERROR`——把调用方的笔误报成服务端故障。这不违反 §4 的任何一条（500 不带数据），但它抹掉了"到底哪个参数错"的信息，而画像接口偏偏有一堆可选参数；附录 D 当时只测了「业务校验」那一层（口径非法 → 200 + 业务消息），类型层没测，所以这条漏在两份旧证据里都不存在。

修法：`GlobalExceptionHandler` 新增 `handleParam`，状态码与消息体沿用既有参数校验约定（400 + `ErrorCode.PARAM_INVALID`，前端 `request.ts` 对非 401 读 `response.data.message`，因此消息能原样显示）。**只回显参数名、不回显提交值**——错误消息会被前端渲染，而历史入参里确实存在 `{{7*7}}` 这类注入串（12 节 risk 表）。

一处边界值得记：`basis={{7*7}}` **不是** 400。它是类型合法、取值非法，走 Service 的业务校验，返回 200 + `"读者口径不合法…"`。两条路径的分工是「Spring 层拦类型/缺失，Service 层拦取值」，把后者也改成 400 会推翻 `BusinessException` 的既有约定。

### I.4 新分支的 4 条单测与变异测试

`GlobalExceptionHandlerTest`（纯单测，`MockHttpServletResponse`，不引 Spring 切片）：类型不匹配点名参数、缺失参数点名参数、消息不等于兜底的"系统繁忙"、以及一条反射断言——`handleParam` 的 `@ExceptionHandler` 注册表恰好是这两类异常且 `@ResponseStatus` 为 400。

| 变异 | 变红的用例 |
|---|---|
| 消息改成 `"…取值不合法: " + mismatch.getValue()`（回显提交值） | `typeMismatchNamesParameterAndNotValue` |
| 从 `@ExceptionHandler` 数组里摘掉 `MissingServletRequestParameterException` | `scalarParamExceptionsAreAllRegistered` |

两变异同时注入、只红这两条，与预测一一对应，均已还原。**为什么要那条反射断言**：单测是直接调 `handleParam` 的，漏注册在单测里毫无感觉（方法照样返回正确的 400 消息），只有反射检查注册表才抓得到——那正是 I.3 那个 bug 的形态。仍然没有 `@WebMvcTest`，与 H.9 同一判断；I.2 第 2、12 行是这条分支的运行时凭据（`缺少必填参数「value」` 就是 `MissingServletRequestParameterException` 真被走到了）。

### I.5 脚手架自己的三个 bug（负结果，别当业务 bug 读）

第 4 行曾长期"PASS"，而它是假的——三个都是我的断言写错，不改代码：

1. 断言 `basis={{7*7}}` 必须 400。业务校验按约定是 200 + 业务消息，断言放宽为「不是 500」。
2. 判定"同区间"的 `key()` 只比了汇总字段（`readers` / `dims`），三种入参下这些字段本来就相同，比不出区间口径。
3. 决定性的一条：`key(both)` 传进去的是 `{status, j, text}` 包装对象而不是 `both.j`，于是三个 `JSON.stringify(undefined)` 全相等 → 恒 PASS。补上 `rangeDays/rangeStart/rangeEnd` 并用直接 curl 复核，才拿到 I.2 那行真实的 `5` vs `0`。

教训是通用的：**断言里出现"两个东西相同"时，先证明它俩可能不同**（把其中一个换成明显不同的值，看断言是否变红）。H.8 那条 UI 文案断言取错字符串是同一个错误的另一面。

### I.6 测试盘子与环境回收

`mvn -o test` **126 例全绿**（此前 122）：`ProfileValuesTest` 73 + `BookProfileCalculatorTest` 24 + `TargetAudienceRulesTest` 13 + `AdminRangeRulesTest` 12 + `GlobalExceptionHandlerTest` 4。前端 `npm run build` 未动（本轮无前端改动）。

环境：自建 8300/8301 两台（I.2 的矩阵）+ 后续为 I.7 重跑的 8300 与 15196 静态同源代理，全部 CommandLine 核对归属后才启动、验证完全部关闭；未触碰 8181/8182/15173/15174/15180 等既有端口与 `D:\item\kbook` 那份副本，MySQL/Redis 未写一行。token、脚本、`dist-local` 都在 `%TEMP%\kbook-verify`，未进仓库。仓库里那个历史遗留的「backend/.verify-token.txt」之前**没被 gitignore**（`git add -A` 就会把自签 token 提上去），已在 `.gitignore` 补一行 `.verify-token.txt` 挡住；文件本身不属于本轮产物，没擅自删。

### I.7 把 H.8 留的「admin 深链原因未定位」做实——它不是 bug，是我的脚手架

H.8 记了一条找不到原因的观察：冷加载 `/admin/*` 被送回 `/home`。这轮把它定位了，而且定位过程本身产出了三条硬前置。

A/B 只改一个变量：同一台本地后端（8300）、同一个 token 文件、同一个 dist 目录，只改 `userInfo` 的注入方式。

| 注入写法 | 冷加载 `/admin/dashboard` 结果 |
|---|---|
| `addInitScript(([t,u]) => localStorage.setItem('kbook_user_info', JSON.stringify(u)), [token, JSON.stringify(userInfo)])` | `location.pathname=/home`，**0 次 `/api` 调用**，页面上是「未登录」 |
| 传原始 JSON 串，脚本内不再 `stringify` | `location.pathname=/admin/dashboard`，正常渲染 |

机制：`JSON.stringify` 被套了两次，`localStorage` 里存的是 `"{"id":1,...}"` 这种**双重编码**串；`hydrate()` 的 `JSON.parse` 成功返回一个**字符串**（`auth.ts:69`，try/catch 不会触发），于是 `userInfo?.role` 是 `undefined`，`AdminGuard` 跳 `/home` 属于**正确行为**。H.8 当年用「串里能看到 `role: ADMIN`」排除了登录态问题——那个判据只证明文本在，不证明能被解析成对象，所以它没能证伪我。

另外两条是这次重跑顺手抓到的环境陷阱，比第一条更危险：

1. **`frontend/dist` 是 `.env.production` 构建**（`VITE_API_BASE_URL=https://book.keiskei.top/api`）。我第一次直接 serve 它，页面流量全部打到现网，还带着本地自签的 ADMIN token。H.8 已经写明「上一轮误用生产构建加载本地 token，已把这条列成硬性前置」，这一轮我照样犯了——因为我接手的是历史脚手架的默认参数，没有核对产物里烘了什么地址。
2. **Git Bash 会把环境变量值做 POSIX→Windows 路径转换**：`VITE_API_BASE_URL=/api` 进了 bundle 变成 `C:/Program Files/Git/api`，运行时是 `fetch("C:/Program Files/Git/api/auth/refresh")`，页面显示「Unsupported protocol C:」。`MSYS2_ARG_CONV_EXCL='*'` **不管用**（只管 argv），要 `MSYS2_ENV_CONV_EXCL=VITE_API_BASE_URL`。

现网那条要如实说清边界：3 次页面加载发出的是十来个只读 GET（`/api/user/me`、`/api/home/stats`、`/api/notes/timeline`、`/api/book-trash/count`、`/api/debate/sessions` 等），我的监听只记状态码与方法未逐条留档；`/api/user/me` 拿到 HTTP 200。**没有定性**——这个应用的 `BusinessException` 也走 HTTP 200 + 非 0 code，所以 200 不等于鉴权通过。我之后没有向现网发任何定向请求（不碰生产流量），把它留给用户核实的一件事写清楚：**现网 `JWT_SECRET` 是否仍是 `application.yml:149` 里的默认值**；若是，任何能看到仓库的人都能自签 ADMIN token，这与本次事故无关，是独立的暴露面。

修正脚手架后，用当前构建重做深链验证（本地 8300 + `dist-local` + 15196 同源代理）：

| 冷深链 | path 保持 | 命中的本地接口 | 渲染 |
|---|---|---|---|
| `/admin/profile-books`（本功能新增页） | 是 | `200 /api/admin/dashboard/profile-dimensions` | 正文 450 字，图 0——默认阈值 10 下该页合法为空（H.4） |
| `/admin/dashboard` | 是 | `200 /api/admin/dashboard?days=7` | 8 个 `.recharts-surface` |
| `/admin/books` | 是 | `200 /api/admin/books/scan/status`、`/embeddings/stats` | 正文 510 字 |

三次都 0 `pageerror`、0 控制台 error。**结论：`/admin/*` 深链没有缺陷，H.8 那条观察降级为「脚手架产物」，不立项。** 下次起本地 UI 验证前的三条 grep 前置（都要为空/为真才算环境就绪）：`grep -r "book.keiskei.top" <dist>/assets`、`grep -r "Program Files/Git" <dist>/assets`、以及读一次 `localStorage.kbook_user_info` 并 `JSON.parse` 后断言 `typeof === 'object'`。

### I.8 本轮之后仍未做的

I.2 只证明"当前构建与 D/G/H 记录的结论一致"，I.7 只消掉一条伪缺陷，都没有推进分期。仍然卡在同一处：H.5 两条待拍板、P3 吻合度口径（单书版 vs 全站版）、A.3 阈值清单在生产库重跑、历史脏值 NULL 化、`book_profile_daily`。

---

## 附录 J：把 I.2 那 14 条矩阵沉淀成仓库内脚本（2026-10-25）

### J.1 动机

I.2 的矩阵此前只活在 `%TEMP%\kbook-verify\regress-current.js` 里。它在上一轮抓到过一个真 bug（I.3 的裸 500），但脚本本身会随环境回收消失，下一轮验证只能凭文档重敲一遍断言——而 I.5 记的三个假 PASS 恰恰就是"重敲断言"的产物（比较函数漏字段、传错对象、把业务 200 当 400 断言）。所以把它落进仓库：`deploy/verify/admin-profile-matrix.mjs`。

用法（全程只读 GET，不写库；token 走文件，不进 argv——Windows 进程列表会暴露命令行）：

```bash
node deploy/verify/admin-profile-matrix.mjs \
  --base=http://127.0.0.1:8300/api/admin/dashboard \
  --loose=http://127.0.0.1:8301/api/admin/dashboard \
  --token-file=$TEMP/kbook-verify/.tok --token-user-file=$TEMP/kbook-verify/.tok-user
```

`--book` / `--protected-book` 默认 `24715` / `12638`（附录 A.1 的 dev 库样本）。**换数据集只改这两个参数，不许改断言**——改断言就是 I.5 那个错误的重来。

### J.2 与临时脚本唯一的实质差别：前置条件先于结论

`0=全绿 / 1=有 FAIL / 2=前置不满足`。分开这两个退出码是本脚本的全部意义：环境不对时，「响应无标识符」「不能 AND 下钻」这类**否定式**断言会虚假变绿（本轮实测：token 签错密钥后 9 条红、5 条绿，而那 5 条绿毫无价值）。三条前置：

1. ADMIN 与 USER token 都能打 `GET /api/user/me` 拿到 `code:0`。secret 必须取 **active profile** 那份（`application-dev.yml` 覆盖 `application.yml`，G.9 末条记过）。
2. `--loose` 的 cohort 闸门确实调低了：**用 `--protected-book` 反向证明**——同一本书在 base 上是保护态、在 loose 上必须不是。
3. `--book` 在 loose 上可取数（`code:0`）。

前置 2 最初的写法是错的，值得留着当反面样本：我原本拿 `--book`（一本有 10 个读者的书）探测 `sampleProtected`，可它在默认阈值下本来就是 `false`，于是**把严格实例当 `--loose` 传也照样 14 行全绿**——一条声称能失败的断言实际永远不会失败。改成用保护态的书反证之后，同一操作立刻 exit 2。

### J.3 本轮实测证据

| 场景 | 期望 | 实测 |
|---|---|---|
| 正常（8300 默认阈值 + 8301 `MIN_COHORT_SIZE=1`） | exit 0，14/14 PASS | ✅ 与 I.2 逐行一致，含 `rangeDays=5` vs `0` 的优先级行 |
| USER token 换成错密钥签的 | exit 2，PASS 行数 0 | ✅ 前置 1 拦住，一条断言都没跑 |
| 不传 `--loose` | exit 2 | ✅ |
| 把 8300（严格）当 `--loose` 传 | exit 2 | ✅ 修正前置 2 之后才成立；修正前是 14 全绿 |
| `--loose` 指到 `MIN_BUCKET_SIZE=1` 的 8302 | **必须变红** | ✅ exit 1，2 条 FAIL：`§4-2/3`（27 个 `count<5` 的桶留在榜上）与 `§4-5`（最小 `count=1`，出现《清醒思考的策略》`count=1 readers=2` 这种"全部读者就是那 2 个人"的行，且 `sortBy=READERS` 与 `sortBy=LIFT` 成员集不同） |

最后一条是这个脚本的变异测试：**把出榜下限放松，它确实会红**，而且是两条独立的视图同时报警（分布态与反查榜），与 H.3 当年那条 `int minCount = byLift ? minBucketSize : 1` 的缺陷同形。也就是说 1244 行的 `count=1` 泄漏在放开门闸的瞬间就被抓住，不用等下一次人肉审代码。

### J.4 顺带记一条脚本运行环境的坑

`process.exit()` 紧接在 `fetch` 之后调用，Windows 下会撞 `Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)`，退出码变成 **127**，把上面精心分开的 0/1/2 契约全部抹掉。改成先设 `process.exitCode`、再挂一个 `.unref()` 的 500ms 定时器兜底即可。任何用全局 `fetch` 写的验证脚本都会遇到，值得在别处复用这条。

---

## 附录 K：「谁算读者」从 7 份收成 1 份（2026-10-25）

### K.1 又是同一个病

D.1、F、G.8、G.9 都是同一类问题的不同马甲：一条口径写两遍，改一处漏一处。这次 grep 出来的不是「两份」，是**七份** `u.status = 'APPROVED'` 字面量，全在 `BookProfileService` 里，分别贴在读者池、榜单分子、榜单分母、分桶命中读者、本书维度分布、全站基线人数、全站维度分布、已填画像人数这几条 SQL 上。

它的危险度和 D.1 那条不是一个量级，值得单独说一句：**漏掉其中任何一处都不会报错**。不会 500，不会变慢，返回的 JSON 形状完全一样——只是未审核账号会混进读者池。后果沿第 4 节展开：`readers` 分母虚高会把该走保护态的书算成分布态，全站基线被污染会让所有 `lift` 一起偏，反查榜上可能出现「只有未审核账号读过」的书。这类漂移是静默的，而且恰好落在 §4 明令禁止的那一侧。

### K.2 落点

| 文件 | 改动 |
|---|---|
| `service/admin/ProfileReaderSql.java`（新） | 包内静态类，只做字符串拼接、不碰 `EntityManager`。`private static final String APPROVED` 是全项目唯一的「谁算读者」定义，另有 `APPROVED_JOIN` 供两条子查询共用。区间条件（`window.condition("x.ts")`）与维度取值 SQL（`dim.getValueSql()`）仍由调用方传入——它们不是这条口径的一部分 |
| `BookProfileService.java` | 七处内联 SQL 换成 `ProfileReaderSql.*`，删掉私有 `bookUserAggregate` 与 `bookUserSource`；服务里剩下的 `APPROVED` 字样只在两句注释里 |

引用点（R3）：被删的两个方法是 private，调用点全在服务内部（`bucketCountByBook`、`totalReadersByBook`、`readerPool`），签名未对外暴露，Controller 与 DTO 无涉及。

### K.3 验证

`ProfileReaderSqlTest` **15 例**，全量 `mvn -o test` **150 例全绿**（此前 135）。断言的都是编译器和接口层都抓不到的形状：

- 每一条碰 `users` 的 SQL 都带 `u.status = 'APPROVED'`（三口径读者池、三口径×两种谓词的聚合、分桶命中、本书分布、全站人数、全站分布、画像人数）；
- 去重语义：读者池是 `SELECT DISTINCT x.user_id`，榜单两侧是 `COUNT(DISTINCT x.user_id)`——§11 那条「并集去重后不重复计数」在这里第一次有了能跑的断言；
- **谓词逐分支下发**：` AND book_id = :bookId` 的出现次数必须等于该口径的 UNION 分支数（READ 1 / TOUCH 2 / DEEP 3），`UNION ALL` 次数等于分支数减一。这是 §7.2「走得到各表索引」的前提，一旦被"优化"成提到外层，SQL 仍然正确、只是全表物化，接口层测不出来；
- 三口径的时间列与前置条件（§2.1）：`rp.updated_at` + `progress > 0`、`b.added_at` + `h.created_at`、`r/n/s.created_at` + `n.deleted_at IS NULL`；
- 全时间窗口不残留 `:start` / `:end` 占位符（否则 `window.bind` 会绑到不存在的参数）；
- 本书分布与全站基线是同一条 SQL 只差 `ids` 限定，防止两边口径分叉。

变异测试（故意放松，确认测试会红）：

| 变异 | 期望 | 实测 |
|---|---|---|
| 全站基线 `siteBucket` 漏掉 APPROVED | `everyUsersQueryFiltersApproved` 红 | exit 1，`ProfileReaderSqlTest` FAILURE |
| TOUCH 的第二个分支不下发谓词 | `predicateIsPushedDownIntoEveryBranch(Basis)[2]` 红 | exit 1，`:84` |
| READ 时间列 `updated_at` → `created_at` | `basisTimeColumnsAreFixed(Basis)[1]` 红 | exit 1，`:95` |

源码还原后与变异前逐字节相同、再跑仍绿。

**运行时中性**（字符串断言只能证明形状，不能证明 SQL 可执行，所以这一步不能省）：自建 8300 / 8301 跑附录 J 的矩阵，exit 0、14/14 PASS；再与本次改动之前那一次运行的逐行证据对比——13 行**逐字相同**，第 14 行（优先级行）只是脚本 detail 串删了 HTTP 前缀，两侧 `bothData` / `daysData` 的 JSON 完全一致（`rangeDays=5 / readers=10 / dims=15` vs `rangeDays=0 / readers=10 / dims=15`）。两台实例验证后关闭。

---

## 附录 L：看板「全站用户画像」区块是第三份口径副本（2026-10-25）

### L.1 怎么找到的

附录 K 把「谁算读者」收成一份之后，剩下的问题只有一条：**还有谁在独立回答这个问题**。grep `FROM users` 得到的第一个答案是 `AdminDashboardService.getGroupCount(String column)`——主看板 `userProfile.mbtiDistribution / genderDistribution` 就是它算的：

```sql
SELECT 列名, COUNT(*) FROM users WHERE 列名 IS NOT NULL GROUP BY 列名
```

没有 `status`、没有白名单、没有 `TRIM`。第 5.1 节当初只把它当"注入面"警告过（列名拼进 native SQL），没意识到它是同一件事的第二份答案：**同一个看板上的两块内容，对"全站有多少人"给的是两个数**。

### L.2 dev 库实测（36 个账号：APPROVED 12 / PENDING 23 / BANNED 1）

| 维度 | 旧 `getGroupCount`（全账号） | 读者画像口径（APPROVED） | 库里真值（只读 SQL） |
|---|---|---|---|
| gender | `MALE 10 / FEMALE 2` | `MALE 4 / FEMALE 2 / 未填写 6` | APPROVED 且 `gender='MALE'` = **4** |
| mbti | `INTJ 9 / ENTJ 1 / INFP 1 / ISTP 1 / ENFP 1` | `INTJ 2 / ENTJ 1 / INFP 1 / ISTP 1 / 未填写 7` | APPROVED 且 `mbti='INTJ'` = **2** |
| 求和 | 13（≠ 任何人数） | 12 = `siteReaders` | 12 |

三条后果，按严重程度排：

1. **脏值可以当桶标签渲染**。`getGroupCount` 不过白名单，而附录 G.6 实测脏值几乎全在 `status <> 'APPROVED'` 的行里——也就是说，第 5.1 节第 1 条要挡的 `{{7*7}} 1 人` 在这一条路径上不但可能、而且**只有**在这里必然出现：待审核账号填什么都会直接上看板。注册页是公开的，这是给任意用户开了一个在看板上刷字的面。dev 上恰好没人用 gender/mbti 写脏值，所以本地完全静默。
2. **未审核账号混进画像**。INTJ 那 9 个人里有 7 个是 PENDING——管理员看到的"全站 MBTI 分布"主要由还没通过审核的注册者决定。
3. **未填写被静默丢掉**。`IS NOT NULL` 让 24 个空值账号从图上消失，各桶之和与任何人数都对不上，图上没有任何地方说明"这张图只覆盖 12/36 的人"。

⚠️ 第 3 条留了一个坑给验证本身：旧实现的求和是 12，`siteReaders` 也是 12——**空值行数与未审核行数撞车**，纯属巧合。任何"HTTP 自己跟自己对齐"的断言在这一天都抓不到它，所以 L.4 的第 15 行必须带外部真值。

### L.3 落点

| 文件 | 改动 |
|---|---|
| `BookProfileCalculator.java` | `mergeRows` 从 `BookProfileService` 提成 public static（原来两处各有一份 null→「未填写」的折叠）；新增 `dashboardDistribution(dim, rows)` = `mergeRows` + `normalize` + 把 `unknown` / `dirty` 作为「未填写」「脏值」两行放回展示表 |
| `AdminDashboardService.java` | `getGroupCount(String column)` 删除；`userProfile` 的两个分布改走 `ProfileReaderSql.siteBucket(dim.getValueSql())` + `BookProfileCalculator.dashboardDistribution`。**`statusDistribution` 保留全账号口径**——它回答的是审核状态，不是画像 |
| `BookProfileService.java` | 两处 `mergeRows` 调用改指静态版，私有副本删除 |
| `BookProfileDimension.java` | 类注释里"禁止像 getGroupCount 那样拼列名"改写为"看板的分布也走这份表达式"（方法已不存在，旧措辞会指向不存在的代码） |

前端一行没改：`dashboard.tsx:252` 自己把 `MALE/FEMALE` 映射成中文，新增的两个键本来就是中文，直接落到图上。

引用点（R3）：`getGroupCount` 全仓只有 `getUserProfile()` 两个调用，`BookProfileDimension` 类注释一处文字引用——共 3 处，全部处理。

### L.4 单测与变异（`mvn -o test` 150 → **154 全绿**）

新增 4 例进 `BookProfileCalculatorTest`：脏值不成键、未填写单独成行且各桶之和等于取回人数、`" male "` 折叠进 `MALE`（证明归一化真在跑而不是原样透传）、键仍是前端映射用的 token 而不是中文标签。

| 变异 | 期望 | 实测 |
|---|---|---|
| `dashboardDistribution` 摘掉 `normalize`，只回原始桶 | 归一化 + 脏值两例红 | 2 failures：`dashboardDistributionNormalizesBeforeRendering`、`dashboardDistributionHidesDirtyValues` |
| 丢掉「未填写」「脏值」两行 | 求和断言红 | 3 failures（外加 `KeepsUnfilledVisible`） |

源码还原后与变异前逐字节相同，再跑仍绿。

**运行时**：自建 8300 / 8301，矩阵从 14 条变 **15 条，FAIL 0/15**（exit 0）。第 15 行 `L 看板画像与读者画像同口径` 断言三件事：`genderDistribution.MALE` == 只读 SQL 给的库内真值、各桶之和 == `siteReaders`、键全在白名单内。真值经 `--db-male` 传入；**不传真值时这一行记 SKIP 而不是 PASS**（脚本的 `row()` 因此多了第三种状态，汇总行会显式报"另有 N 行 SKIP"）。它会咬的证据：把真值换成旧实现算出来的那个错数（10）再跑 → 该行 FAIL、exit 1；把 flag 去掉再跑 → SKIP、exit 0。三种退出路径都验过。

### L.5 没做

- 不改 `statusDistribution`，也不给它加 k-匿名：它是账号审核状态计数，本来就该含 PENDING/BANNED。
- 生产库里这块的分布会变（PENDING 侧的脏值不再上看板，未填写会成最大的一行），上线后值得重跑一次第 15 行——脚本已经带了取真值的那条 SQL。
- `getGroupCount` 形状的"列名当参数"没有留兼容层，直接删。若将来要加维度，加进 `BookProfileDimension` 即可，看板和抽屉会同时拿到。

---

## 附录 M：`DEEP` 少了一层类型过滤——今天没错，是因为写入端恰好守规矩（2026-10-25）

### M.1 线索

附录 L 之后，剩下的问题只有一个：**还有谁在独立回答"这段会话算不算这本书的问答"**。grep `book_chat` 得到四份写法：

| 位置 | 写法 | 查的表 | 回答的问题 |
|---|---|---|---|
| `AdminDashboardService.CHAT_TYPE_FILTER` | `type IN ('book_chat','BOOK')` | `ai_sessions` / `ai_conversations` | 看板问答数、平均轮次、热门书 |
| `ProfileReaderSql` 的 `DEEP` 分支 | **不过滤** | `ai_sessions` | 画像「深度互动读者」 |
| `BehaviorProfileBackfillRunner` | `type IN ('book_chat','assistant')` | `ai_conversations` | 行为画像回填 |
| `RecommendService` | `type = 'BOOK'` | `ai_conversations` | 推荐信号 |

本轮只动前两行：它们查同一张表、回答同一个问题，却一份带 `BOOK` 历史值、一份一个都不带。后两行查的是另一张表、回答的是另一件事（推荐与回填），合并的风险大于收益，**记为已知分歧**，不在本轮顺手改。

### M.2 为什么"现在没错"不等于"可以不加"

dev 实测（只读）：带 `book_id` 的会话 **381 行全是 `book_chat`，11 个用户**；`type NOT IN ('book_chat','BOOK') AND book_id IS NOT NULL` = **0 行**。全仓唯一给 `AiSession` 写 `book_id` 的地方是 `BookChatService`。

也就是说 `DEEP` 今天等价于文档说的"和这本书聊过天的人"，但这条等价**建立在写入端的习惯上**，而 `AiSession.java:40` 的类型注释还写着 `CHAT / BOOK / LIBRARIAN` 三个早已不用的值——下一个读到那行注释、给助理会话补 `book_id` 的人不会知道画像在依赖它。届时不是报错：`DEEP` 人群换掉、cohort 分母虚高、该走保护态的书出分布，正好是第 4 节规则 1 的反向失效。

### M.3 落点

| 文件 | 改动 |
|---|---|
| `service/ai/BookChatScope.java`（新） | `TYPE` / `LEGACY_TYPE` + `sqlFilter(alias)`，一处定义「哪几种 type 算这本书的问答」 |
| `BookChatService.java` | 写侧的私有 `TYPE` 改为引用 `BookChatScope.TYPE`（6 处调用不动），读写两侧不会再各长出一份 |
| `AdminDashboardService.java` | `CHAT_TYPE_FILTER = BookChatScope.sqlFilter("")` |
| `ProfileReaderSql.java` | `DEEP` 第三分支 `WHERE s.type IN (...)`；谓词照旧逐分支下发（§7.2 的索引前提不变） |

`READ` / `TOUCH` 未动——它们不含 `ai_sessions`。

### M.4 验证

- 单测 +2 例（`ProfileReaderSqlTest` 15 → 17），`mvn -o test` **154 → 156 全绿**。一例断言 `DEEP` 源里类型谓词出现恰好一次、`TOUCH` 根本不碰 `ai_sessions`；一例用反射钉住看板的私有常量与 `BookChatScope.sqlFilter("")` 逐字相同——"两处共用一份定义"这件事必须能失败，否则它只是注释。
- 变异：`DEEP` 分支退回 `WHERE 1 = 1` → `deepChatBranchIsScopedToBookChat` 红；看板把常量另写成漏掉历史值的 `type IN ('book_chat')` → `dashboardSharesTheSameChatTypeDefinition` 红。两次各红一条，说明两条断言分别守着两侧。
- 运行时：自建 8300 / 8301 重跑矩阵 **FAIL 0/15、exit 0**，且输出与合并前那次运行**逐字节相同**（`diff` 为空）——三口径读者数没变，符合 M.2 的 0 行探针。
- 探针已进生产清单：`deploy/sql/profile-threshold-audit.sql` 第 4 步。生产若跑出非零的 `4b`，说明现网确实存在"带 book_id 的非问答会话"，那要拍板的是 `DEEP` 口径本身，不是要不要过滤。

### M.5 本轮的一次自我破坏（留着当反面样本）

恢复第二处变异时图省事用了 `git checkout -- AdminDashboardService.java`。那个文件带着本会话三处**未提交**改动，checkout 把整个文件打回 HEAD：`getGroupCount` 复活、`AdminRangeRules` 委托消失、`MAX_CUSTOM_RANGE_DAYS` 退回私有。幸而变异脚本在改之前先 `cp` 了一份当前状态，才从副本恢复；恢复后用 `git diff` 逐块核对"差异 == 预期的三处"、再跑测试回到 156 绿。

规则：**变异前先给源文件做快照（TEMP），还原只能从快照 `cp` 回来。** `git checkout` / `git stash` 的作用单位是整个文件，它不认识"我只想回退这一处改动"，对未提交过的文件用它等于赌上一轮的活儿。

---

## 附录 N：折叠桶上了饼图之后的真实视口回归（2026-10-25）

### N.1 这一轮为什么只动渲染

附录 L 之后，`/api/admin/dashboard` 的 `userProfile` 两块分布换了 key 集合：真实取值一律是白名单 token（`MALE` / `FEMALE` / `ENTJ`…），后面可能多出「未填写」「脏值」两格。后端已经自证过（矩阵 15/15、单测 156 例），但**没人证明过前端拿到这两格还画得出来**——饼图的配色是按切片下标取模的，多出来的桶会不会把真实取值的色位占掉，只有渲染出来才知道。

### N.2 dev 实测（只读，`days=0`）

| 字段 | 值 | 合计 |
|---|---|---|
| `genderDistribution` | `MALE 4 / FEMALE 2 / 未填写 6` | 12 = `siteReaders`（口径对上了） |
| `mbtiDistribution` | `INTJ 2 / ENTJ 1 / INFP 1 / ISTP 1 / 未填写 7` | 12 |
| `statusDistribution` | `APPROVED 12 / PENDING 23 / BANNED 1` | 36（账号口径，本就不该等于读者数） |

36 个账号里只有 12 个 APPROVED，饼图却按 12 算——这正是附录 L 要的效果：改造前 `getGroupCount` 会把 36 个账号一起端上去。

真实视口回归（同源静态构建 + 自建端口，`verify-page` 断言渲染产物节点）：

| 视口 | surface | 饼扇形 | 矩形条 | 饼标签 | 结论 |
|---|---|---|---|---|---|
| 1440×1000 | 8 | 6 | 5 | `男 33% / 女 17% / 未填写 50%` | PASS |
| 390×844 | 8 | 6 | 5 | 同上 | PASS |

MBTI 条形图的 Y 轴类目里 `未填写` 确实出现（和 `INTJ/ENTJ/INFP/ISTP` 并列），页面上唯一那条「暂无数据」是 `py-4` 版式（热门书 / 热门辩题 / 成本表三处之一），不属于画像两块——两块都有图。控制台与页面异常均为 0。

### N.3 抓到的配色缺陷

看板饼图配色是 `[chat, debate, roundTable][i % 3]`。真实取值只有两个（男/女）时没问题；一旦折叠桶出现，第三格起就回头占用第一格的绿色。dev 现在恰好只有「未填写」一格（3 切片，各占一个色位）所以看不出来，生产库里 `gender` / `mbti` 存在白名单外取值时会凑出 4 格，「脏值」会和「男」同色——饼图上分不清谁是谁。

修法（`frontend/src/pages/admin/dashboard.tsx`）：`FOLDED_BUCKETS = {'未填写','脏值'}`，`withSliceColors()` 让折叠桶固定用 `COLORS.info`（灰），色位只在真实取值之间轮转。这条不是美化：灰色本身就是"这格不能解释"的记号，和 §5.1 的折叠语义一致。

### N.4 验证与变异

dev 库没有脏性别取值，所以第 4 格只能在客户端造：Playwright 拦 `/api/admin/dashboard`，把响应改写成 `MALE 4 / FEMALE 2 / 未填写 6 / 脏值 2`，不动库、不动接口。断言写进 `probe`（TEMP，不入库）：4 个扇形、真实取值两个不同色、两格折叠都是灰、标签里同时出现「未填写」「脏值」。

| 构建 | 扇形配色 | 断言 |
|---|---|---|
| 修复后（dist-local） | `chat / debate / info / info` | **PASS**，1440 与 390 两档都过 |
| 变异（去掉折叠判断，退回纯下标取模） | `chat / debate / roundTable / chat` | **FAIL**，退出码 1——「脏值」撞回「男」的绿 |

变异证明这条断言会咬。还原走的是 `cp` 快照（M.5 那条规则的第一次实际应用），还原后 `diff` 与快照逐字节一致，再拿修复后的构建重跑一次两档视口回归：5 项检查全 PASS。

### N.5 三个环境坑（都会伪装成产品缺陷）

- **`application-dev.yml:107` 的 `jwt.secret` 是 `${JWT_SECRET:<默认值>}`**，不是裸值。照上一轮的做法把整行当 secret 签 token，14 条接口矩阵没跑就 401。要取花括号里冒号后面那段：`sed -n '107p' … | sed -E 's/.*\$\{JWT_SECRET:([^}]+)\}.*/\1/'`。这是 [[dev-env-facts]] 里那条坑的第三种形态——前两次是取错文件，这次是取错粒度。
- **PWA 的 service worker 让 `page.route` 静默失效**。KBook 前端带 workbox，SW 注册后自己发 `/api` 请求，Playwright 的页面级拦截拦不到它，表现是"我明明改了响应，页面还是老数据"，很容易被读成"前端没重算"。桩一律用 `newContext({ serviceWorkers: 'block' })`。顺带查清：`vite.config.ts:74-75` 对 `/api/*` 配的是 `NetworkOnly`，不缓存，所以此前各轮运行时读数都是实时值，没有被旧缓存污染的风险。
- **`.text-destructive` / `.text-danger` 在这个看板上不是报错态**：前者是侧栏「退出登录」按钮，后者是 KPI 的涨跌数值。拿它们做反向断言会虚假变红。报错分支（`dashboard.tsx:114-125`）会整页替换、图画不出来，所以「`.recharts-surface` 存在」就是它没进报错分支的证据。

### N.6 遗留

- 两个折叠桶同灰，靠标签和 Tooltip 区分；切片占比 < 5% 时饼图不出标签（`label` 回调的既有阈值），届时那一格只剩颜色和悬浮提示。真实分布里如果「脏值」长期是个位数，值得把它从饼图里挪出去（只留在数字里），但那要改的是 §5.1 的展示约定，等 H.5 两个决策一起拍。
- `profile-books.tsx`（反查页）与 `BookProfileSection.tsx` 用的是文字计数（"另有 N 未填写"），没有饼图配色问题，本轮未动。
- 前端没有测试框架，本轮的渲染断言只活在 TEMP 脚本里。要长期钉住这条配色规则，得先决定前端测试选型——不属于本设计的范围。

## 附录 O：维度取值 SQL 一直没有测试，而它藏过两个静默缺陷（2026-10-25）

### O.1 怎么发现这块是空的

P3 的两条路（吻合度口径、快照表）都卡在待决策上，所以这一轮改成回头补 §11 自己的账：把第 11 节逐条对到当前构建里的测试名。对完发现第 4 条「年龄分桶：`birthday=NULL`、未来日期、18 岁生日当天、60 岁边界」只有 Java 一侧——`BookProfileCalculatorTest.ageBuckets` 断的是 `AGE.toToken("-1")`，而 **`-1` 这个字符串是 SQL 给的**（`BookProfileDimension.ageSql()`）。同理，`boolSql()` 上面那段 `⚠️ is_married/has_children 是 BIT(1)` 的注释记录过一次真实踩坑，但**没有任何一条测试守着它**。15 个维度的 `valueSql()` 在整个测试目录里一次都没出现过。

这类缺口的形状和附录 K、M 是同一个：写错不报错，只是数字变小。`CAST(u.is_married AS CHAR)` 拿到的是 `0x01/0x00` 原始字节，Java 侧 `trim()` 剪成空串 → 已婚/未婚两维的样本整片掉进「未填写」，接口 `code:0`、看板照常出图；删掉 `WHEN u.birthday > CURDATE()` 之后，未来生日算出负岁数，直接落进「18 岁以下」，同样什么都不断。

### O.2 落点

新增 `backend/src/test/java/com/kbook/service/admin/ProfileDimensionSqlTest.java`（纯静态断言，不接 `EntityManager`，因此不需要数据库、不需要 Mockito）：

| 断言 | 钉住的东西 |
|---|---|
| 两个布尔列必须是 `CAST(CAST(col AS UNSIGNED) AS CHAR)`，且不得出现单 `CAST(col AS CHAR)` | BIT(1) 那次实测踩坑 |
| 非布尔列一律不得出现 `AS UNSIGNED` | 上面那条不是"到处加 CAST"的许可证 |
| 年龄 SQL 同时含 `> CURDATE() THEN '-1'` 与 `TIMESTAMPDIFF(YEAR, ...)`，并接 `toToken("-1") == 脏值` | 分支两端都在，中间断开也没用 |
| 12 个字符串列必须 `COALESCE(NULLIF(TRIM(u.col), ''), '未填写')` | §5.1「空串与 NULL 同桶」是在 `GROUP BY` 之前成立的 |
| 每条维度 SQL 只认别名 `u.`，不得出现 `users.` | `ProfileReaderSql` 的 `JOIN users u` 用的是别名；新维度写全名会让看板整片 500 |
| 维度 SQL 里不得出现 `status` | 附录 K 的成果：「谁算读者」只有一份，不许在维度表里再抄一遍 |

最后两条是给 L.5 那条承诺上保险的——「将来要加维度，加进 `BookProfileDimension` 即可」，加错了得有东西叫。

### O.3 实测与变异

`mvn -o test`：**156 → 204 全绿**（新增 48 例：3 个 15 值 `@EnumSource` + 3 个普通用例）。前端 `npm run build` 同一构建零错误。

变异用逐行定位（TEMP 脚本 `mutate-dim-sql.mjs`，不入库），三条都咬：

| 变异 | 位置 | 结果 |
|---|---|---|
| M1 布尔列退回单 `CAST` | `boolSql` 第 425 行 | exit 1，`Failures: 2`（嵌套 CAST 断言 + `onlyBoolColumnsAreBitTyped` 的反向断言各一条） |
| M2 删掉未来日期分支 | `ageSql` 第 431 行 | exit 1，`Failures: 1` |
| M3 字符串列去掉 `NULLIF(TRIM(...))` 折叠 | `raw` 第 413 行 | exit 1，`Failures: 12`（12 个字符串列全红，正是预期的覆盖面） |

还原走 M.5 那条规则：`fs.readFileSync` 快照 → 变异 → 写回 → 逐字节比对，脚本末行 `还原后与快照逐字节一致：true`。

### O.4 没做

- 形状断言只能证明 SQL **长这样**，不能证明它在 MySQL 上真的这么执行（附录 K.3 已经写过这条边界，这里不重复立项）。真要取年龄边界的执行真值，得往 dev 库写几条生日正好在 18 岁当天/未来的账号——那是写库，不在本轮授权范围内。
- `BookProfileDimension` 里那三条 `⚠️` 注释（BIT(1)、题干混现状与期望、`OTHER` 必须收录）现在只有第一条变成了断言。第二条是产品口径，拍不了；第三条已被 `whitelistIsEnforcedCentrally` 间接覆盖。
- E.3 第 2、3 步（吻合度口径、`book_profile_daily`）仍在等 H.5/§10 那两个决策，本轮没有替用户定。

## 附录 P：§6 的两端契约第一次有了能跑的核对（2026-10-25）

### P.1 为什么单开这一条

第 6 节把请求与响应字段写死成契约，AGENTS.md 的全栈规范也要求「后端改 DTO 时同步改前端 TS Interface」，但**没有任何东西能证明两端此刻还在说同一件事**——比之前只有两种途径：接口层跑矩阵（附录 J 只断业务语义，字段名少一个不会红）或人眼对读。而第 12 节记着的正是这么一起已发生过的事故：前端新、后端旧，`userProfile` 整片 `undefined`。附录 N 之后没人再看过字段名这一层，所以这轮做的不是新功能，是把契约本身变成可重跑的断言。

### P.2 落点

`deploy/verify/profile-dto-parity.mjs`（零依赖、纯静态，不启服务、不连库）：

- 用花括号深度把 `BookProfileVO` / 内部类 `Dimension` / `Bucket` / `ProfileBookVO` 的 `private` 字段归到所属类，再与 `adminDashboard.ts` 里 `export interface` 的成员名做**双向集合差**；
- 从 Controller 方法签名逐行取 `@RequestParam` 形参名，与 api 文件里 `params: DashboardQuery & { ... }` 展开后的键名做同样的差集；
- 自带失败前置：任何一侧解析出 0 个字段就直接 FAIL 并说明"脚本对不上这份文件了"。这条是附录 J.2 那个原则的又一次应用——**解析器坏掉的脚本会比没有脚本更危险**，因为它永远绿。

### P.3 实测

基线（当前工作树）：

| 对照 | 字段/参数数 | 结论 |
|---|---|---|
| `BookProfileVO` ↔ `BookProfile` | 14 ↔ 14 | OK |
| `BookProfileVO.Dimension` ↔ `BookProfileDimension` | 8 ↔ 8 | OK |
| `BookProfileVO.Bucket` ↔ `BookProfileBucket` | 7 ↔ 7 | OK |
| `ProfileBookVO` ↔ `ProfileBook` | 8 ↔ 8 | OK |
| `getBookProfile` 参数 ↔ `getBookProfile(params)` | 5 ↔ 5 | OK |
| `getProfileBooks` 参数 ↔ `getProfileBooks(params)` | 8 ↔ 8 | OK |

**没有漂移**，所以这一节的产出不是 bug，是把"两端一致"从人眼判断换成一条命令。§6.2 那行字段清单倒是漏了一个 `siteRatio`，已就地补正；顺带量到 `siteRatio` 在两个响应里都只有 TS 声明、全仓库没有任何组件读它——死字段，本轮不动（删它属于前端改动，得连带 `LiftBadge` 那列要不要显示全站占比一起定）。

变异（TEMP 脚本，三份文件先快照后还原）：

| 变异 | 结果 |
|---|---|
| 前端 `siteReaders` → `siteReadersX` | exit 1：后端独有 `["siteReaders"]` / 前端独有 `["siteReadersX"]` |
| 后端 `siteReaders` → `siteReaderz`（反向同一条断言） | exit 1：两个方向的角色对调 |
| Controller `String sortBy` → `String sort` | exit 1：参数漂移被抓——这类漂移在运行时**不报错**，Spring 直接按 `defaultValue = "LIFT"` 走，前端传的 `sortBy=READERS` 被静默丢掉 |

还原后三份文件与快照逐字节一致。

### P.4 没做

- 只比名字，不比类型与默认值。类型漂移（后端 `Double` 改 `BigDecimal`、TS 写 `number`）和默认值漂移（后端 `defaultValue = "30"` vs 前端 `getDashboard` 传 `{ days: 7 }`）都在这条命令的覆盖面之外；真要收口得让前端也进同一份生成物，那属于前端工程选型，第 11 节末尾已经划过线。
- 不覆盖 `@RequestBody` 方向的 DTO（`AiBudgetUpdateRequest` 等不属于本功能）。
- 没接进任何自动流程（CI / `package.bat`）。当前是"想起来就跑一下"的一条命令，和附录 J 的矩阵脚本同等待遇。

## 附录 Q：分子与分母来自两个不同寿命的缓存，`lift` 会短暂变成一个从未存在过的比值（2026-10-25 发现，**待拍板**）

### Q.1 代码事实

`BookProfileService` 里两份 Caffeine，寿命不同且互不知情：

| 缓存 | key | TTL | 内容 |
|---|---|---|---|
| `profileCache` | `bookId\|basis\|windowKey`（`:57`） | 5 分钟（`:59`） | 整份单书画像 VO，**含用基线算出来的 buckets / lift / coverage** |
| `siteCache` | 单键 `site`（`:62`） | 10 分钟（`:64`） | 全站基线：`siteReaders` + 15 个维度的 `DimCounts` |

三条取数路径与基线的关系不同：

- **抽屉**（`getBookProfile`）：命中缓存时，VO 里的分桶是**旧基线**算的；未命中时现算，`siteBaseline()` 可能返回**旧基线**（`:125`、`:137` 都取它，VO 里 `siteReaders` 也来自它）。两种情况都是"该书读者（此刻）× 全站基线（≤10 分钟前）"。
- **反查榜**（`getProfileBooks`）：**完全不碰 `profileCache`**，`bucketCountByBook` / `totalReadersByBook` 每次现查（`:195`、`:201`），只有分母 `siteBaseline()` 是缓存的（`:183`）。所以它的分子恒为最新、分母最多旧 10 分钟。
- **维度选择器**（`getSiteDimensions`，`:247`）同样直接复用缓存里的基线——人群选书页的 `queryable` 与 `coverage` 也带同样的 10 分钟龄期。
- **看板画像**：`AdminDashboardService` 不走这两份缓存，每次现算——于是**同一个数字在三个地方可能不一样**。

### Q.2 量级不是"略有偏差"

`lift = ratio / siteRatio`，而 `siteRatio = 全站同桶人数 / 全站该维度有效人数`。审核 10 个 PENDING 账号这个动作，在 dev 上会把分母从 12 人推到 22 人（+83%）：

| 窗口 | 榜单显示的 lift | 真实 lift | 后果 |
|---|---|---|---|
| 批准后 0–10 分钟内 | ≈ **1.83 ×** 真值 | 1.0 | 一本其实和全站持平的书显示成"全站 ×1.8 集中"，`sortBy=LIFT` 直接把它顶到榜首 |
| 10 分钟后 | 与真值一致 | — | 自愈 |

`significant` 用的是单书侧的 Wilson 区间（分子），不受影响，所以**页面上是"又显著又高"的错误结论**，不带任何可疑标记。§3.2 要的恰好是这个数。

### Q.3 为什么这一轮没有直接改代码

改法是清楚的（下面两个选项都写得出来），但**本轮无法验证**：
- 单测要 `EntityManager`，项目到现在没引 Mockito（附录 H.9 的原则是"抽成纯函数再测"，可这段逻辑本身就是缓存装配，抽不出纯函数）。**这条当时写得太绝对**：附录 R 用反射读 `@Value` 占位符和 Caffeine 的 policy，不碰 `EntityManager` 就把装配钉住了；但"分子分母配对"那个行为本身仍然测不了——它要求真数据变一次。
- 运行时证明需要基线真的变一次，即往 dev 库写数据（把 PENDING 改成 APPROVED），这超出本轮"只读验证"的授权。

按本项目自己定下的规矩——**只有跑出来才算**——不写不可验证的代码，改为把缺陷与复现步骤钉在文档里，等一句授权。

复现（**仍然需要一次性写库**；附录 R 把 TTL 做成配置之后，去掉的只是"还要改代码"这一条前置，不是"要写数据"这一条——没有数据变动就没有龄期差可观察）：
1. 自建实例起后端（不动 8181/15173），`KBOOK_PROFILE_BASELINE_CACHE_MINUTES=1`、`days=0`；
2. `GET /profile-books?dim=GENDER&value=FEMALE&basis=READ&limit=10&sortBy=LIFT` 记录一份；
3. 把 10 个 PENDING 账号改 APPROVED（其中一半 `gender=FEMALE`）；
4. 立刻重打第 2 步那条：`readers`/`count` 变了、`siteRatio` 没变 → 同一响应里分子分母不同龄，即为证据；
5. 等 1 分钟（原为 10 分钟）再打：数值回落到稳定态。

### Q.4 两个修法（选一个，都要改 §7.4）

| 方案 | 做法 | 代价 |
|---|---|---|
| **A：把基线并进同一份缓存值** | `profileCache` 的 key 带上基线代号（`siteBaseline()` 每次重算就 +1），旧代号的条目直接当 miss；反查榜改为同一代号的基线+分子配对 | 命中率下降（基线一刷新，500 条单书缓存集体作废）；改动集中在 `BookProfileService` |
| **B：基线不缓存** | 删掉 `siteCache`，每次请求现算 15 个 `GROUP BY`；分子分母天然同龄 | 每次画像/反查多 16 条对 `users` 的聚合。dev 无感；生产 `users` 上到 10⁴~10⁵ 时是 15 次全表扫（§7.3 没给 `users` 的画像列建过索引） |

倾向 A：它只改缓存装配，不引入新的取数压力，而 B 的成本落在最不可测的生产库规模上。两者都要顺手处理"三个地方同一个数字不一样"这条——看板侧不缓存是它自己的选择，要不要统一另说。

### Q.5 顺带确认过没问题的两件事

- **`limit` 不越界**：`Math.min(Math.max(limit, 1), MAX_QUERY_LIMIT)`（`:180`），`MAX_QUERY_LIMIT = 50`（`:40`）与 §6.2 的"1..50"一致，传 100000 不会Dump 全站。
- **已删除的书不会上榜**：候选来自 `bookRepository.findAllById`，取不到 `Book` 的行 `continue`（`:212`）；单书侧则是 `findById` 抛「图书不存在」（`:101`）。两侧"什么算一本存在的书"其实是同一个谓词（行还在 `books` 里），不是附录 K/L/M 那类口径副本。`books` 没有软删列（只有 `BookTrash` 这张表存在，本轮没追它的语义）。


---

## 附录 R：缓存寿命从注释变成配置项，第一次有了能跑的装配断言（2026-10-25）

### R.1 代码事实

`BookProfileService` 的两份 Caffeine 缓存此前是 `private final Cache<…> = Caffeine.newBuilder()…build()` 的字段初始化器，5 分钟 / 10 分钟只存在于代码里，文档 §7.4 与它们没有任何可执行联系。现在：

| 项 | 值 |
|---|---|
| `kbook.admin.book-profile.profile-cache-minutes` | env `KBOOK_PROFILE_CACHE_MINUTES`，默认 **5**（不变） |
| `kbook.admin.book-profile.baseline-cache-minutes` | env `KBOOK_PROFILE_BASELINE_CACHE_MINUTES`，默认 **10**（不变） |

字段改为非 `final`，建缓存挪进 `@PostConstruct void buildCaches()`。原因不是风格：`@Value` 的注入发生在字段初始化**之后**，就地建会读到 `0` 分钟，于是每条请求都重新查库——表现是"接口变慢了"，不会有任何报错。默认值没动，所以**运行行为与这一轮之前逐字相同**，附录 Q 的缺陷也原样保留。

### R.2 为什么是 `@PostConstruct` 而不是构造参数

按 R3 先列引用点：两份缓存的读写全在服务内部（`bookProfile` 的命中/回写 `:107`、`:133`、`:149`，`siteBaseline()` 的 `:355-369`），外部没有第二处。这个类用的是 `@RequiredArgsConstructor`，构造参数是 `EntityManager` + repository，把 TTL 塞进构造器等于改签名，牵动 `BookProfileCacheConfigTest` 之外的手建实例；而阈值四件套（`min-cohort-size` 等）已经是 `@Value` 字段，跟着它走是一致的做法。

### R.3 `BookProfileCacheConfigTest`：7 条断言 + 变异

`backend/src/test/java/com/kbook/service/admin/BookProfileCacheConfigTest.java`，全部零依赖（反射读注解 + 读 yml 文本 + Caffeine `policy()`），不需要 `EntityManager`、不需要起服务：

| 断言 | 钉住的东西 |
|---|---|
| 两个 `@Value` 占位符原文 | 属性名与兜底数字就是 §7.4 写的那两个 |
| 给 3 / 7 建出的缓存是 `PT3M` / `PT7M` | 寿命真来自配置，不是写死的数 |
| `(5,5)` 时两份缓存是不同对象 | 附录 Q 的结构性前提——**这条是"缺陷还在"的哨兵**，Q.4 方案 A 落地时应当改掉它 |
| 容量 `500` / `4` | §7.4 与 H.7 修正后的数字 |
| `buildCaches()` 前字段为 null、后非 null | 0 分钟那个坑的真实形状 |
| `buildCaches()` 上必须有 `@PostConstruct` | 上一条是自己 new 出来手调的，证明不了**容器**会调它；注解被删时单测全绿而线上第一个请求 NPE |
| yml 的生效默认值 == 注解兜底值；`@Value` 路径末段 == yml 键；env 名 == 文档承诺的名字 | 之前没有任何测试读过 `application.yml`（全仓 grep 为空），yml 与注解可以各改各的 |

变异（脚本 `mutate-cache-config.mjs`，跑在 temp，源码快照后逐字节还原并校验一致）：

| 变异 | 结果 |
|---|---|
| 基线（未变异） | GREEN，7 例全过 |
| 单书缓存借用 `baselineCacheMinutes` | RED |
| 把 5 分钟写死、忽略配置 | RED |
| 只把 yml 的默认值改成 60（注解仍 5） | RED ← 这条正是"生效值与测试钉的值分家" |
| 只把 yml 的 env 名改掉（少 `_CACHE`） | RED ← 部署清单里的名字失效会被抓住 |
| 删掉 `@PostConstruct` | RED |
| 单书缓存容量 500 → 50 | RED |

全量：`mvn -o test` → **Tests run: 211, Failures: 0, Errors: 0**，BUILD SUCCESS（附录 Q 之前是 204，本轮 +7）。

### R.4 写错的第一版：拿"逐字相同"当不变量

`yml` 那条断言的第一版写成 `yamlValue(key)).isEqualTo(placeholder(field))`，即要求 yml 的值和注解的字符串一模一样。基线直接红——因为两边**本来就该不同**：注解里是属性路径 `kbook.admin.book-profile.profile-cache-minutes`，yml 里是 env 名 `KBOOK_PROFILE_CACHE_MINUTES`，只有冒号后那个数字是共同不变量。

值得记的是顺序：这一版是**变异脚本先发现的**，不是我想出来的。第一版跑出来的表里基线那行就是 RED，六条变异的"变红用例数"和基线一样（1），等于全部不可解读。项目里那条"解析器坏掉的脚本比没有脚本更危险"在这里换了个形态——**基线红的变异表比没有表更危险**，它看起来是一张全绿的通过记录。脚本里那条 `!! 基线就是红的，后面全部不算数` 的告警当时被 `tail -25` 截掉了；第二轮改成先落盘再 grep，才既看到表也看到告警。

### R.5 运行时证据（自建实例，只读）

配置化的装配在真实 Spring 容器里成立才算数，所以起了一份自己的实例：

- 端口归属先查：`netstat -ano` 显示 8181（PID 5736）与 15173（PID 35992）是**别人的副本**，8402 被一个非我启动的进程占着，首次启动因此报 `Port 8402 was already in use`。没有去动那三个。
- 换 8411 启动成功，`GET /api/admin/dashboard/book-profile?bookId=24715&days=0` → `HTTP 200`，`{"code":0,…,"basis":"READ","readers":0,"minCohortSize":10,"sampleProtected":true,…}`。这条请求走的正是 `bookProfile` 缓存那条路径：`buildCaches()` 抛不出异常、也没把 TTL 读成 0。
- 鉴权用本地密钥自签的 ADMIN token（密钥由命令内 `sed` 提取、未回显），只打只读接口，未写库；token 与日志都留在 `%TEMP%\kbook-verify`，未进仓库。
- 验证完按 PID 归属确认是自己那条 `--server.port=8411` 后关掉，端口已释放。

### R.6 这一轮打开了哪扇门、没打开哪扇

打开了：**不用改代码就能把 TTL 调到 1 分钟**，所以 Q.3 的复现步骤里"改代码"这条前置没了；装配层（注解在不在、yml 与注解是否分家）第一次有能跑的断言。

没打开：**Q 本身**。分子分母的配对逻辑一行没动，默认寿命还是 5 / 10，`profile-books` 依然不进 `profileCache`。要在只读库里"看见"龄期差是不可能的——没有数据变动就没有差可观察，所以下一步仍然要么等一句写库授权，要么按 Q.4 拍 A/B。

### R.7 配置项落地后必须同步的两处，以及 §4.1 的重新逐项清点

新增 env 就是新增部署面，所以顺手补了 `deploy/docs/deploy-guide.md` 的 env 变量表一行（第 2 节「Docker Compose → 核心配置说明」末尾，与阈值四条同一张表）：两个 TTL **只在启动时读取、改了要重启**（与阈值四条同一形状，见 H.7），并且写明"把基线那条调小会让 `users` 更频繁地跑 15 个 `GROUP BY`（§7.3 没给画像列建过索引），生产不建议长期低于默认"——否则这两个键看起来像免费旋钮。

同时把 §4.1 那四条结论按当前代码重新清点了一遍，避免以后重复审：

| §4.1 | 现状 |
|---|---|
| 1 上线前用生产库跑分布 SQL 再定阈值 | 脚本侧已就绪（`deploy/sql/profile-threshold-audit.sql` 三口径各一张分布表）；**跑在哪个库仍待授权**，不是代码缺口 |
| 2 阈值做成配置项，允许降到 5/3 或按维度差异化 | 四条阈值 + 两条 TTL 均为 env；"按维度差异化"这一半**没做**，它需要 §4.1 的第 1 步先出数，否则只是把一组没校准的数拆成十五组 |
| 3 保护态必须有可用降级展示，不是空白 | 前端已在：`BookProfileDrawer.tsx:96-107` 给「样本过小，出于隐私不提供画像分布」+ 当前读者数 + 阈值 + 一条通往人群选书的链接。**文档原文还要求"仅显示总人数与评分"**——抽屉里没有评分，评分在书页详情那一层，管理员看得到；判为满足，不再改动 |
| 4 若校准后多数书落保护分支，则改做人群选书 | P2 `profile-books` 已上线，等于这条的预防措施已落地；要不要反过来把人群选书当主入口，是产品决策（§4.1 原文即标了"需要产品决策"） |

## 附录 S：§8 前端设计第一次逐条清点，并补上抽屉里的折叠（2026-10-25，**运行时未验证**）

### S.1 清点方法与结果

前面各轮把 §2/§4/§5/§6/§7/§11 都翻过一遍，§8 只被"顺路"验证过（B.2 记了图表形态的偏差、G/N 是真实视口回归），没有整节清点过。这次按 §8.1–§8.3 的每条要点回代码对：

| §8 要点 | 现状 |
|---|---|
| 8.1 `pages/admin/book-profiles.tsx` | 实际文件是 `pages/admin/profile-books.tsx`，**B.2 已记**，路由与入口一致 |
| 8.2 保护态整块替换、阈值文案来自接口 | 在：`BookProfileDrawer.tsx:96-107` 用 `data.minCohortSize`，前端没有硬编码 10（文档原文写 `VO.threshold`，字段实名 `minCohortSize`，P 的静态核对保证两端一致） |
| 8.2 每桶同时给 `count` 与 `ratio`，`lift` 徽章 `>=1.3` 蓝 / `<=0.7` 灰红 / **不显著不上色** | 在：`BookProfileSection.tsx:22-32`，`!bucket.significant` 走灰色文字分支，不上色 |
| 8.2 格式化函数一律先 `if (!Number.isFinite(n)) return '—'`（文档标了"从生产事故来的硬要求"） | 在：`:8`、`:13`、`:18` 三处入口全部先查有限性，`lift==null` 也单独兜底 |
| 8.2 移动端抽屉 `<md` 全屏 | 在：`BookProfileDrawer.tsx:47` `w-full md:max-w-[480px]` |
| 8.2 **图表高度固定 240px** | **作废，且作废的原因此前没写**：抽屉与反查页两处 `recharts` 引用数为 0（grep 计数），全是 DOM 比例条，`ResponsiveContainer` 不存在，0 高塌陷这条风险从未成立。B.2 第 4 条只说了"环形作废"，没推到"240px 也一并作废" |
| 8.2 **复用 `CollapsibleSection`，折叠记忆与主看板一致** | **此前没做**：`CollapsibleSection` 是 `pages/admin/dashboard.tsx` 里的私有函数（grep 全仓只有这一个文件命中），抽屉里每个维度都不能折叠。本轮改掉，见 S.2 |
| 8.3 `useQuery` + `placeholderData: keepPreviousData` + 打开才请求 | 在：`BookProfileDrawer.tsx:2,26-30`。`enabled` 用的是 `!!book` 而不是文档写的 `drawerOpen`，与"打开才请求"等价（`book` 由入口按钮置上、关闭时清空） |

### S.2 这一轮的代码改动

折叠的**记忆方式**要一致，就得只有一份实现，所以把组件从看板页面里提出来：

- 新增 `frontend/src/components/admin/CollapsibleSection.tsx`：导出 `CollapsibleSection`（原样搬过来，含 `aria-expanded`）与 `useCollapsed(storageKey, defaultOpen)`——`localStorage` 存 `'1'/'0'`、没存过视为展开、读失败退默认值。
- `pages/admin/dashboard.tsx`：删掉私有定义、改为 import，4 处 `<CollapsibleSection>` 用法与 `storageKey` 一字未动，`ChevronDown` 随之从该文件的图标 import 里去掉（`noUnusedLocals` 会因残留 import 直接构建失败）。
- `components/admin/BookProfileSection.tsx`：维度标题行由 `<div>` 改成 `<button aria-expanded>`，正文 `open` 才渲染；标题右侧原有的「填写率 / 多值」信息保留在按钮里。**没有**把整块套进 `CollapsibleSection`：那个组件自带 `bg-card shadow-xs` 外壳和 `h2`，套进去等于在抽屉里再画一张卡片、还会把 `填写率` 挤到 `action` 槽里。所以复用点是**记忆与切换语义**（同一个 `useCollapsed`），不是外壳。
- `BookProfileDrawer.tsx`：`storageKey = admin.profile.<bookId>.<dimensionKey>`，并把 React `key` 从 `dimension.key` 改成 `<bookId>:<dimensionKey>`。后者是必须的——换一本书时组件不重挂载，`useState` 的初始化器不会重跑，新 `storageKey` 不会被读，会把上一本书的折叠状态带到这一本书上。

按 R3 先列了引用点：`BookProfileSection` 全仓只有 `BookProfileDrawer` 一个调用方（所以 `storageKey` 做成必填不会牵动别处）；`CollapsibleSection` 只有 dashboard 一个使用方。

### S.3 验证到哪一步（当轮的边界，已在 S.5 闭环）

- **已通过**：`npm run build`（`tsc -b && vite build`）exit 0，产物含 PWA；`mvn -o test` 211 例仍全绿（本轮没动 Java）。
- **没有通过运行时**，且这不是遗漏而是被两件具体事实挡住：
  1. `vite.config.ts:95-97` 的 `/api` 代理目标是写死的 `http://localhost:8181`，而 8181 属于**另一份代码副本**（端口陷阱，见 [[reference-prod-deployment]]）。用 15173 或任何自建 vite 去验证，请求都会打到不是我改的 backend 上，读数与我的改动无关。要用自建 8411 验证，要么临时改这个代理目标（属于共享构建配置，不在本轮单方面动），要么把 8181 腾出来（不是我的进程，不能动）。
  2. dev 库里**没有任何一本书能出分布图**：读者数最多 1–4，`min-cohort-size=10` 下 `sampleProtected` 恒为真，抽屉永远显示保护态。要看维度折叠必须临时用 `KBOOK_PROFILE_MIN_COHORT_SIZE=2 KBOOK_PROFILE_MIN_BUCKET_SIZE=1` 起实例——这条路是通的（H.9/I 就是这么对打的），只是要和本轮这两个环境约束排在一起做。
- 下一步的最短路径：自建 backend（8411 + 降阈值 env）+ 一次性把 vite 代理指向它（改完即还原、不提交）+ Playwright 点击式断言。断言形状照 J 的规矩写死：抽屉内 `button[aria-expanded]` 数量 == 维度数；点一次后属性翻 `false` 且该维度条数变为 0；`localStorage` 里出现 `admin.profile.<bookId>.<key>` = `'0'`；刷新后仍折叠；换一本书不继承上一本的折叠状态（正是 S.2 那条 `key` 改动要证的）。**在这几条跑绿之前，本节的折叠都算"编译过、没跑过"。**

### S.4 第一次运行时验证尝试停在哪（三条可复用的事实）

按 S.3 的最短路径试过一轮，**结论是仍然未验证**，但把三个约束摸清了，下一轮不用重新探：

1. **同源代理不用改仓库任何文件**：`vite.config.ts:95-97` 里本来就有一条 `process.env.TAURI ? 'http://127.0.0.1:8282' : 'http://localhost:8181'`。所以 `TAURI=1 npx vite --port 15191 --strictPort` + 自建后端 `--server.port=8282` 就能让自建实例走同源 `/api`，绕开 8181/15173 那两份别人的代码。本轮两条都起来了（`GET /` 200，`/api/...` 拿到 401 而不是 502，证明代理链是通的）。
2. **401 的原因在取密钥那一步**，不在代理——但**我上一轮写在这里的诊断是错的**。当时写"这个 yml 里有多处 `secret:`"是想当然：用 awk 按缩进数过，`secret:` 只有一处（`application.yml:149` 的 `jwt.secret`，`JwtUtil.java:25` 读的就是它）。真正的原因是取值命令 `sed 's/.*:\s*//'` 贪婪匹配到了**最后一个冒号**，于是 `${JWT_SECRET:默认值}` 结尾那个 `}` 被一起当成密钥，签出的 token 必然对不上。下一轮的取法：`sed -n '149p' … | sed 's/.*://; s/}.*$//'`，并且先 `curl /api/user/me` 确认 200 再谈页面。（两处都没打印过密钥本身。）
3. **入口锚点不是 `查看读者画像`**。真实入口在 `pages/admin/dashboard.tsx:545`：可见文案只有「画像」两个字，语义标签是 `aria-label={查看《书名》读者画像}`。所以下一轮的定位用 `[aria-label^="查看《"]`，`getByText('查看读者画像')` 找不到任何东西——那是 §8.1 描述入口的说法，不是页面上的文字。抽屉本体挂在同一个文件的 `:861`。

进程收尾：本轮起的 8282 后端与 15191 vite 已按 PID 归属确认后关闭（`CommandLine` 分别是我那条 spring-boot argfile 与 `vite --port 15191 --strictPort`），8181/15173/8402 三个别人的端口全程未动；token 与日志只在 `%TEMP%\kbook-verify`，未进仓库，验证脚本 `verify-fold.cjs` 同样留在 temp。
### S.5 运行时应验：11 条断言全过，并证伪了自己的一条断言（2026-10-25 晚）

S.3/S.4 登记的欠账已经跑完。环境（全部是自建实例，别人的 8181/15173 全程未动）：

- 后端：`--server.port=8282` + `KBOOK_PROFILE_MIN_COHORT_SIZE=1 KBOOK_PROFILE_MIN_BUCKET_SIZE=1`（dev 里没有任何一本书在 READ 口径下有读者，cohort=2 也出不来分布，只能压到 1）；
- 前端：`TAURI=1 npx vite --port 15191 --strictPort`，走 `vite.config.ts:95-97` 既有的 TAURI 分支把 `/api` 指到 8282——**仓库文件零改动**；
- 鉴权：`application.yml:42` 的 `active: dev` 说明生效的是 `application-dev.yml:107` 的 `jwt.secret`（主 yml 的 `:149` 被 dev 覆盖，S.4 第 2 条记的就是这个坑）；自签 ADMIN token 后先验 `GET /api/user/me` → `code=0, role=ADMIN, id=1` 才继续；
- 数据事实：10 本热门书在 `READ` 口径下读者数**全为 0**（cohort=1 仍保护态），`TOUCH` 每本 1 人，`DEEP` 1~10 人。所以抽屉打开后必须点「互动」才有维度——这本身也是 dev 库"样本薄"的又一次实证。

11 条断言（脚本 `verify-fold2.cjs`，Playwright + 真实视口 1440x1000 + `serviceWorkers: 'block'`）：

| # | 断言 | 结果 |
|---|---|---|
| 0 | 冷加载 `/admin/dashboard` 不被守卫踢走 | PASS |
| 1 | 书籍行存在 `aria-label^="查看《"` 的画像入口 | PASS |
| 2 | 点击后 `[role=dialog][aria-modal=true]` 可见 | PASS |
| 3 | 抽屉内 `button[aria-expanded]` 数量 > 0 | PASS（15 个维度） |
| 4 | 首维度初始 `aria-expanded=true` | PASS |
| 5 | 点击后翻成 `false` | PASS |
| 6 | 折叠后该维度的比例条不再渲染 | PASS（4 条 → 0 条） |
| 7 | `localStorage` 写入 `admin.profile.<bookId>.<key> = "0"` | PASS（实测 `admin.profile.24715.GENDER`） |
| 8 | 刷新后重新打开仍折叠 | PASS |
| 9 | 换一本书不继承上一本的折叠态 | PASS——**但这是空断言，见下** |
| 10 | 控制台零错误 | PASS |

变异（源码快照、跑完逐字节还原并校验一致）：

- **写值恒为 `'1'`**（`CollapsibleSection.tsx` 的 `setItem`）→ 第 7、8 条同时变红，还原后基线回到全绿。这两条是真咬住的。
- **去掉 `key` 里的 `bookId`**（只留 `dimension.key`，即 S.2 那条修正的反向）→ **仍然全绿**。原因不是断言写错，而是流程里没有这个状态：脚本用 Escape 关抽屉，而抽屉 `if (!book) return null` 会整棵卸载，换书时组件本来就重挂载、`useState` 重新初始化。也就是说 **S.2 那条 `key={`${book?.id}:${dimension.key}`}` 是防御性写法，不是"已被验证的修复"**——在当前 UI 流程里（换书必须先关抽屉）它观察不到差别。保留它，但按"未验证的保险"记，不拿 PASS 当证据。

收尾：8282 与 15191 已按 PID 归属（`spring-boot` argfile 与 `vite --port 15191`）确认后关闭；8181/15173 的 4 条监听仍在（别人的副本）。token、`dash.json`、脚本与日志都在 `%TEMP%\kbook-verify`，未进仓库；仓库里本轮只新增/修改了 S.2 那三个前端文件，仍未提交。

## 附录 T：§7.3 的索引表与实体声明不一致；§9 的首屏承诺第一次有网络证据（2026-10-25）

### T.1 四条索引：部署清单是对的，设计文档的表格是错的

§7.3 那张「缺口」表是从查询意图推出来的，编码时按 SQL 里真正出现的列重建，于是名字和列都有偏差。逐条对（实体文件行号为证）：

| §7.3 写的要求 | 实体实际声明 | 判定 |
|---|---|---|
| `idx_progress_book(book_id, updated_at)` | `idx_progress_book_updated(book_id,updated_at)`（`ReadingProgress.java:29`） | 列同、名不同 |
| `idx_history_book_action(book_id, action)` | `idx_history_book_created(book_id,created_at)`（`UserReadHistory.java:27`） | **列不同**：SQL 根本不按 `action` 过滤 |
| `idx_shelf_book(book_id, added_at)` | `idx_shelf_book_added(book_id,added_at)`（`Bookshelf.java:22`） | 列同、名不同 |
| `idx_note_book(book_id)` | `idx_note_book_created(book_id,created_at)`（`Note.java:33`） | 实际是超集 |

「列不同」那条不是笔误，而是实现的判断更对，证据在 `ProfileReaderSql.java`：TOUCH 口径的两条分支分别取 `b.added_at`、`h.created_at` 作时间列（`:34-37`），**整份 SQL 里没有一处对 `user_read_history.action` 的过滤或排序**。所以 `(book_id, action)` 对画像查询毫无增益，`(book_id, created_at)` 才能同时服务「按书取人群」和窗口条件。沿用 H.7 的判法：实现更合理 → **改文档，不反过来改实体**（改名还会在存量库上留下两条同义索引）。

部署清单 `deploy/docs/deploy-guide.md` 的「本次发布含索引变更」小节写的本来就是实体真名（`idx_progress_book_updated` 等四条），所以**运维照清单核对不会错**，错的只有设计文档这一节。本轮在 §7.3 表下补了一条修正注指向这里。

**没验的那一半**：索引是否已经在库上建成。~~本机没有 `mysql` 客户端，后端也没暴露只读 SQL 的接口~~ **2026-10-25 已补验（附录 AC）**：四条索引都在、`EXPLAIN` 的按书查两条主干都是 `ref`；`user_read_history` 用的确实是 `created_at`。dev 数据量小，`bookshelf` 在聚合查询里仍是全扫（优化器选择），生产需按同两条 EXPLAIN 复跑。验证方式备查：`SHOW INDEX FROM reading_progress WHERE Key_name='idx_progress_book_updated'`（另三张表同理），或查 `information_schema.statistics`；再对 §7.2 的两条 SQL 跑 `EXPLAIN`，`type` 应为 `range`/`ref`、`key` 为上表名字。

### T.2 §9 的「首屏零回归」从推测变成断言

§9 写「抽屉懒加载，不进主看板 → 主看板零回归风险」，此前没人证过。现在用 Playwright 记录网络请求（`verify-firstpaint.cjs`，1440x1000 真实视口，`serviceWorkers: 'block'`）：

| 断言 | 结果 |
|---|---|
| 主看板加载期间 `book-profile` 请求数 | **0** |
| 主看板自身确实在发接口（防「空跑也是 0」） | PASS（`admin/dashboard` 等） |
| 点开画像入口后 `book-profile` 请求数 | **恰好 1** |
| 该请求耗时 | 11 ms / 24 ms（两次跑）——**dev 库、样本极小，不能当生产 P95 读** |

§9 其余三条（`IN (10⁴)` 的分组开销、全表基线量级、P95 > 800ms 才优化）仍然只是估算：在 dev 的 12 个审核用户上测不出意义，属于「要生产量级才有结论」的一类，和 A.3 一起欠生产库。

### T.3 又一次断言自纠

首屏脚本第一版的 (b) 判成 FAIL：它把 vite 的源码模块 URL（`/src/api/user.ts`）也算成了接口请求，且 `split('/api/')` 之后比对时多写了一个前导斜杠。改成只统计 `xhr/fetch`、再比 `admin/dashboard` 才 PASS。**结论没变，但过程记在这里**——「0 次请求」这类否定式断言，必须先证明「确实在发请求」才有意义，否则空跑与懒加载无法区分。

## 附录 U：文档里的路径引用有了可重跑的核对（2026-10-25）

### U.1 为什么做这个

本轮之前修过三次"引用对不上"：R.4 的行号是凭印象写的、S.4 把 `application.yml` 的 secret 行当成了 dev 生效项、T.1 发现 §7.3 的四个索引名有三个和实体不符。三次都是**人工看文档**才发现的，而这个文档已经 1800 行、改过十几版，靠人眼复查必然会漏下一次。

所以把最便宜、最不会撒谎的那一层抽出来：**文档反引号里的仓库路径是否真的存在**。新增 `deploy/verify/doc-paths.mjs`，零依赖、只读、不启动任何服务。

### U.2 它认什么、不认什么

- 认两种写法：带目录前缀的（`backend/src/main/java/...`）直接查；树状图里的相对路径（`service/admin/BookProfileService.java` 写在 `backend/src/main/java/com/kbook/` 标题下）按"全仓存在某个以它结尾的文件"判——否则 7.1 那一节会得到一片假红。
- 不认：URL、带 `*`/`{`/`…` 的占位、带空格的句子、以及 `/src/api/user.ts` 这类浏览器模块 URL（T.3 里拿它举例，不是仓库路径）。
- 行号（`:149`）会被剥掉后再判存在性；**行号本身对不对不在本脚本范围内**——那需要 AST 或 diff 感知，按附录 P 的教训宁可窄而真，不假装覆盖。

### U.3 基线、变异与自毁闸门

| 跑法 | 结果 |
|---|---|
| 当前文档 | 42 个路径，2 个"已在文档里记过的偏差"被显式放行 → **PASS** |
| M1：把文档里一个真实路径改成 `ProfileReaderSqlV9.java` | **FAIL**，点名那一条（说明"全绿"不是因为它什么都看不见） |
| M2：喂一份没有任何路径的文档 | **exit 2「只解析出 0 个路径——解析器坏了」**，不是静默 PASS |

两条放行都有据可查，不是为了让检查变绿：`pages/admin/book-profiles.tsx` 是 B.2 记过的改名（实际 `profile-books.tsx`）；`frontend/vite.verify.config.ts` 是 C.4 / G.5 自述的临时文件（"验证后删除"）。脚本把放行原因打印出来，所以它们不会悄悄累积成新的假绿。

### U.4 这个检查抓不到什么

- **存在但语义变了**：T.1 那类"文件在、里面的索引名和文档说的不一样"，路径检查看不见，只能靠实体/SQL 的静态断言（O/P）或变异来钉。
- **行号漂移**：文档里大量 `:107`、`:355-369` 这类引用，本轮仍然靠 grep 手工核对。要做自动化得上 TS/Java 的轻量 AST 抽取，成本远高于收益，暂不做。
- **描述与行为不一致**：例如 S.5 里"11 条全绿但第 9 条是空断言"——这类只有变异测试能暴露。

用法：`node deploy/verify/doc-paths.mjs`（默认查本设计文档，`--doc <path>` 可换）。提交前顺手跑一次，比人眼便宜。

### U.5 插入本附录时，它先抓到了我自己

写 U.2 时用 `backend/src/main/java/...` 举例（三连点占位），脚本立刻把它当成失效引用报红。于是给解析器补了 `...` 排除，PASS。这件事本身说明了它和人眼的差别：人眼会把"..."读成省略号，正则不会——所以占位写法要么别放进反引号路径里，要么在解析器里显式排除。基线仍然维持 42 个路径、2 条已登记偏差。

## 附录 V：一次画像请求到底发几条 SQL（2026-10-25 实测，dev）

§7.2 标题写「两条 SQL 打天下」、§7.4 的方案 B 估算「每次多 16 条对 `users` 的聚合」。这两句此前都没数过。开 `org.hibernate.SQL` 的 DEBUG 日志，数一次请求打了几条。

做法（可复现）：后端以 `LOGGING_LEVEL_ORG_HIBERNATE_SQL=DEBUG` 起在 8282（自建，别人的 8181/15173 未动），连续打三次接口并记录日志行号，逐段统计含 `hibernate.SQL` 的行。

| 请求 | 语句数 | 构成 |
|---|---|---|
| 单书画像，缓存未命中（`basis=DEEP`，cohort=1） | **34** | `users` 32 + `books` 1 + `notes` 1 + `book_reviews` 1 + `ai_sessions` 1 |
| 单书画像，紧接着同一参数再打（缓存命中） | **1** | 只有 `books` 那一行——**缓存键里不含书名**，书的行每次都要取 |
| 画像反查（`dim=GENDER&value=FEMALE&sortBy=LIFT`） | **4** | `bucketUserIds` 1 + **同一段 UNION 聚合跑了两遍**（一次 `COUNT` 出桶人数、一次出该书总读者）+ `books` 1 |

关键读数：

- **32 条 `users` 查询 = 2 × 16**：该书侧 15 个桶各一条 + 1 条 `siteReaders` 计数，全站基线再各来一遍。这正好对上 Q.4 方案 B 的「每次多 16 条聚合」——**那个估算是有数的**，并且说明 H.7 说的「基线一次算出 15 个维度」指的是一个 Service 里的循环，落到 SQL 是 15 条，不是一条。§7.2 的「两条 SQL」说的是取数结构（读者池 + 聚合），不是语句总数；两处表述放在一起容易被读成"一次请求两条 SQL"，这里把真实数字钉住。
- **缓存命中只省掉 33 条**，`books` 那一行仍每次查（`findById` 在缓存之外）。量大时这一条走主键、成本可忽略，但「命中即零查询」的说法是错的。
- **反查榜把同一段 UNION 跑了两遍**。这不是缺陷（两次 `COUNT` 的列不同：一次按书分组数人、一次数该书总读者），但意味着反查榜的实际扫描量是理论值的两倍。要不要合成一条 `COUNT(DISTINCT)` + 窗口函数，属于优化项，本轮不动——它要么改 SQL 形状（连带的形状断言都要改，见附录 K），要么加一层计数缓存（又回到 Q 的龄期问题）。

两次计数偏差的自纠（都记在案）：

1. 第一次按 `grep "SQL - "` 计数得到 **0**，因为控制台日志带 ANSI 颜色码，把 `SQL - ` 切开了。改成按 `hibernate.SQL` 计数、并 `sed 's/\x1b\[[0-9;]*m//g'` 去色后才数准。
2. 上一轮我在同一份日志里用 `wc -l` 的差值当"SQL 条数"，那是**日志行数**不是语句数（一条 SQL 可能折多行），两次口径不能混用。

限制：这是语句条数，不是耗时；dev 只有 12 个 APPROVED 用户，行数规模毫无代表性；基线与反查在生产上的真实代价仍要 `EXPLAIN`（任务 #41）配合页面的 P95 一起看，§9 的「P95 > 800ms 再优化」依然成立。

## 附录 W：缓存配置化之后，接口矩阵整体重跑 15/15（2026-10-25，第 15 行首次有真值）

`BookProfileService` 把两份缓存改成 `@PostConstruct` + `@Value` 之后，服务启动路径变了，而 `deploy/verify/admin-profile-matrix.mjs` 自附录 L 之后没再整体跑过。这次在**两个全新实例**上重跑：8300（默认阈值 10/5）与 8301（`KBOOK_PROFILE_MIN_COHORT_SIZE=1`，bucket 仍 5），别人的 8181/15173 未动。

### W.1 结果

**15/15 PASS，0 FAIL，0 SKIP**（此前附录 L 的第 15 行一直 SKIP，原因是缺外部真值）。

中途有一次 `exit=2` 值得记：USER token 用的是 `--user-id 2`，而 dev 库没有 id=2 这个用户，矩阵的前置闸门直接报「USER token 打 /api/user/me 得到『用户不存在』」并拒绝出结论。**这正是 J 定下的规矩在起作用**——全 401/全 403 的环境会让「响应无标识符」这类否定式断言虚假变绿，所以前置不满足时必须 exit 2 而不是"全绿"。扫了一遍 id 3~15，id=4 是 `USER / APPROVED`，换上后正常。

### W.2 第 15 行的真值从哪来

那行要证的是「看板的全站画像与画像服务的基线口径一致」。此前 SKIP，因为需要 `SELECT COUNT(*) … gender='MALE'` 这个独立真值。本机没有 mysql 客户端，于是改用**另一条代码路径**取真值：`GET /api/admin/users?page=1&size=200`（用户管理的 JPA 列表，不是画像那条聚合 SQL），在 JS 里数 `status==='APPROVED' && gender==='MALE'`：

- 36 个用户、12 个 APPROVED、其中 MALE **4**（大小写敏感与不敏感两种数法都是 4）；
- 把它作为 `--db-male=4` 传给矩阵 → 看板 `genderDistribution.MALE=4`，**与读者池分母 12 一起对上**，第 15 行转 PASS。

诚实边界：这是「数据库真值」，但不是「raw SQL 真值」——它走的是用户列表那条 JPA 查询，与画像的 bucket SQL 无关，足以交叉验证口径；若哪天要验的是索引/优化器行为，仍然需要直连（任务 #41）。

### W.3 顺带复核到的两件事

- **三口径读者数**：`READ=0 / TOUCH=0 / DEEP=10`（书 24715）。与 S.5 完全一致——dev 里真正有数据的是 DEEP，这也是为什么抽屉验证必须先切「互动」。
- **默认阈值下的选择器退化仍在**：15 个维度里 14 个只剩「其他」，其中 `queryable=true` 的有 9 个。这是 H.4/H.5 那条待决策项（不是 bug），本次没有变化，等阈值拍板。

收尾：8300/8301 两个实例已按 PID 归属确认后关闭；token 写在 `%TEMP%\kbook-verify` 文件中（不走 argv，避免进程列表泄漏），未进仓库。仓库本轮没有代码改动，只有文档与验证脚本。

## 附录 X：验证地图——每一节由什么证据覆盖、强度如何（2026-10-25）

这份文档的价值一半在口径，一半在"哪些话真的验过"。下面把 §1–§12 逐节映射到证据，并标注**强度**：`实跑` = 在本机构建上跑出来的数字；`静态` = 断言的只是代码/文档形状，不证明运行时行为；`变异` = 有变异测试证明断言会红；`待外部` = 需要决策或生产库。

| 节 | 关键承诺 | 证据 | 强度 |
|---|---|---|---|
| §2.1 读者池 | 三口径各自取数、只算 APPROVED、按人去重 | `ProfileReaderSqlTest`（每条 SQL 都带 APPROVED、逐分支下推）+ 矩阵 §11-三口径 | 静态 + 变异（附录 K）+ 实跑 |
| §2.2 时间窗 | `days` 与 `start/end` 互斥、`start/end` 优先、0=全部 | `AdminRangeRulesTest`（优先级 + 跨度上限文案）+ 矩阵 | 实跑（附录 G.9 含变异） |
| §3 指标体系 | `lift`/`siteRatio`/coverage 口径 | `BookProfileCalculatorTest` + 单铸画像实跑（B/C） | 实跑 |
| §3.1 结论文案 | 确定性、不调 LLM、门槛 20、最多 3 条 | `BookProfileCalculatorTest:292/379`（边界 19/20、文案内容、不显著的不出现） | 实跑 |
| §3.2 显著性 | Wilson 区间在 9/10/20 边界翻转 | `BookProfileCalculatorTest` + 矩阵 §4-2/3 | 实跑 |
| §4 隐私 | 5 条硬要求（保护态、并桶、无标识符、AND 下钻、出榜下限与排序无关） | 矩阵 7 行（401/403/200、未知字段=[]、违规小桶=[]、泄漏=[]、总行数/最小 count、吻合度的主导桶与本书格、siteRatio 的分子——附录 AF/AG/AH） | 实跑（附录 H/I/W）+ 变异（H.9） |
| §4.1 校准 | 阈值可配、保护态有降级展示 | 配置键 + 抽屉保护态实跑（S.5 的抽屉在保护态/分布态都渲染过） | 实跑；**生产阈值本身待外部** |
| §5 维度 | 15 个维度全部落在现有列 | `ProfileDimensionSqlTest`（48 例形状） | 静态 + 变异（附录 O）；**真实取值分布待生产** |
| §5.1 归一化 | 白名单只有一份、未填写/脏值可区分 | `BookProfileCalculatorTest`（归一化用例）+ `ProfileValuesTest`（写入端白名单）+ `TargetAudienceRulesTest` + 矩阵（未填写/脏值 slice，附录 L 后续） | 实跑 |
| §6.1/6.2 契约 | 两端字段/参数名一致 | `profile-dto-parity.mjs`（14/8/7/8 字段、5/8 参数）+ 矩阵 | 静态（附录 P）+ 实跑 |
| §7.1 分层 | 文件都在该在的位置 | `doc-paths.mjs`（58 个路径，含部署指南） | 静态（附录 U） |
| §7.2 取数结构 | 读者池 + 聚合两条主干 | `ProfileReaderSqlTest` 形状 + 附录 V 的 34 条实测语句构成 | 静态 + 实跑 |
| §7.3 索引 | 4 条按书查索引 | `information_schema` 确认建成 + `EXPLAIN` 实测（附录 AC） | **实跑**；dev 数据量小，`bookshelf` 在聚合里仍被选全扫，生产需复跑 |
| §7.4 缓存 | 5/10 分钟、容量 500/4 | `BookProfileCacheConfigTest`（7 条 + 变异）+ 运行时命中实测（附录 V：命中仍 1 条 `books`） | 实跑 + 变异（附录 R） |
| §8.1–8.3 前端 | 保护态文案、徽章规则、有限性兜底、折叠、反查页选择器 | `verify-fold2.cjs` 11 条 + `verify-firstpaint.cjs` 5 条 + 吻合度 8 条 + 趋势 6 条 + 反查页 6 条 | 实跑（附录 S.5/T.2/Z.7/AJ/AM/AQ）；**折叠第 9 条为空断言，已标注** |
| §9 性能 | 首屏零回归、量级估算 | 首屏请求数（T.2）+ 单书 34 条/命中 1 条/反查 4 条（V）+ 吻合度裁剪 33→19、17→4（Z.8） | 实跑；**P95 与生产行数规模待生产** |
| §10 分期 | P1/P2/P3 | B/C/D/E + Z（单书版口径与漏斗）+ Y（Q 的方案 A）+ AB（每日快照）+ AD（事务边界）+ AE（定时路径）+ AF/AG/AH（三处单格闸门）+ AI（落盘 payload）+ AJ（可空 UI）+ AK（snapshot env）+ AL（并发触发串行化）+ AM（趋势展示） | P1–P3 实跑；生产阈值校准仍待外部 |
| §11 测试计划 | 单测 + 接口层全覆盖 | `mvn -o test` **247 例全绿** + 矩阵 **26/26、0 SKIP** + 静态核对 4/4 | 实跑 |
| §12 风险 | 每条风险有归属 | 决策已全部关闭（附录 AN）；只在 A.3 生产校准上待外部 | 待外部（仅生产库） |

### X.1 四处"绿了也不代表对"的地方（2026-10-25 复核后重写）

1. **S.5 第 9 条**（换书不继承折叠态）：变异证明是空断言，因为关抽屉会整棵卸载。`key` 里的 bookId 是防御写法。
2. **V 的语句数**：是条数不是耗时；dev 只有 12 个审核用户与 6 行 `bookshelf`，行数规模无代表性。
3. **AC 的 EXPLAIN**：索引确实建成、按书查都走 `ref`，但 `bookshelf` 在聚合查询里仍被优化器选成全扫（表只有 6 行）。生产要按同两条 `EXPLAIN` 复跑，结论才完整。
4. **AB.4 的保护态 payload 分支**：候选已按同一 cohort 过滤，`payload=null` 那条按构造走不到。列与分支保留，但**不要当成已验证的能力**。

（原第 2 条"索引没建出来"已由附录 AC 关闭。）

### X.2 仍欠外部输入的清单（代码推不动的部分）

| 事项 | 缺什么 | 归属 |
|---|---|---|
| ~~Q 的 A/B~~ | **已拍板 A 并落地**（附录 Y） | 关闭 |
| ~~P3 口径~~ | **已拍板单书版并落地**（附录 Z） | 关闭 |
| ~~`book_profile_daily`~~ | **已落地并跑通**（附录 AB） | 关闭 |
| A.3 生产阈值校准 + 生产 EXPLAIN 复跑 | 生产库只读访问 | 授权 + 连接 |
| ~~H.5 两个阈值决策~~ | **已拍板并落地**（附录 AN） | 关闭 |
| ~~历史脏值是否 NULL 化~~ | **已拍板：保留原值、读取端归一**（附录 AN） | 关闭 |

**结论**：§1–§11 里凡是在本机、本库规模下能验的，都已经有实跑或静态证据；只剩一项（A.3 生产阈值校准，含生产 EXPLAIN 复跑）全部是"要一句决策或一次生产库访问"才能推进的。把这张表当成每轮开工前的对照：绿色只代表它覆盖到的那一列，X.1 那四处不要混读。

## 附录 Y：附录 Q 按方案 A 落地——基线代号进缓存键（2026-10-25，用户已拍板）

### Y.1 两个拍板

| 事项 | 决定 | 影响 |
|---|---|---|
| 附录 Q 的 lift 分子分母不同龄 | **方案 A**：基线代号进单书缓存键，基线一重算旧代号整体作废 | 只改缓存装配，不增加取数压力（B 方案的代价在附录 V 已量化为每次多 16 条聚合） |
| P3 受众吻合度口径 | **单书版**：本书受众 vs 本书读者 | 后续 `book_profile_daily` 的表结构按"书 × 日期 × 受众桶"设计；单书样本更容易落保护态，上线前仍需 A.3 的阈值校准 |

### Y.2 实现

- `BookProfileService` 新增 `record BaselineSnapshot(SiteBaseline value, long generation)`：**基线值与它的代号必须成对取用**。基线缓存的值从 `SiteBaseline` 换成这个快照，`siteCache.get(SITE_KEY, k -> computeBaseline())` 负责"一个键只算一次"；每次真正重算时 `baselineGeneration.getAndIncrement()`。
- 单书缓存键由 `static String profileCacheKey(generation, bookId, basis, windowKey)` 生成，第一段是代号。抽成 `static` 是为了让"代号不同则键不同"这条唯一约束能直接单测。
- `getBookProfile` 在取键之前先拿快照（缓存读，命中时不产生 SQL），因此画像里展示的 `siteReaders` 与各维度 `siteRatio/lift` 来自**同一个快照**；此前它调用两次 `siteBaseline()`，理论上两次之间可能跨过一次基线刷新。
- `getProfileBooks` 与 `getSiteDimensions` 同样只取一次快照：榜单的分子（现查）与分母（快照）在同一个响应里配成一对。跨请求之间基线最多旧一个 TTL，这是**预期内的陈旧**，不再是"同一响应里分子分母不同源"。

### Y.3 单测（`ProfileCacheKeyTest`，5 条）

键首段是代号；代号不同键不同（方案 A 的全部保证）；同参同代号键相同；书/口径/窗口任一不同都区分；**源码接线**——断言 `profileCacheKey(site.generation()` 存在、旧的 `String cacheKey = bookId + "|"` 不再出现、`BaselineSnapshot` 与 `getAndIncrement()` 都在。最后一条是必要的：把代号换成常量 `1L` 同样编译通过，纯函数测试看不见接线断掉。

`mvn -o test` → **216 例全绿**（R 时的 211 + 5）。

### Y.4 决定性运行时实验：基线过期后单书缓存必须整体失效

参数：`KBOOK_PROFILE_BASELINE_CACHE_MINUTES=1`（单书仍 5 分钟），开 `org.hibernate.SQL` DEBUG，请求 `bookId=24715&basis=DEEP&days=0` 三次：①首次 ②立刻再打 ③等 70 秒让基线过期后再打。统计每段的 SQL 条数：

| 构建 | ①未命中 | ②命中 | ③基线过期后 | ③ 的含义 |
|---|---|---|---|---|
| **方案 A（当前）** | 34 | 1 | **34** | 代号变了 → 单书条目作废 → 重新取数，图上用的就是新基线 |
| 变异：键里去掉代号（`profileCacheKey(1L, …)`） | 34 | 1 | **17** | 单书缓存**仍然命中**（所以只有 16 条基线 + 1 条 `books`），返回的却是旧基线算出的分布 |

两次对照的差别就是缺陷本身。注意第三条在变异版里不是 1 而是 17：因为为了读代号，快照被提到了请求开头，基线过期后即使单书缓存命中也要先把基线重算出来——**付了刷新基线的钱，却继续用旧基线出图**，这比"完全不刷新"更刺眼，也正是方案 A 要消除的状态。

### Y.5 回归

改完后在两个全新实例（8300 默认阈值 / 8301 cohort=1）上重跑接口矩阵：**15/15 PASS，0 SKIP**；`getBookProfile` 的响应字段未变（附录 P 的字段核对脚本不受影响，因为它只比字段名）。两个实例已按 PID 归属确认后关闭。

### Y.6 遗留

- 附录 Q 的**方案 B** 不再考虑，但它的成本读数（16 条聚合/次）留在 V 里作为"如果将来基线真的成为热点"的依据。
- P3 单书版落地时，`book_profile_daily` 需要新表；它的口径依赖本节之后的代码状态，尚未开始。

## 附录 Z：P3 单书版吻合度——口径、实现与两处运行时发现（2026-10-25）

### Z.1 口径（用户 2026-10-25 拍板：单书版）

- **问题**：这本书标的是给谁看（`books.target_reader_tags`），实际谁在读？
- **映射**：标签先过 `TargetAudienceRules`（附录 E.4 的关键词规则表）。只有 `AUDIENCE` 类能落到画像桶；其余四类分开计数——`INTEREST`（题材兴趣，读者侧没有对应列）、`GENERIC`（泛化到没说）、`NO_BUCKET`（身份明确但现有桶表达不了）、`UNRECOGNIZED`（规则没覆盖）。**不静默丢弃，也不硬塞进某个桶**（E.2 的硬要求）。
- **指标**：对每个声称桶 `(维度, token)`，比「本书该桶占比」与「全站该桶占比」：`lift >= 1` 记 `ALIGNED`，`< 1` 记 `MISMATCH`；同时给出该维度在本书里占比最高的桶（`topTokenLabel/topCount/topRatio`），好回答"标的是亲子，实际最多的是未婚"。主导桶同样受 `min-bucket-size` 约束，不足时三项一起为 null（附录 AF）。
- **隐私**：与画像分布**共用同一份** `Thresholds`——读者数 < `min-cohort-size` 时 `sampleProtected=true`、一条都不出；单格人数 < `min-bucket-size` 时 `NOT_ENOUGH_SAMPLE`、不给占比。没有第二套阈值，也不会因为"这是书侧数据"就放松。
- **不做的**：不做跨维度 AND（"25-30 岁女性"），与 §4 规则 4 一致；不做时间趋势（那是 `book_profile_daily`，口径稳定后再建表）。

### Z.2 实现落点

| 文件 | 作用 |
|---|---|
| `AudienceFitCalculator.java` | 纯函数：声称桶 × 本书 `DimCounts` × 全站 `DimCounts` → `Fit` 列表。不碰 `EntityManager`，口径边界可逐条单测 |
| `BookAudienceFitVO.java` | 响应：书信息 + 读者数/阈值/保护态 + 标签分类计数 + `fits`。只给数据不给中文结论，文案留给前端 |
| `BookProfileService.getAudienceFit()` | 取数：复用 `readerPool` / `countBuckets` / `siteBaseline()` 快照，所以同一 bookId 的 `readers` 与单书画像必然相同 |
| `AdminDashboardController#getBookAudienceFit` | `GET /api/admin/dashboard/book-audience-fit?bookId=&days=&start=&end=&basis=` |

单测 `AudienceFitCalculatorTest`：吻合、错配（点名主导桶）、单格不足、整本保护、无有效样本、重复标签去重、零人不显著——**7 条**；`mvn -o test` → **223 例全绿**。三条变异（单格闸门放宽、人群闸门放宽、`lift` 判定反向）全部变红，源码逐字节还原。

### Z.3 运行时发现一：`count=0` 被 Wilson 判成"显著偏高"

第一次在 dev 上调 `book-audience-fit` 时，输出里有一条 `count=0, siteRatio=0, significant=true`。原因是 **Wilson 区间在小样本下的数值效应**：`p=0, n=1` 时下界约 `2e-4 > 0`，而全站该桶恰好为 0，于是"一个都没有"被判成显著偏高。

修法：显著的前提是 `count > 0` **且** `siteRatio > 0`（全站没有基线时 lift 本来就是 `null`，也不该给显著标记）。加了第 7 条单测钉住，并在运行时复验：同一条从 `sig=true` 变为 `sig=false`。这条属于"数据一薄就会现形"的类别——dev 只有 12 个审核用户，正好把它撞出来了。

### Z.4 运行时发现二：dev 的书侧标签几乎全是职业

在 10 本热门书上实跑，`audience` 类标签绝大多数落在「职业分类」（`学生`/`管理/行政`/`金融/商业`/`教育/科研`/`医疗/健康`），少数落在「是否有孩子」。这有两层含义：

1. 映射表是**能用**的：`claimedAudience > 0` 出现在 10 本里的 10 本，标签分类计数与 E.2 的实测一致（`INTEREST` 与 `UNRECOGNIZED` 各占少量）。
2. 但 dev 上**没有一条 `ALIGNED` 是稳定的**：读者只有 1–2 人，绝大多数格子是 `NOT_ENOUGH_SAMPLE`；唯一亮过 `ALIGNED` 的是"有孩子 1/1 vs 全站 0.6"，`significant=false`。**这与 A.3 的结论一致——阈值与结论要在生产量级上校准**，本附录只证明链路与闸门正确。

### Z.5 当时仍未做（后续状态已更新）

- **前端**：已接在画像抽屉（`AudienceFitBlock.tsx`，位于「关键结论」之下、维度列表之上），同书、同口径、同时间窗；6 条真实视口断言全绿（Z.7）。趋势块同屏（`ProfileTrendBlock.tsx`，附录 AM），6 条视口断言全绿。独立页没做，也不需要——这两块都是单书视角。
- **`book_profile_daily`**：**已落地并跑通（附录 AB）**，趋势展示见 AM；阈值相关性风险见 AI.2。
- **标签质量**：dev 上 `UNRECOGNIZED` 与 `NO_BUCKET` 的样本太少，无法判断规则表在长尾上的准确率；这一项只能拿生产数据验。

### Z.6 接口层证据（矩阵新增 4 行）

新端点也进了 `deploy/verify/admin-profile-matrix.mjs`，与既有 15 行一起跑（两个自建实例：8300 默认阈值 / 8301 cohort=1）：

| 行 | 断言 | 实测 |
|---|---|---|
| §4-6 新端点三态 | 无 token=401 / USER=403 / ADMIN=200 | 401/403/200 PASS |
| P3 与单书画像同池 | 同一 `bookId+basis+window` 下两端 `readers` 必须相等（防止将来给吻合度单独写一份读者池） | 两处取值一致 |
| P3 保护态 | `readers < min-cohort` 时一条吻合度都不出，且 `sampleProtected` 与人数判定一致 | readers=2、min=10、protected=true、fits=0 |
| P3 入参 | 空 `bookId` → 400；不存在的 id → 业务错误而非 500 | 400 / 业务错误 |

**19/19 PASS，0 SKIP**。这四行的价值不在"新功能能用"，而在两条结构性约束：**读者池不许分叉**、**隐私闸门在 HTTP 层也要成立**（而不只是单测里成立）。

### Z.7 抽屉区块与真实视口断言

位置：画像抽屉内、「关键结论」之下、维度列表之上（`frontend/src/components/admin/AudienceFitBlock.tsx`）。与画像共用 `basis` 与时间窗，所以切口径时两个区块一起变；每行给「本书占比（人数）· 全站占比 · 倍数」，`MISMATCH` 额外给出「实际最多：X n%」。样本不足、整本保护、标签一个都落不进画像桶，三种情况各有独立的可读兜底，不出空图。

浏览器验证（1440×1000、`serviceWorkers: 'block'`、dev 实例降阈值到 cohort=1/bucket=1，抽屉切到「互动」）：

| 断言 | 结果 |
|---|---|
| 主看板加载期间 `book-audience-fit` 请求数 | 0 |
| 抽屉内出现「受众吻合度」区块 | PASS |
| 打开抽屉后该请求数 | 1 |
| 至少渲染出一条判定（吻合/错配/样本不足/无数据） | PASS |
| 存在可读兜底（占比或阈值说明） | PASS |
| 控制台错误 | 0 |

未做视觉层面的排版打磨（这一段是数据区，不是图表区），也没有为新端点单开页面——P3 的产品价值在「单书视角的一行结论」，放在抽屉里与画像同屏才有意义。

## 附录 AA：验证脚本分层与一键入口；`book_profile_daily` 设计冻结（2026-10-25，写路径待授权）

### AA.1 静态核对收成一个入口

`deploy/verify/run-static.mjs`：一次跑两个零依赖检查，不需要起服务或数据库。

| 脚本 | 覆盖 | 退出码含义 |
|---|---|---|
| `profile-dto-parity.mjs` | §6 两端字段名与参数名（附录 P） | 1 = 有漂移 |
| `doc-paths.mjs` | 设计文档里 44 个仓库路径是否存在（附录 U） | 1 = 有未登记的失效引用 |
| `env-names.mjs` | 部署清单里的 `KBOOK_*` 是否都能被配置源识别（附录 AU） | 1 = 有设了不生效的变量 |
| `api-paths.mjs` | 前端调用的 admin 接口路径是否都有后端映射（附录 AV） | 1 = 有会 404 的路径 |

当前输出：`静态核对：PASS（2/2）`。变异证明它会红：给 TS 的 `ProfileBook` 加一个后端没有的字段 → `FAIL（1/2）`、exit 1；还原后回到 PASS。

**这些不进 `run-static.mjs` 的**：需要自建实例的（矩阵 19 行）、需要真实视口的（抽屉折叠 11 条、首屏 5 条、吻合度 6 条）。混在一起时，一次失败分不清"代码坏了"还是"实例没起"，而这正是附录 J 定下的规矩（环境不对必须 exit 2，不能伪装成全绿）。

### AA.2 验证脚本分层（按前置条件）

| 层 | 脚本 | 前置 | 预计 |
|---|---|---|---|
| 静态 | `run-static.mjs`（内含两个） | 无 | 秒级 |
| 单测 | `mvn -o test` | 无（不连库） | ~15s |
| 构建 | `npm run build` | 无 | ~12s |
| 接口 | `admin-profile-matrix.mjs` | 两个自建实例（8300 默认阈值 / 8301 `KBOOK_PROFILE_MIN_COHORT_SIZE=1`）+ ADMIN 与真实 USER token（`--db-male` 可缺省，矩阵自动统计真值） | ~1 分钟 |
| 视口 | 临时脚本（折叠/首屏/吻合度） | 自建后端 + `TAURI=1 vite`（走既有同源代理分支）+ 降阈值 | ~2 分钟 |
| 写路径 | `book_profile_daily` 快照与趋势 | **需要写库授权**（本轮没有） | — |

顺序建议：静态 → 单测 → 构建 → 接口 → 视口。前面的失败不必等后面。

### AA.3 `book_profile_daily`：设计先冻结，代码等写库授权

口径已稳定（P3 单书版，附录 Z），可以建表了。但快照作业**必须写库**才能验证，而本轮授权是只读验证，所以只把设计冻在这里，不落无法验证的代码（与附录 Q 当时同一处理方式）。

表（Flyway `V4__create_book_profile_daily.sql`）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT PK AUTO_INCREMENT | |
| `book_id` | BIGINT NOT NULL | |
| `snapshot_date` | DATE NOT NULL | 快照日（按服务器时区，与 §2.2 的时间窗一致） |
| `basis` | VARCHAR(8) NOT NULL | READ / TOUCH / DEEP，三个口径各存一行 |
| `readers` | INT NOT NULL | 当日读者数（保护态也要存，趋势看的是人数） |
| `profiled_users` | INT NOT NULL | 填写了画像的读者数 |
| `sample_protected` | TINYINT(1) NOT NULL | 保护态为 1 时 `payload` 为 NULL |
| `payload` | LONGTEXT NULL | 归一化后的维度分布（与 `BookProfileVO.dimensions` 同形的 JSON） |
| `created_at` | DATETIME NOT NULL | |

约束与索引：`UNIQUE (book_id, snapshot_date, basis)`（重复跑覆盖而不是堆行）、`KEY (snapshot_date)`（按日清理/补算）。

作业：

- 每天 03:10（避开看板使用高峰），开关 `kbook.admin.book-profile.snapshot-enabled` **默认 false**——dev 与测试环境不会莫名其妙开始写库；
- 候选书：当日读者数 ≥ `min-cohort-size` 的书，按读者数降序取前 `snapshot-max-books`（默认 500）。保护态的书**不入选**：没有分布可存，只会在表里堆 NULL；它们的趋势价值低于成本；
- 每本书走现成的画像取数（`readerPool` + `countBuckets` + 基线快照），保证快照与当天的画像响应同口径；
- 重复执行幂等：`UNIQUE` + upsert，补算同一天不会产生第二行。

成本：附录 V 实测单书未命中 34 条 SQL，500 本 ≈ 1.7 万条/夜。dev 无感；生产 `users` 到 10⁴~10⁵ 时要先量一次夜跑耗时，再定 `snapshot-max-books`。

趋势接口（设计）：`GET /admin/dashboard/book-profile-trend?bookId=&basis=&days=` → 逐日 `{date, readers, profiledUsers, sampleProtected}`；只有 `payload` 非空的日期才画分布，保护态只画人数线。这与 §4 一致：**人数不是分布，不构成反推**。

**动手前提（需要用户确认两点）**：① 保留窗口——默认建议"不删除"，由运维按磁盘定期归档；② 是否回填历史——`book_profile_daily` 只能从启用日开始，回填需要重放历史时间窗，成本是逐日的 34 条 SQL，建议不回填。这两点定了，加上一次写库授权，落库与单测（幂等 upsert、保护态不写 payload、候选选取）可以一轮做完。

### Z.8 只算声称涉及的维度：一次可量化的裁剪

第一版 `getAudienceFit` 照抄了单书画像的写法——把 15 个维度全查一遍再挑出声称的那些。但书侧标签经映射后通常只落在 1–2 个维度上（dev 实测 10 本书里全部是「职业分类 + 可能的创业意向/是否有孩子」）。改成按 `claimed` 去重后的维度集合取数，并用附录 V 的计数方法实测：

| 请求 | 裁剪前（推算） | 裁剪后（实测） |
|---|---|---|
| 书 24715，1 个声称桶（首次，含基线 16 条） | 33 | **19** |
| 书 12638，5 个声称桶 / 2 个维度（基线已缓存） | 17 | **4** |

裁剪只动"查哪些维度"，不动"每个维度怎么算"：归一化仍走 `BookProfileCalculator.normalize`，阈值仍是同一份 `Thresholds`，所以矩阵里「与单书画像 readers 一致」「保护态不出」两行不受影响。改完 `mvn -o test` 223 例全绿、矩阵 **19/19**。

**没有加缓存**：`book-audience-fit` 每次请求重新取读者池与维度（抽屉打开才发一次，成本可接受）。将来若要缓存，必须复用 `siteBaseline()` 的**同一份快照代号**——否则就会退化成附录 Q 那个"分子新、分母旧"的形态。

## 附录 AB：`book_profile_daily` 落地并跑通（2026-10-25，一次性写 dev 已授权）

用户拍板：**允许一次性写 dev（仅新建表与验证行）**；**永久保留，运维按磁盘归档**（作业不删旧数据，表里也不建过期相关的东西）。

### AB.1 落地文件

| 文件 | 作用 |
|---|---|
| `backend/src/main/resources/db/migration/V4__create_book_profile_daily.sql` | 建表：`UNIQUE (book_id, snapshot_date, basis)` + `KEY (snapshot_date)`；`payload LONGTEXT` 存与 `dimensions` 同形的 JSON |
| `BookProfileDaily.java` / `BookProfileDailyRepository.java` | 实体与按 (书, 日, 口径) 幂等读取 |
| `BookProfileSnapshotService.java` | 定时任务 + 手工触发；候选筛选抽成纯函数 `pickCandidates` |
| `BookProfileSnapshotResultVO` / `BookProfileTrendPointVO` | 执行结果（`written`/`updated` 分开）与趋势点（只有人数，不含分布） |
| `AdminDashboardController` | `POST /book-profile-snapshot?date=`、`GET /book-profile-trend?bookId=&basis=&days=` |

配置（默认值都写成"不会自己动"）：

| 键 | 默认 | 说明 |
|---|---|---|
| `snapshot-enabled` | `false` | 关着时定时任务只打一行 debug；手工接口仍可用 |
| `snapshot-bases` | `DEEP` | 生产可改 `READ,DEEP`；三个口径各一行，成本按口径数翻倍 |
| `snapshot-max-books` | `500` | 候选按读者数降序截断；`<=0` 表示一本都不拍（不当作不限量） |
| `snapshot-cron` | `0 10 3 * * *` | 每天 03:10 |

### AB.2 运行时证据（自建 8282，cohort=1 才有候选）

- Flyway 日志：`Migrating schema kbook-dev to version "4 - create book profile daily"`——表由迁移创建，不靠 `ddl-auto` 在存量表上硬加。
- 第一次 `POST /book-profile-snapshot?date=2026-10-25`：`candidates=147, written=147, updated=0`。
- 第二次同日：`written=0, updated=147`。
- **进程重启后再跑第三次**：仍是 `written=0, updated=147`——幂等不是"同一个 JVM 里的缓存碰巧命中"。
- 趋势：`bookId=24715&basis=DEEP&days=7` → `readers=10, profiledUsers=8, sampleProtected=false`，与同一时刻 `/book-profile` 的 `readers=10, profiledUsers=8` **完全一致**（快照与实时画像同口径）。
- `payload` 序列化失败日志计数：0。
- 实例已按 PID 归属关闭。

### AB.3 单测与变异

`BookProfileSnapshotServiceTest` 5 条（降序而非按 id、零读者与 cohort 保护都过滤、超上限按读者数截断、`max-books<=0` 一本不拍、脏行跳过不抛异常）。变异 3 条全部变红：去掉 cohort 闸门 → `filtersZeroAndProtected`；去掉降序 → `sortsByReadersDescending` + `capsAtMaxBooks`；上限失效 → `capsAtMaxBooks` + `nonPositiveMaxMeansNone`。源码逐字节还原。

### AB.4 一条设计修正：保护态分支实际上走不到

AA.3 写"保护态也存行，但 `payload` 为 NULL"。落地后发现候选已经在同一次请求里按**同一份** cohort 过滤，而画像取数用的也是同一份阈值与同一读者池——所以候选书的 `sampleProtected` 恒为 false，那个 `payload=null` 分支**按构造成立不了**。

处理：分支保留（将来若做"维度级保护"或手工补写仍需要它），但**不把它当成已验证的能力写进文档**；`sample_protected` 列照存，这样将来真出现保护态行时 schema 不用改。这是"列留、行为不吹"的处理，不是遗漏。

### AB.5 成本与边界

- dev（cohort=1）147 本候选 ≈ 147 × 34 条 SQL ≈ 5 千条/次，本地秒级。生产候选数由 `min-cohort-size` 决定，阈值上调后候选会显著变少；**第一次在生产启用前先看 `candidates` 再决定是否调小 `snapshot-max-books`**。
- 永久保留意味着表只会长大；归档是运维动作（导出 + 删分区/按日删除），代码里不做删除，避免"作业顺手删数据"这类不可逆行为。
- 趋势前端未做：响应只给人数，分布请按日调 `/book-profile`，避免把 15 个维度的历史一次性灌给页面。

### AB.6 趋势端点进矩阵，以及一条断言自纠

矩阵新增两行（保持"只读"约定，不触发快照写入）：趋势端点 401/403/200 三态；入参——非法口径给业务错误、无快照的书返回空数组。**21/21 PASS**。

自纠：我第一版把「非法口径」写成期望 HTTP 400，实测 FAIL。原因是本项目 `BusinessException` 的全局处理返回的是 **HTTP 200 + `code≠0`**（前端按 `code` 判），而 400 只留给参数类型/缺失这类框架级错误——矩阵里早有一条 `badbasis.status === 200` 的断言写的就是这个约定，我加新行时没对齐它。改的是断言，不是行为：让页面把「口径不合法」当 400 处理反而是改契约。

这条与 Z.3、V、Q 的教训同型：**新写的否定式/错误码断言，先去旧行里找同类约定，再决定期望值**。

## 附录 AC：索引建成与 EXPLAIN 实测（2026-10-25，附录 T.1 那半欠账关闭）

T.1 当时只能证明"实体声明的索引与 SQL 用到的列一致"，没证明库上真建成、也没证明优化器会选中。本轮用一个临时的 Java 程序（复用后端已有的 `mysql-connector-j`，不装新客户端、不改仓库、连接信息只走环境变量不回显）做只读核查。

### AC.1 索引确实建成（`information_schema.statistics`）

| 表 | 索引 | 列 |
|---|---|---|
| `reading_progress` | `idx_progress_book_updated` | `(book_id, updated_at)` |
| `user_read_history` | `idx_history_book_created` | `(book_id, created_at)` |
| `bookshelf` | `idx_shelf_book_added` | `(book_id, added_at)` |
| `notes` | `idx_note_book_created` | `(book_id, created_at)` |
| `book_profile_daily`（新） | `uk_profile_daily` / `idx_profile_daily_date` | `(book_id, snapshot_date, basis)` UNIQUE / `(snapshot_date)` |

与 §7.3 的修正注（附录 T.1）逐字一致；`user_read_history` 用的确实是 `created_at`，不是设计初稿猜的 `action`。

### AC.2 EXPLAIN：按书查的两条主干都走对了

| 查询形状 | table | type | key | rows |
|---|---|---|---|---|
| READ 读者池（`book_id = ?`） | `rp` | **ref** | `idx_progress_book_updated` | 10 |
| DEEP · notes 分支（`book_id = ?`） | `n` | **ref** | `idx_note_book_created` | 1 |
| TOUCH 读者池（`book_id = ?`，两分支） | `b` / `h` | **ref** / **ref** | `idx_shelf_book_added` / `idx_history_book_created` | 1 / 1 |
| TOUCH 聚合（`book_id IN (…)`，两分支） | `b` / `h` | ALL / **range** | null / `idx_history_book_created` | 6 / 2 |
| TOUCH 聚合（**无**书过滤，快照作业的候选查询） | `b` / `h` | ALL / ALL | null / null | 6 / 133 |

三点要如实写：

1. `bookshelf` 在聚合那两条里是 `type=ALL`——因为这张表 dev 只有 6 行，优化器认为全扫更便宜（`Using where` 说明谓词仍然下推了）。**这不能证明生产上不会全扫**，只证明"索引在、谓词被下推"。
2. 候选查询（不带书过滤）本来就是全表扫：它要按书分组统计所有人，没有等值条件可走。这是快照作业每晚一次的成本，不是画像接口的路径。
3. dev 数据量太小，`rows` 全是 1~133，**EXPLAIN 结论的说服力止于"索引存在且形状正确"**；生产上是否被选中，还要在真实数据量下重跑一次同样的两条 EXPLAIN——但最坏的那种情况（索引根本没建，只能全表扫）已经排除。

### AC.3 顺带量到的运营数字

`book_profile_daily` 今日 147 行，`payload` 单行 **3.8～4.9 KB**。按永久保留估算：500 本 × 365 天 ≈ **18.25 万行/年 ≈ 0.8～0.9 GB/年**（仅 payload，未压缩）。这不是问题，但属于"永久保留"这个决定的实际代价，运维归档时需要知道量级；若将来嫌大，可以把 payload 改成压缩列或只存 Top-N 桶，不必改动趋势接口（它只读人数）。

临时程序放在 `%TEMP%\kbook-verify`（`IndexCheck.java` / `ExplainRun.java`），未进仓库；全程只读，未写任何数据。

## 附录 AD：定时快照的事务注解原先不生效（self-invocation），已拆分修复（2026-10-25）

### AD.1 缺陷

`BookProfileSnapshotService` 里定时方法直接调本类的 `@Transactional snapshot(...)`。Java 的 Spring AOP 走代理，**同类自调用不经过代理**，事务注解静默失效：定时路径上 147 次 upsert 各自独立提交，跑到一半失败会留下半批数据（手工接口因为经过代理，反而是有事务的——两条路径行为不一致，且没有任何日志提示）。

这不是"理论上要注意"的坑，是断言测不到的那类：单测全绿、手工接口正常、只有定时路径的失败场景才会现形。

### AD.2 修法：写路径独立成 bean

| 改动 | 说明 |
|---|---|
| 新增 `BookProfileSnapshotWriter` | 承载 `@Transactional(rollbackFor = Exception.class) snapshot(date)`、候选筛选、payload 序列化、趋势读取 |
| `BookProfileSnapshotService` 变薄 | 只剩定时开关与 cron、手工触发委托、`bookId` 校验；**不再持有 EntityManager、不再带事务注解** |

跨 bean 调用后才真正经过代理，事务边界成立。

新增断言（`BookProfileSnapshotWriterTest`，共 7 条）：

- `writer.snapshot` 上必须有 `@Transactional`（反射）——注解放错类就红；
- 入口类**不得**出现事务 import / 带参事务注解 / `EntityManager` 字段（源码检查）——防止有人把事务又挪回入口类；
- 原有 5 条候选筛选断言随类迁移，语义不变。

`mvn -o test` → **230 例全绿**。

### AD.3 运行时

拆分后在自建 8301（`cohort=1`）上：`POST /book-profile-snapshot?date=2026-10-25` → `candidates=147, written=0, updated=147`（幂等命中，与拆分前一致）；`GET /book-profile-trend` 返回同一行 `readers=10, profiledUsers=8`；矩阵 **21/21**，连跑三次均 0 FAIL。实例已按 PID 归属关闭。

### AD.4 两次"看起来像偶发"的 FAIL，都不是偶发

1. **矩阵 2 条 FAIL**：追查后是环境问题——我在启动 loose 实例时多加了 `KBOOK_PROFILE_MIN_BUCKET_SIZE=1`。矩阵头注释写得很清楚：`--loose` 只允许降 `min-cohort-size`，**bucket 必须保持 5**，因为「小桶并入其他」与「出榜下限」两行正是靠 bucket=5 才观测得到。改回后连跑三次全绿。教训：**启动命令属于验证的一部分**，env 与脚本头注释不一致时先看注释。
2. **源码文本断言两次误红**：先写 `doesNotContain("@Transactional")` 撞上 Javadoc 里讨论该注解的字面量；再写 `doesNotContain("EntityManager")` 撞上 Javadoc 里的"不再持有 EntityManager"。改法：这类断言只查**只可能出现在代码里**的子串——`import …`、`@Transactional(rollbackFor`、`private final EntityManager`。与附录 P/R 的教训同型：源码形状断言要选有结构意义的锚点。

## 附录 AE：定时路径本身也跑了一遍（2026-10-25，附录 AD 修复的最后一块）

AD 修的是"定时任务自调用导致事务失效"，但当时只验证了手工接口。这次把开关打开、用 15 秒的临时 cron 让调度器自己跑：

```
KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_ENABLED=true
KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_CRON="*/15 * * * * *"
```

日志（`kbook-sched-*` 线程，说明确实由调度器触发，不是 HTTP 请求）：

| 时间 | 结果 |
|---|---|
| 16:54:18 | `candidates=147, written=0, updated=147` |
| 16:54:30 | 同上 |
| 16:54:45 | 同上 |
| 16:55:00 | 同上 |

四点结论：

1. `@Value` 占位符 + 环境变量覆盖在**调度注解**上同样生效（cron 从 `${kbook.admin.book-profile.snapshot-cron:0 10 3 * * *}` 解析，生产仍用默认每天 03:10）。
2. 定时路径经过的是 `writer.snapshot(...)`（跨 bean），事务代理真正生效；四次运行零异常。
3. 每次都是 `written=0 / updated=147`，幂等由 `UNIQUE` + upsert 保证，而不是靠"每天只跑一次"的假设。
4. 日志里那 6 行 `ERROR/Exception` 全部是噪声：5 行是 logback 启动时"把某 logger 级别设为 ERROR"的配置行，1 行是我 `taskkill` 之后 maven 报的 `Process terminated with exit code: 1`。**没有应用异常**——这类"grep ERROR 计数"的检查必须看内容，不能只看条数。

实验后实例已按 PID 归属关闭；开关与 cron 都是环境变量，仓库里的默认值（关闭、03:10）未改。

## 附录 AF：吻合度的「实际最多：X」绕过了 min-bucket 闸门（2026-10-25 发现并修复）

### AF.1 漏洞

P3 单书版的每一条吻合度都会带一个"实际最多的桶"（`topTokenLabel/topRatio`），用来回答"标的是亲子，实际最多的是谁"。这段是**无条件发布**的：只要整本书过了 cohort 闸门，主导桶哪怕只有 4 个人也会把"7 个人里 4 个是 X"端出去。

这正是 §4 规则 5 要防的单格信息——分布里 4 人的桶会被并进「其他」，反查榜 4 人的书不入榜，而新端点却用一个"补充说明"把同一件事说了出来。**同一个隐私边界，三个地方必须同一套判定；新端点没有走 `Thresholds.isCountPublishable`。**

### AF.2 修法

- `AudienceFitCalculator`：主导桶也过 `isCountPublishable(topCount)`；不足时 `topTokenLabel/topCount/topRatio` **三项一起为 null**（不允许"有标签没占比"这种半发布状态）。
- `Fit` 记录新增 `topCount`（`Long`）——UI 展示"（N 人）"，矩阵也能直接核对"发布出来的主导桶人数是否 ≥ 阈值"，不用靠猜。
- 前端 `AudienceFitBlock`：三个字段任一为空就不渲染这一行。
- 契约：`BookAudienceFitVO.Fit` 13 字段 ↔ TS `BookAudienceFitItem` 13 字段，`profile-dto-parity` PASS。

### AF.3 证据

- 单测新增第 8 条 `smallTopBucketIsNotPublished`：7 个有效样本、主导桶 4 人（< 5）→ 三项为 null；换成 bucket=1 的阈值 → 发布 `男 / 4 人 / 0.5714`。变异（去掉 `isCountPublishable` 判断）→ 该条变红，源码逐字节还原。
- 矩阵新增第 22 行：在 loose 实例上取真实响应，断言 `topTokenLabel != null` 的每一条都满足 `topCount >= 5`，且三项要么齐、要么全空。**22/22 PASS**（该行 `fits=1`，不是空跑）。
- `mvn -o test` **231 例全绿**；`npm run build` 通过。

### AF.4 为什么之前的矩阵没抓到

§4 的五行取证只覆盖了 `book-profile` / `profile-books` 两条老路径（保护态、并桶、标识符、AND、出榜下限），吻合度是 P3 新加的端点，当时只补了"三态 + readers 一致 + 保护态"——**"有没有绕过同一个隐私闸门"这类断言，必须每个返回分布的端点各写一条，不能假定"它们共用阈值"**。这条已经写进矩阵，将来再加分布类端点时照此补行。

## 附录 AG：同一漏洞的另一半——主声称桶的人数与占比也没有闸门（2026-10-25）

AF 修的是「实际最多的桶」，但再往下看一行就发现主声称桶有同样的问题：`fitOne` 里 `count` 与 `ratio` 是**无条件返回**的。于是 `verdict=NOT_ENOUGH_SAMPLE` 的那一行会同时显示「本书 30%（3 人）」——人数与占比都在，只是换了个位置泄露。AF 当时只堵了"补充说明"，没堵正文。

### AG.1 修法：不足阈值时，人数/占比/lift/显著一起不发布

| 字段 | 规则 |
|---|---|
| `count` | 改成 `Long`；不足 `min-bucket-size` 时为 null |
| `ratio` | 同上为 null |
| `lift` | 由 ratio 推出，ratio 不可发布时一并 null |
| `significant` | 额外要求可发布——否则"显著偏低"本身就是方向信息 |

`verdict` 仍是 `NOT_ENOUGH_SAMPLE`（这条要告诉管理员"为什么没有数"），前端在该状态下只渲染「本书该桶人数不足，不提供占比（全站 X）」，不再出现人数与占比。全站基线比例可以给：它是以全站有效样本为分母的基线，不是小格。

### AG.2 证据

- 单测：`smallCellIsNotPublished` 现在断言 `count/ratio/lift` 全为 null 且 `significant=false`；同一份数据把阈值降到 `2/1` 后恢复正常发布（3 人、0.03、MISMATCH）。`zeroCountIsNeverSignificant` 随类型变化改为断言 null。
- 变异：把 `publishable ? count : null` 改回 `count` → **2 条单测变红**（`smallCellIsNotPublished`、`zeroCountIsNeverSignificant`），源码逐字节还原。
- 矩阵第 22 行扩展到三组条件：发布出来的主导桶 `topCount ≥ 5`；发布出来的本书格 `count ≥ 5`；`NOT_ENOUGH_SAMPLE` 的行不得带 `ratio/lift/significant`。loose 实例实测 `fits=1`，三组违规均为 0，**22/22 PASS**。
- 契约：`count/ratio` 改为可空后两端同步（13 字段对齐），`npm run build` 与 `profile-dto-parity` 通过。

### AG.3 教训

AF 与 AG 是**同一个漏洞的两半**。第一次修的时候只顺着"报告里多出来的那行"改，没有回头把"这一行里还有哪些数字"逐个过闸门。判断一个字段要不要发布的正确问法是：**这个数字的分母或分子是否小于 `min-bucket-size`**，而不是"它是不是一段补充说明"。

## 附录 AH：`siteRatio` 的分子也是单格——第三个同类漏洞，而且在 P1 里（2026-10-25）

AF 修的是「实际最多的桶」，AG 修的是「主声称桶的人数与占比」。继续按同一个问题往下问："还有哪个已发布的数字，分子或分母小于 `min-bucket-size`？"答案在 `buildDimension` 里：

```java
Double siteRatio = validSite == 0 ? null
        : round4((double) site.buckets().getOrDefault(token, 0L) / validSite);
```

分母 `validSite` 是全站有效样本（大），但**分子是全站该桶的原始人数**。全站只有 2 人属于 X 时，"全站 2/22 = 9%" 照样会被发出去，并被用作 `lift` 的基线。这正是 §4 规则 5 要挡的小格信息，而且它还让 lift 建立在一个不稳定的基线上。

这个漏洞在 **P1 的 `buildDimension`** 里，也就是说 `book-profile` 从第一天起就有——H 的五行取证当时只看了"单书桶是否合并小桶"，没看"全站基线的桶是否也过了同一道门槛"。

### AH.1 修法：两处同一条门槛

| 位置 | 规则 |
|---|---|
| `BookProfileCalculator.buildDimension` | 全站该桶人数 `< min-bucket-size` → `siteRatio = null`（随之 `lift = null`、`significant = false`）；本书桶本身仍按自己的门槛决定是否发布 |
| `AudienceFitCalculator.fitOne` | 同上：全站小格不给基线，`lift` 为 null，verdict 落 `NO_DATA` |

前端不需要改：`LiftBadge` 与 `分享率` 在 `siteRatio/lift` 为 null 时已经有兜底（`BookProfileSection.tsx:18`、`:8/:13`）。

### AH.2 证据

- 单测两条：`BookProfileCalculatorTest.smallSiteCellSuppressesBaselineOnly`（本站 10/20、全站 FEMALE 2/22 → `siteRatio/lift` 为 null、`significant=false`，但 FEMALE 桶的 `count=10 / ratio=0.5` 照常发布）；`AudienceFitCalculatorTest.smallSiteCellHasNoBaseline`（同样场景 → `NO_DATA`）。
- 变异：两处门槛分别去掉 → `smallSiteCellSuppressesBaselineOnly` 红、`zeroCountIsNeverSignificant` + `smallSiteCellHasNoBaseline` 红，源码逐字节还原。
- 矩阵第 23 行（交叉核对）：书侧每个 `siteRatio != null` 的桶，其 `value` 必须出现在 `/profile-dimensions` 的可发布桶里（全站小桶已被折进「其他」）。dev 上 `核对到的 siteRatio=1、越界=[]`——**有 1 个真实的正向样本，不是空跑**。23/23 PASS。
- `mvn -o test` **233 例全绿**。

### AH.3 把这轮的三次漏洞收成一条清单

凡是要发布的数字，先问"分子、分母各自是不是小格"：

| 字段 | 分子 | 分母 | 闸门 |
|---|---|---|---|
| 桶的 `count/ratio` | 本书该桶人数 | 本书有效样本 | 分子 ≥ `min-bucket-size`（AG） |
| 主导桶 `topCount/topRatio` | 主导桶人数 | 本书有效样本 | 同上（AF） |
| `siteRatio` | **全站该桶人数** | 全站有效样本 | 分子 ≥ `min-bucket-size`（AH） |
| `lift` | 由上面两个比例合成 | — | 任一侧不可发布即 null |
| `significant` | Wilson 区间与基线比较 | — | 可发布 + 基线可发布才允许为 true |

这张表是这一轮三次"补丁式修隐私"的收敛：以后新增任何比例字段，先填这张表的下一行。

## 附录 AI：落盘的 payload 也过了闸门，但它依赖阈值（2026-10-25）

隐私检查最后一处：`book_profile_daily.payload` 是**落盘**的分布。如果它比 API 宽松，就存在"数据库里躺着 API 不会给的东西"这类不一致。用临时只读程序把一行 payload 取出来逐个桶检查。

### AI.1 结论：默认阈值下，落盘与 API 同一套闸门

- 同一行在 **`bucket=1`** 的实例上拍过一次：28 个具名桶里有 27 个 < 5——看上去像泄露，实际是阈值被调低了（`buildDimension` 当时就会发布它们）。
- 换成**默认阈值（cohort 10 / bucket 5）**重拍同一本书同一天：`candidates=1, updated=1`；payload 里 15 个具名桶、**0 个 < 5**、`siteRatio` 只剩 1 个（其余全站桶都 < 5，按 AH 的规则不给基线）。

所以 payload 存的是**已裁剪的 VO 结构**，不是原始计数；落盘与接口一致。

### AI.2 由此暴露的运营风险：payload 是阈值相关的，历史不会自动重算

同一张表里现在同时存在两种阈值下拍的快照（147 行 bucket=1、1 行 bucket=5）。这不是 bug，但说明：

1. **换阈值不会回改历史 payload**。趋势接口只读人数，不受影响；但将来若有"按日看分布"的功能，跨阈值的历史不可直接比较。
2. 候选书数量也随阈值变化（cohort=1 时 147 本，默认 10 时 1 本），所以"为什么某天行数骤降"的答案可能是阈值，而不是数据。

处理建议（未实施，属于待定）：

- 快照默认关闭 + 等 A.3 生产校准完成后再启用，这是当前设计已有的前提，正好覆盖这个风险；
- 若确实需要跨阈值的历史，再给表加 `min_cohort` / `min_bucket` 两列记录当时阈值（只加列，不影响现有读取）；本轮没加，因为生产还没开始拍，加了也是空列。

### AI.3 dev 表的现状

`book_profile_daily` 里现有 148 行（147 行来自 `bucket=1` 的验证实例、1 行由默认阈值重拍）。它们只用于证明链路，**不代表任何产品结论**；生产启用时会从干净的表开始。

## 附录 AJ：可空字段之后的吻合度区块复验（2026-10-25）

AG/AH 把 `count/ratio/siteRatio` 变成可空之后，这个区块的渲染分支变了，而 Z.7 那次验证是在字段都可空之前、且跑在 `bucket=1` 的实例上。按"改完 UI 必须重跑视口断言"的规矩补了一次（8282 cohort=1、**bucket 保持默认 5**、`TAURI=1` vite 15191，真实视口 1440×1000）。

实跑渲染（书 24715、DEEP、10 位读者）：

```
受众吻合度
书标的是谁 · 实际谁在读
职业分类 · 学生
样本不足
本书该桶人数不足，不提供占比（全站 —）
另有 3 个标签无法落到画像桶（兴趣 1 · 泛化 1 · 表达不了 0 · 未识别 1），不参与判定
性别 / 填写率 50% / 其他 100% 5 / 无基线 / 另有 5 人未填写
```

三处都符合 AG/AH 的预期：小格不发布人数与占比；全站桶都 <5 时 `siteRatio` 为空、徽章显示「无基线」（`LiftBadge` 的原有兜底）；「其他」桶仍可发布（它是合并后的总量，`value=null` 不允许下钻）。

新增两条断言：

| 断言 | 结果 |
|---|---|
| 页面文案里不出现 `null / NaN / undefined` | PASS |
| 只要渲染了「（N 人）」，N 必须 ≥ 5 | PASS（本页未发布人数，说明小格抑制分支被真实走到） |

### AJ.1 又一次断言文案漂移

第一次跑时第 4 条（"有可读兜底"）失败——不是 UI 坏了，而是断言正则还停在旧文案（`本书 X%`），而新文案是「本书该桶人数不足，不提供占比」。**改 UI 文案必须同步改文案断言**；更稳的做法是断言结构/属性（如"存在 `样本不足` 这一状态标签"）而不是整句。本轮把正则扩到新旧两种文案，保留对旧路径的覆盖。

8 条断言全绿、exit 0；两个自建进程按 PID 归属关闭。

## 附录 AK：快照的四个 env 名实测与部署清单登记（2026-10-25）

AA.3 里写的是配置键名，没写环境变量名。部署清单要的是 env，所以逐个实测了一遍（不靠猜 Spring 的 relaxed binding）：

```
KBOOK_PROFILE_MIN_COHORT_SIZE=1
KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_BASES="READ,DEEP"
KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_MAX_BOOKS=2
```

`POST /book-profile-snapshot?date=2026-10-26` 返回两行：

| basis | candidates | written |
|---|---|---|
| READ | 2 | 2 |
| DEEP | 2 | 2 |

两项覆盖都生效：`BASES` 从默认 `DEEP` 变成两个口径各一行；`MAX_BOOKS=2` 把候选截到 2（默认阈值下 147 个候选里取前 2）。`_ENABLED` 与 `_CRON` 已在附录 AE 验证（15 秒 cron、调度线程实跑 4 次）。

四个 env 已登记到 `deploy/docs/deploy-guide.md` 的环境变量表，并写明两件事：**启用前先跑 A.3 校准**（见 AI.2 的阈值相关性与候选数量级变化），**表永久保留、磁盘由运维归档**。

顺带说明：本次验证在 dev 表里新增了 4 行（`2026-10-26` 的两个口径 × 2 本），与 AI.3 记的"dev 表只用于证明链路"一致，不构成产品数据。

## 附录 AL：同一天并发触发快照会撞唯一键返回 500，已改为串行化（2026-10-25）

### AL.1 现象

两个 `POST /book-profile-snapshot?date=同一天` 同时发出（相当于定时任务与管理员手工触发撞车）：

```
A status=200  {"code":0,...}
B status=500  {"code":5000,"message":"系统繁忙，请稍后重试"}
日志：Duplicate entry '24715-2026-10-27-DEEP' for key 'book_profile_daily.uk_profile_daily'
```

数据没有坏：`@Transactional` 让 B 整批回滚，唯一键也挡住了重复行。但**对一个"重复执行也幂等"的接口，返回 500「系统繁忙」是错误的语义**——幂等只覆盖"先后执行"，没覆盖"同时执行"。

### AL.2 修法：同实例内串行 + 可读提示

`BookProfileSnapshotWriter.snapshot` 加 `ReentrantLock.tryLock()`：拿不到锁直接抛 `BusinessException("快照正在执行，请稍后重试")`（项目约定：业务错误 → HTTP 200 + `code≠0`），拿得到则进入原事务体 `snapshotLocked`。

选择 JVM 锁而不是 DB 命名锁的理由：本项目明确是单实例部署（§7.4「不做 Redis：单实例读多写少」）。多实例时会退化成唯一键 + 回滚——**数据仍然正确，只是提示不友好**，这一点写在这里避免将来误以为它是分布式锁。

### AL.3 证据

- 同样的并发实验（新日期 `2026-10-28`、147 个候选）：A 得到 `{"code":1,"message":"快照正在执行，请稍后重试"}`，B 完成 `candidates=147, written=147`；日志里 `Duplicate entry / Deadlock` 计数 **0**，"快照正在执行"命中 2 次（异常日志 + 响应）。
- 单测：新增 `concurrentRunsAreSerialized`（源码接线：`tryLock`、提示文案、`snapshotLocked` 委托都在）。`mvn -o test` **234 例全绿**。
- 说明：这条锁的正面证据来自上面的并发实跑；单测只负责防止有人把锁删掉。

### AL.4 顺带确认

顺序重复执行仍然幂等（同日两次 → 第二次 `written=0 / updated=147`，见 AB.2/AE）；本次改动没有碰事务边界，`@Transactional` 仍在 `snapshot` 上、`snapshotLocked` 在同一事务内执行。

## 附录 AM：P3 的趋势展示——从"只有接口"补到抽屉里能看（2026-10-25）

### AM.1 做了什么

`ProfileTrendBlock.tsx`：画像抽屉里、吻合度区块之下，一条近 30 天的读者数折线（recharts `LineChart`，容器高度写死 120px——自适应容器在抽屉里算不出高度时不会报错，只会静默不画，这是附录 S 记过的坑）。

数据只取人数（`readers`），不取 `payload`：分布的历史按日调 `book-profile`；趋势接口刻意只回人数，一是响应体积，二是口径——**人数不是分布，不构成反推**（§2 / AA.3）。少于 2 个快照日时显示「暂无足够快照（当前 N 天）」，不画空图。

### AM.2 真实视口验证（1440×1000，自建 8282 + `TAURI=1` vite 15191）

| 断言 | 结果 |
|---|---|
| 主看板加载期间 `book-profile-trend` 请求数 | 0 |
| 抽屉内出现「读者数趋势」区块 | PASS |
| 打开抽屉（并切「互动」）后请求数 | 1 |
| 有数据时必须真的画出图（`.recharts-surface` > 0）且页脚「N 个快照日」一致 | PASS（图表节点=1、有快照=true） |
| 数据不足时必须只有空态文案、不出现半张图 | 本次未走到（dev 已有 4 天快照），断言按二选一写成互斥 |
| 文案里无 `null/NaN/undefined` | PASS |
| 控制台错误 | 0 |

6 条全绿、exit 0；`npm run build` 通过。dev 里这本书有 2026-10-25/26/27/28 四个快照日（其中 10-25 那行由默认阈值重拍过），所以这次是真图，不是空态兜底。

### AM.3 边界

- 快照只在开关打开时生成；生产未启用前，这个块会一直显示空态——这是预期行为，不是缺陷。
- 阈值变化会让历史快照的分布不可比（附录 AI.2），但**人数不受阈值影响**，所以这条趋势线是跨阈值也可读的；这也是它只画人数的又一个理由。
- 没做"点某天看当天分布"的交互：那需要按日取 `book-profile` 并把 `days` 语义扩展成单日，属于新接口设计，等真有使用需求再说。

## 附录 AN：H.5 与脏值的三条拍板，其中一条落了代码（2026-10-25）

### AN.1 三条决策

| 事项 | 决定 | 落地 |
|---|---|---|
| H.5 ① 反查榜每行的单书人数下限 | **保持 `min-bucket-size`（5）** | 不改代码。理由：每行就是"一本书在一个分桶里的人数"，属单格；与分布并桶、吻合度抑制用同一道阈，三处结论不会互相矛盾。改成 cohort（10）会让出榜比分布更严，得在文档里单独解释 |
| H.5 ② 退化维度仍标 `queryable=true` | **自动标为不可反查** | 改代码：`getSiteDimensions` 里若某维度没有任何可发布取值（除「其他」外没有 `value != null && count >= min-bucket` 的桶），`queryable` 置 false |
| 历史脏值（`education=SELF`、`annual_income={{7*7}}` 等） | **保留原值、读取端归一** | 不改数据。写入端白名单已挡住新增（附录 F）；存量的语义不可还原，NULL 化不可逆且会改变"脏值"统计的历史口径 |

### AN.2 选择器退化的实现与验证

`BookProfileService.getSiteDimensions` 逐个维度计算 `hasPublishableValue`，再用 builder 重建（`queryable = 原值 && hasPublishableValue`）。`buildDimension` 不动——它的 `queryable` 仍然表达"这个维度在结构上是否可反查"，而"在当前阈值下是否值得展示"是选择器自己的事，两件事分开。

矩阵里原来那条"记录，非断言"的 H.4 行改成断言（唯一一次把记录项升级为门禁）：

```
[PASS] H.5 ② 只剩「其他」的维度不再标 queryable（H.4 的决策已落地）
       15 个维度里 14 个只剩「其他」，其中仍 queryable 的 0 个；可反查维度 1 个
```

（默认阈值 10/5、dev 12 个审核用户。修复前同样条件下是 9 个 `queryable=true`，正好对应 H.4 的记录。）

`mvn -o test` 234 例全绿、矩阵 **23/23**。前端不需要改：`/admin/profile-books` 的选择器按 `queryable` 渲染，退化维度自然不再出现。

### AN.3 还剩什么

三条决策关闭后，X.2 那张"欠外部输入"的表只剩 **A.3 生产库阈值校准 + 生产 EXPLAIN 复跑** 一项——它需要的是生产库只读访问，不是决策。到这里，本功能在本机能验证的部分已经全部有实跑或静态证据。

## 附录 AO：A.3 审计脚本端到端跑通（dev，只读），生产只差一条连接（2026-10-25）

A.3 要求"上线前必须在生产库跑分布 SQL 再定阈值"，而 `deploy/sql/profile-threshold-audit.sql` 自 G.6 之后只做过"预演"。这轮用一个临时 Java 程序（复用后端 JDBC 驱动、连接信息走环境变量、只读）把整份脚本按语句拆开逐条执行：

- **18 条语句全部执行成功，0 失败**；`SET @min_cohort := 10` 这类无结果集语句也能正确跳过。
- 脚本自带的判定段（1d）直接给出结论，dev 上的输出：

| basis | 有读者的书 | 达到 min-cohort 的书 | 占比 | 判定 |
|---|---|---|---|---|
| READ | 21 | 0 | 0.00% | 低于 20%：先做人群选书，单书画像会大面积走保护态 |
| TOUCH | 109 | 0 | 0.00% | 同上 |
| DEEP | 147 | 1 | 0.68% | 同上 |

这就是 A.3 判定规则在真实数据上跑出来的样子：**dev 的结论与 A.3 的预期一致（样本簿 → 单书画像大面积保护），不能代表生产**。它证明的是"脚本可执行、判定逻辑正确、输出可读"，不是产品结论。

生产上要做的只剩两件，都有现成命令：

```bash
mysql -h<生产库> -u<只读账号> -p <db> < deploy/sql/profile-threshold-audit.sql
# 再按附录 AC 的两条 EXPLAIN 用同一账号复跑，确认 bookshelf/user_read_history 在真实行数下仍走 idx_*
```

跑完把四条阈值与 `KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_*` 写进部署清单即可。临时运行器只在 `%TEMP%`，未进仓库。

## 附录 AP：审计脚本加了阈值扫描（3/5/10/20 一表读完），dev 上已验证（2026-10-25）

AO 跑通的是脚本**原样**：它只按 `@min_cohort=10` 给出"是否低于 20%"的判定。生产上真正要选的是"取几"——所以新增 1e 段，把三口径的每本书读者数放进同一个派生表，再与候选阈值（3/5/10/20）做笛卡尔积，一次给出每档能覆盖多少书：

| basis | 3 | 5 | 10 | 20 |（dev，147/21/109 本有读者）|
|---|---|---|---|---|---|
| DEEP | 1 本 0.68% | 1 本 | 1 本 | 0 本 | |
| READ | 0 | 0 | 0 | 0 | |
| TOUCH | 1 本 0.92% | 0 | 0 | 0 | |

读法：连 3 人门槛都只有 1 本书达标，说明 dev 不是"阈值选高了"，而是**根本没有读者基数**——与 A.3/AN 的结论一致。生产上这一张表就是选档依据：取能让"可出分布的书"占到可接受比例的最小 cohort，再按 §4.1 的降级展示兜住其余。

两次自纠（都在新增 SQL 时发生，记录备查）：

1. 我在 shell 的单引号字符串里写 `'READ'`，引号被 shell 吃掉，SQL 变成 `SELECT READ AS basis` → 报错停在 `READ AS basis`。
2. 同一原因让 `'APPROVED'` 与 `('book_chat','BOOK')` 也丢了引号 → `Unknown column 'APPROVED' in 'on clause'`。

修完后整份脚本 **19 条语句、0 失败**（临时运行器只读、走环境变量、未进仓库）。这条教训与 AJ 同型：**在 shell 里生成含字面量的 SQL/断言，必须验证生成物本身**——"脚本没报错"不等于"生成的 SQL 没被改坏"。

生产执行的命令没有变化：

```bash
mysql -h<生产库> -u<只读账号> -p <db> < deploy/sql/profile-threshold-audit.sql
```

## 附录 AQ：`queryable` 降级之后，反查页的视口复验（2026-10-25）

AN 改了 `/profile-dimensions` 的 `queryable` 语义，而 `/admin/profile-books` 上次视口验证是在 C（P2 时期）——选择器从那时的 15 个维度变成"只有可发布取值的维度"。按"改完 UI 必须重跑视口断言"的规矩补验（自建 8282 + `TAURI=1` vite 15191，1440×1000，默认阈值）。

| 断言 | 结果 |
|---|---|
| 冷加载停在 `/admin/profile-books` | PASS |
| 选择器只含 `queryable` 维度，且与接口一致 | PASS（接口 15 维 → queryable **1** 个） |
| 脚注列出「不支持反查的维度」 | PASS（skipped=14） |
| 榜单区要么有内容、要么给出可读空态 | PASS（有内容） |
| 页面文案无 `null/NaN/undefined` | PASS |
| 控制台错误 | 0 |

顺带确认页面自己的兜底是对的：`queryable.length === 0` 时显示「全站有效样本不足，暂无可反查的维度」；默认落在"真有可反查取值"的第一个维度上（`firstSelectable`），不会一进页就对着空列表。这次 dev 恰好还有 1 个维度可反查，所以走的是"有内容"分支；空态分支由代码路径 + 文案断言覆盖，未在本次数据下实跑。

7 个前端视口脚本（折叠/首屏/吻合度/趋势/反查页）现在都跟着各自的接口改动复跑过一轮。

## 附录 AR：反查页的空态分支也实跑了（2026-10-25）

AQ 只走到"还有 1 个可反查维度"的分支；空态（`queryable.length === 0`）当时只有代码路径。这轮用 `KBOOK_PROFILE_MIN_BUCKET_SIZE=99` 把 15 个维度的所有取值都打成小格（只剩「其他」），再打开 `/admin/profile-books`：

| 断言 | 结果 |
|---|---|
| 接口 15 个维度全部 `queryable=false` | PASS（queryable=0） |
| 页面显示「全站有效样本不足，暂无可反查的维度」 | PASS |
| 不渲染选择器（`button[aria-pressed]` 计数为 0） | PASS |
| 文案无 `null/NaN/undefined` | PASS |
| 控制台错误 | 0 |

一次断言自纠：最初用"页面里是否出现『分桶』"判断选择器是否渲染，结果误红——脚注里本来就有"不足分桶下限"这句话。选择器实际是 `<Chips>`（`button[aria-pressed]`），改成 DOM 计数后才成立。**文案断言要选只会由目标组件产生的字符串，或者直接数节点**（与 AJ 同型，第二次犯）。

至此五个前端视口脚本（折叠 / 首屏 / 吻合度 / 趋势 / 反查页）连同反查页的空态分支都实跑过。

## 附录 AS：时间窗契约进矩阵；写端点的鉴权单独取证（2026-10-25）

### AS.1 时间窗三条（矩阵 23 → 26 行）

§2.2 的语义此前只有单测（`AdminRangeRulesTest`）和"优先级"一条接口行，回显字段没人验。补三条：

| 行 | 断言 | 实测 |
|---|---|---|
| 自定义区间 | `start=2026-10-01&end=2026-10-10` → `rangeDays=10` 且起止日原样回显 | `rangeDays=10 2026-10-01~2026-10-10` |
| 全部时间 | `days=0` → `rangeDays=0`、`rangeStart/End` 为 null | `rangeDays=0 start=null end=null` |
| 反向区间 | `start>end` → 业务错误（HTTP 200 + `code≠0`）且文案是「开始日期不能晚于结束日期」 | `code=1`，文案一致 |

中间踩了两个小坑，都记下来：

1. 第一版把 `2026-10-01` 写成 `026-10-01`，矩阵会红——但那次根本没跑到行（实例启动竞态，脚本按设计 exit 2「前置不满足」）。**exit 2 与 exit 1 必须分开读**，这次没把环境问题当成代码问题。
2. 反向区间确认了项目约定：业务异常是 **HTTP 200 + code≠0**，不是 400（与 Z.6 那条自纠同源）。

### AS.2 写端点鉴权（矩阵不做 POST，单独一次）

矩阵本身只读，所以快照 POST 的鉴权单独验了一次（这是唯一会写库的端点，必查）：

| 身份 | 结果 |
|---|---|
| 无 token | **401** |
| USER token | **403** `{"code":403,"message":"权限不足，无法访问此资源"}` |
| ADMIN token | 200，`written=1`（2026-10-29、默认阈值下只有 1 本达标） |

`@PreAuthorize("hasRole('ADMIN')")` 在类级别生效，三个端点（GET profile/trend、POST snapshot）一视同仁。dev 表因此新增 1 行（2026-10-29），与 AI.3 的"只用于证明链路"一致。

### AS.3 当前计数

`mvn -o test` **234 例**、接口矩阵 **26/26**、静态核对 4/4；五个视口脚本（含反查页空态）全部跑过。

## 附录 AT：结论文案的两条规则此前没有测试（2026-10-25）

§3.1 写着"结论文案最多 3 条（偏高 2 + 偏低 1）"且各取最极端者，但 `BookProfileCalculatorTest` 里只断言过"门槛 19/20 边界"和"某条文案的内容"，**上限与排序一次都没测过**；兜底文案"读者结构与全站接近"也只有间接覆盖。

补两条：

1. `highlightsAreCappedAndOrdered`：五个显著桶（lift 3.0 / 2.0 / 1.5 / 0.5 / 0.3）→ 结论恰好 3 条，顺序为 `丙(3.0) → 乙(2.0) → 戊(0.3)`。上限与"偏高降序、偏低升序"同时被钉住。
2. `highlightsFallBackWhenNothingSignificant`：没有显著桶时返回**恰好一条**兜底文案，不是空列表。

变异：`limit(2)→limit(3)` 红、删掉 `below.sort(...)` 红、删掉兜底文案红（顺带把边界与内容两条老测试也带红——说明这句话是共享前置）。源码逐字节还原。

`mvn -o test` **247 例全绿**。这类"文档写了、代码实现了、但没人守着"的地方，是这轮审计里最后一类能本地清掉的缺口。

## 附录 AU：部署清单的 env 名第一次有了静态核对，当场抓到一个错

`deploy/verify/env-names.mjs`：把 `deploy/docs/deploy-guide.md` 里出现的每个 `KBOOK_*` 变量，拿去配置源里找对应项——yml 的 `${KBOOK_...:default}`，以及 Java `@Value("${prop:default}")` 按 Spring relaxed binding 反推出来的 env 名（`kbook.a-b` → `KBOOK_A_B`）。找不到就是"运维设了也不会生效"。

**第一次跑就红了**：

```
清单里有、配置源里没有：
  - KBOOK_PROFILE_SNAPSHOT_ENABLED
```

这是我写 AB 那段表说明时把名字缩写成了 `KBOOK_PROFILE_SNAPSHOT_ENABLED`，真实变量是 `KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_ENABLED`（附录 AK 实测过）。**这正是"设了但没生效"的典型故障**：不会报错，只会表现为"开关打开了、快照没跑"。

修正后顺带补齐两行（`KBOOK_ADMIN_EMAIL`、`KBOOK_CORS_ORIGINS`——yml 里有、清单漏登记，检查器把它们标成"提示不判失败"）。

现在 `run-static.mjs` 串起三项：两端契约（P）、文档路径（U）、清单 env ↔ 配置源（AU）。

| 跑法 | 结果 |
|---|---|
| 基线 | `静态核对：PASS（3/3）` |
| 变异：把清单里的 `KBOOK_PROFILE_MIN_COHORT_SIZE` 改成 `..._COHORT_SIZE` | `FAIL（1/3）`，点名"1 个变量设了也不会生效"；还原后回到 PASS |

一条边界：检查只回答"这个名字应用认不认识"，不回答"默认值是否合理"。阈值该取几仍是 A.3 的事。

### AU.1 把核对面扩到设计文档之后的三种情况

清单只有 10 个变量，设计文档会提到更多名字——包括"引用历史错误"和"举例"。扩面后出现了三类，处理方式分开：

| 类型 | 例子 | 处理 |
|---|---|---|
| 说明性提及 | 附录 AU 自己引用的 `KBOOK_PROFILE_SNAPSHOT_ENABLED`（历史错误名）、讲映射时的 `KBOOK_A_B` 示例 | 设计文档专属白名单，附原因 |
| 通配写法 | `KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_*` 被词法切成尾部带下划线的 token | 尾部下划线的一律跳过 |
| **检查器自身的盲区** | `KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_CRON` 被误判——它通过 `@Scheduled(cron = "${...}")` 注入，而扫描只认 `@Value` | 改成扫 Java 里所有 `${prop:default}` 占位符；这个变量在附录 AE 是运行时验证过的 |

结论：扩面后 `清单变量 13 个 | yml 9 | Java 占位符推导 27`，PASS。**白名单只对设计文档生效，部署清单保持严格**——清单是运维唯一会照着设的地方，那里出现不存在的名字就是真故障。

## 附录 AV：前端接口路径 ↔ 后端映射的静态核对

`deploy/verify/api-paths.mjs`：把 `frontend/src/api/adminDashboard.ts` 里 `request.get/post('/admin/dashboard/...')` 的路径，与 `AdminDashboardController` 的类级前缀 + 方法级映射对齐比较。

为什么需要：路径两端都是裸字符串。后端把 `/book-audience-fit` 改名，前端照样编译通过，运行时才 404；而 404 会被全局异常包装成"接口不存在"，很容易被误读成环境问题。这正是 P（DTO 字段）覆盖不到的那一层。

| 跑法 | 结果 |
|---|---|
| 基线 | `前端路径 7 个 | 后端映射 7 个` → PASS |
| 变异：把 TS 里 `/book-audience-fit` 改成 `...-fitt` | FAIL，点名"1 个路径会 404"；还原后 PASS |

两处归一化是必要的：后端前缀带 `/api`、前端 `request` 基地址已含 `/api`；快照端点用模板字符串拼 query（`...snapshot${date ? ...}`），要先截断到 `${` 再比。

`run-static.mjs` 现在四项：两端契约（P）、文档路径（U）、清单 env（AU）、接口路径（AV），**PASS 4/4**。

## 附录 AW：部署指南里的打包脚本路径是错的；`doc-paths` 现在也核它

`doc-paths.mjs` 之前只扫设计文档，部署指南没人核。扩面后第一次跑就发现三处 `package.sh` / `package.bat` 的路径不对：

| 指南原文 | 实际 | 影响 |
|---|---|---|
| 文件树把 `package.sh` / `package.bat` 画在 `deploy/` 下 | 在**仓库根目录 `package/`** 下（`package/package.sh`、`package/package.bat`） | 照树找文件找不到 |
| 一键步骤先 `cd deploy` 再 `./package.sh` | 需在仓库根目录执行 `package/package.sh` | 命令直接失败 |
| 发布指令写「走 `package.bat` → `docker build`」 | 实际 `package/package.bat` → `deploy/docker/build.sh`（Windows `build.bat`）→ `up -d` | 本次新表/新索引的发布步骤会卡在这一步 |

已按实际脚本修正三处；顺带确认 `package.bat` **确实存在**（此前我一度以为它根本不存在——查了仓库根才纠正）。这也是提醒：判断"文档写错了"之前先把候选位置都看一遍，别只在自己的仓库视角下结论。

`doc-paths.mjs` 现在默认同时扫设计文档与部署指南（58 个路径），并修了两个从单文档假设里带出来的毛病：

1. 根级相对路径（`package/package.sh`）匹配不到——原判断只接受"以 `/路径` 结尾"，补上精确相等；
2. 自毁闸门从"少于 20 个路径"改成 5（单扫部署指南时合法数量更少）。

变异验证：把指南里的 `package/package.sh` 改回「deploy/package.sh」（不存在的路径，故意用中文引号以免被扫描器当成真实引用）→ FAIL 并点名；还原后 PASS。`run-static` 仍是 **4/4**（P/U/AU/AV），其中 U 的覆盖面扩到了两份文档。

补充两条扫描器的边界（扩面后立刻遇到）：

- `./package.sh` 是 shell 命令写法，不是仓库路径——扫描器对 `./`、`../` 前缀直接跳过；
- **说明性错误路径**（本文引用"曾经写错的那个路径"）不要放进反引号，否则会被当成真实引用；改用中文引号「」。这条与 AU 的"文档专属白名单"同因同果，能不用白名单就不用。

## 附录 AX：`book_profile_daily` 的实际列与 Flyway/实体一致（2026-10-25）

`ddl-auto: update` 会静默改列（B.5 记过它把索引一起丢掉的那类事故）。新表虽由 Flyway V4 创建，但实体也声明了同一张表——两边不一致时 `update` 可能悄悄改类型。用只读查询核对：

| 列 | 实际类型 | 可空 | 与 V4 / 实体 |
|---|---|---|---|
| `id` | bigint | NO | ✓ |
| `book_id` | bigint | NO | ✓ |
| `snapshot_date` | date | NO | ✓ |
| `basis` | varchar(8) | NO | ✓ |
| `readers` | int | NO | ✓ |
| `profiled_users` | int | NO | ✓ |
| `sample_protected` | tinyint(1) | NO | ✓ |
| `payload` | longtext | YES | ✓（保护态为 NULL 的设计依赖它可空） |
| `created_at` | datetime(6) | NO | ✓ |

九列全部一致，唯一键 `uk_profile_daily` 与日期索引也在（附录 AC 已列）。也就是说：这张表在不同环境里由谁建都一样——Flyway 先建、`ddl-auto` 再看也没有可改的地方。

用到的仍是那个临时只读运行器（`%TEMP%`，未进仓库）。这条检查没有做成常驻脚本：它依赖一个连库的临时工具，而生产上的同类核对仍属 A.3 的范畴（同一连接、同一批只读查询）。

## 附录 AZ：提交后的干净检出验证（2026-10-25）

代码按功能拆成四笔提交后，用 `git archive HEAD` 导出到临时目录（不含任何未跟踪文件、不含本机产物），在那里重跑：

| 检查 | 结果 |
|---|---|
| `mvn -o test` | **247 例全绿**（三笔提交自洽，没有漏文件） |
| `profile-dto-parity` / `env-names` / `api-paths` | PASS |
| `doc-paths` | **FAIL 1 条**：`V3__add_note_occurred_at.sql` 不在版本控制里 |

这次验证抓到两件只有"干净检出"才看得见的事：

1. **部署指南让人执行的 `package/package.sh|bat` 根本不在仓库**（`.gitignore:39` 忽略了 `package/`）。它只是本机打包工具，指南已改为仓库内确实存在的流程：`deploy/download.sh`（或本机打包后拷入）→ `deploy/docker/build.sh` → `up -d`。`frontend/dist` 与这两个脚本在 `doc-paths` 里登记为"本机/构建产物"。
2. **`V3__add_note_occurred_at.sql` 未提交**。它属于笔记时光机那个功能（B.5 记过它修的是 `notes.occurred_at` 缺列导致的 500），不是本功能的文件，所以没有一并提交；但它在仓库里缺席意味着**全新部署会缺这一列**。这是本次提交范围之外的真实缺口，需要单独决定是否提交 V3。

结论：本功能的四笔提交在干净检出下后端 247 例全绿、三个静态核对通过；`doc-paths` 的唯一红项指向的是另一个功能的未提交迁移，不是本功能的断链。

## 附录 BA：`doc-paths` 现在区分"文件在磁盘上"与"在版本控制里"

AZ 记录的现象是：工作区 `doc-paths` 全绿，`git archive` 干净检出却红在 `V3__add_note_occurred_at.sql`——因为检查器只问"文件在不在磁盘上"。这类缺口最隐蔽：提交、发布、干净部署各看各的，谁都不报错。

现在 `doc-paths` 多了一段**不改变判定**的提示：在 git 工作树里逐条确认被引用路径是否被 git 跟踪，未跟踪的列在

```
未入库（本机有、干净检出会缺；不计入判定，但发布前必须处理）：
  ! backend/src/main/resources/db/migration/V3__add_note_occurred_at.sql
```

为什么不计入判定：这是**跨功能**的信息（V3 属笔记功能，不在本功能的提交范围），把它变成红会让本功能的验证长期处于"看起来坏了"的状态；但把它藏起来又会在发布时咬人。所以：工作区提示、干净检出真红、部署清单另有一条显式检查（`1bf8fc4`）。三处指向同一件事，任何一处被看到都能拦住它。

另外两个边界：不在 git 工作树时（archive 解出的目录）跳过这段——没有版本信息可查；`ALLOW` 里登记的本机工具（`package/*`、`frontend/dist`、`.verify-token.txt`）不重复提示。

### BA.1 干净检出的前端构建

同一份 `git archive HEAD` 导出里，给 `frontend/node_modules` 建一个目录联接（junction，指向工作区已有依赖），直接 `npm run build`：

```
✓ built in 12.21s   （tsc -b + vite build，exit 0）
```

说明三笔提交里的前端文件是自洽的：`components/admin/*`、`pages/admin/profile-books.tsx`、`api/adminDashboard.ts`、路由与常量没有漏提交。清理时本想用 `rmdir` 只拆联接（不跟随到目标），但命令报「文件名语法错误」；随后用 `rm -rf` 删父目录，联接本身随之消失。**事后核对工作区 `frontend/node_modules` 完好（633 个顶层包、构建再次通过），联接没有被跟随**——但这是一次靠结果验证而非靠操作保证的险情，记在这里：以后应先 `rmdir`（或用 PowerShell `Remove-Item` 指定 LinkType）确认链接已拆，再删父目录。

至此干净检出的三件事都有结论：后端 247 例测试全绿、前端构建通过、静态核对仅剩 V3 一条（属他功能、未提交，已在部署清单显式检查）。

## 附录 BB：交付状态快照（2026-10-25，供交接）

### BB.1 代码与提交

| 提交 | 内容 |
|---|---|
| `0146552` | 后端：口径/隐私/缓存代号/吻合度/每日快照/写端校验/索引 + 247 例测试 |
| `3527ce9` | 前端：画像抽屉（分布/吻合度/趋势/折叠）、人群选书页、回显归一化 |
| `a7cd6dd` | 设计文档 + 验证矩阵/静态脚本 + 阈值审计 SQL + 部署清单 |
| `11573f9` / `1bf8fc4` | 打包流程改仓库内脚本；发布清单增加 V3 检查项 |
| `02914d7` / `d5006a3` / `5970558` / `78ae245` / `795f833` / `58fdcd6` | doc-paths 边界与未入库提示、干净检出记录与更正 |

### BB.2 验证现状（全部可重跑）

| 层 | 命令 | 结果 |
|---|---|---|
| 单测 | `mvn -o test` | 247 例全绿（干净检出同样是 236） |
| 构建 | `npm run build` | 通过（干净检出用联接依赖跑过，12.21s） |
| 接口 | `deploy/verify/admin-profile-matrix.mjs` | 26/26（两实例 + ADMIN/USER token，真值自动统计） |
| 静态 | `deploy/verify/run-static.mjs` | 4/4（契约、文档路径、清单 env、接口路径） |
| 视口 | 折叠 11 / 首屏 5 / 吻合度 8 / 趋势 6 / 反查页 6+5 | 全绿（临时脚本在 `%TEMP%`） |

### BB.3 收口只剩两步

1. **V3 迁移提交**：`backend/src/main/resources/db/migration/V3__add_note_occurred_at.sql` 属笔记功能、当前未入库；缺它在全新部署会让笔记时光机 500。发布清单已显式检查，但要彻底关掉干净检出的红项，需要单独提交这个文件。
2. **A.3 生产校准**：`deploy/sql/profile-threshold-audit.sql`（19 条语句、含 3/5/10/20 阈值扫描）已在 dev 端到端验证；生产上跑一次，再把四条阈值与 `KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_*` 写进部署清单，按附录 AC 的两条 `EXPLAIN` 复跑索引确认。

这两步都需要仓库外的输入（一个提交决定、一个生产只读连接）。在此之前，本功能的所有代码、文档与证据均已入库，构建与验证在干净检出下可复现。

## 附录 BC：矩阵第 15 行不再依赖人工真值（2026-10-25）

「看板画像与读者画像同口径」需要"库内 APPROVED 男性人数"作真值，之前只能由人手工传 `--db-male`，不传就 SKIP。现在矩阵自己在没有该参数时从 `/admin/users` 分页统计（每页 500、最多 50 页），并在明细里标明来源：

```
[PASS] L 看板画像与读者画像同口径
       genderDistribution={"MALE":4,...} siteReaders=12 求和=12
       库内 APPROVED 男=4（来源：/admin/users 分页（APPROVED=12）） 非白名单键=[]
```

为什么这条统计可以当真值：它走的是用户管理的 JPA 查询，与画像的 bucket SQL 不同源，足以交叉验证"看板的 MALE 数 == 库里的 MALE 数"；同时 `求和 == siteReaders` 保证了分母一致。`--db-male` 仍然保留——需要严格 SQL 真值（例如生产上核对）时可以显式传入，此时来源显示为 `--db-male`。

带着这个改动连跑矩阵：**26/26，0 SKIP**（此前该行缺参数时会 SKIP）。这样矩阵的前置条件只剩"两个实例 + 两种 token"，把"忘了传真值"这一类人为疏漏从验证流程里移除了。

### BC.1 真值分页的上限与降级

自动统计真值也有边界：用户量大时不能没完没了翻页，也不能拿"截断的统计"去判 FAIL。规则定成：

- 每页 500、最多 50 页（2.5 万用户）；
- 触到上限仍未翻完 → 判定**真值不完整**，该行降级为 SKIP 并在明细里写明"/admin/users 分页达上限，真值不完整"，而不是拿少算的男性数去比较；
- 正常翻完 → PASS/FAIL 按真值判；显式 `--db-male` 始终优先。

带守卫复跑：**26/26，0 SKIP**（dev 12 个 APPROVED 一页翻完）。这条与 AU/AV 的思路一致：**验证工具自己也要有"看不清时承认看不清"的路径**，否则它给出的红绿都不可信。

## 附录 BD：HEAD 缺 V3 不是文档问题，是部署故障（2026-10-25 取证）

此前记的"V3 未入库"只是 `doc-paths` 的一条红。取证后确认它更严重：

```
git grep occurred_at HEAD -- backend/src/main
  Note.java:91   @Column(name = "occurred_at", nullable = false)
  Note.java:29   @Index(name = "idx_note_user_occurred", columnList = "user_id,occurred_at")
git ls-tree HEAD backend/src/main/resources/db/migration/
  V1__create_ai_usage_log.sql
  V2__ai_cost_governance.sql
  V4__create_book_profile_daily.sql
  fix_debate_score_role_key.sql          ← 没有 V3
```

也就是说**当前 HEAD 自相矛盾**：已提交的实体声明了一个 NOT NULL 列，而创建它的迁移没提交。全新部署时：

1. Flyway 只跑 V1/V2/V4；
2. `ddl-auto: update` 试图给有存量数据的 `notes` 表加 `occurred_at NOT NULL`——B.5 记过，这一步会被 MySQL 严格模式拒绝，且**连带索引一起丢掉**；
3. 笔记时光机/统计接口 500，而编译、单测、`mvn package` 全都正常。

这正是"提交拆分"带来的一个真实副作用：V3 属笔记功能，未被纳入本功能的提交，但本功能的文档引用了它，干净检出的静态核对把它揪了出来。

处理：**本功能不擅自提交他功能文件**。已把问题写进部署清单（`1bf8fc4`）与这里。建议单独提交 V3（`fix(notes): 补提交 notes.occurred_at 迁移，此前遗漏`），一行命令即可，随后干净检出的最后一条红项消失、HEAD 恢复自洽。

## 附录 BE：把 A.3 的判定规则做进审计脚本（1f）

A.3 的原文规则是："达 `min-cohort-size` 的书不足 20% → 阈值过高，应降档或改做人群选书"。脚本此前只输出分布与扫描表，**20% 这条线要人自己算**。补 1f：直接输出每个口径的"最小达标档位"与建议：

```sql
WITH per_book AS (三口径每本书的读者数), sweep AS (× 3/5/10/20 的覆盖率)
SELECT basis,
       COALESCE(MIN(CASE WHEN pct >= 20 THEN min_cohort END), '无') AS 建议_min_cohort,
       CASE WHEN ... IS NULL THEN '没有任何档位达到 20%：按 4.1 第 4 条先做「人群选书」'
            ELSE CONCAT('采用最小达标档位 ', ...) END AS 建议
```

dev 实跑（20 条语句、0 失败）：

| basis | 建议_min_cohort | 建议 |
|---|---|---|
| DEEP / READ / TOUCH | 无 | 没有任何档位达到 20%：按 4.1 第 4 条先做「人群选书」 |

与 A.3 的预期一致——dev 不是阈值选高了，是没有读者基数。生产上 1f 会直接给出"取 3/5/10/20 中的哪一档"；正分支（存在 ≥20% 的档位）在 dev 上不可能触发，只能到生产验证，这也是 A.3 仍未关闭的原因。

规则只写在一处：阈值档位（3/5/10/20）与 20% 线都写在 1f 的 SQL 里，并与 §4.1 的原文同源；将来改规则要同时改文档与这里。

## 附录 BF：规则表与白名单的跨表不变量第一次有测试

`TargetAudienceRules`（书侧标签 → 画像桶）与 `BookProfileDimension`（各维度白名单）是两张手写表。规则里写错一个 token——比如把 `HAS_CHILD` 写成 `HAS_CHILDREN`——**不会抛异常**：归一化把它当脏值，于是吻合度那一维永远 `NO_DATA`，页面上只表现为"没数据"，没人会去怀疑规则表。反向也一样（白名单改名、规则没跟）。

新增 `TargetAudienceRulesWhitelistTest`：反射遍历规则表的每个静态 `Rule` 列表（不依赖枚举），断言 (维度, token) 在白名单里、关键词非空，并要求至少读到 20 条规则（防止反射失灵导致空跑）。当前 **26 条规则全部通过**，`mvn -o test` **247 例**。

变异验证：把「已婚」规则的 token 改成 `MARRIED_X` → 测试以一句可读的话变红：

```
规则「已婚」指向 MARITAL 的 token「MARRIED_X」，但该 token 不在白名单里（会被归一化成脏值）
```

还原后回到全绿。这条与 AU/AV 属同一族：**两个手写表之间的一致性，必须有一条会咬的断言**。

## 附录 BG：维度映射的域测试被"清洗层"遮蔽，需要黄金值

起因：怀疑年龄桶表写错会静默变脏值，于是加了一条域断言——15 个维度、85 个样本（历史别名/脏值/空值/null），断言 `toToken(x) ∈ 白名单 ∪ {未填写, 脏值}`。

**变异证明它是空断言**：把 `ageToken` 的 `"31-40"` 改成 `"31-45"`，测试仍然全绿。原因不在断言写法，而在实现结构：`toToken` → `toTokens`，后者会把白名单外的 token **洗成 `脏值`**——所以"域内"恒成立，内层表写错的表现恰恰是"变成脏值"，域断言看不出来。

补一条**黄金表**测试 `ageBucketsAreExact`：`0/17→<18`、`18/24→18-24`、`25/30→25-30`、`31/40→31-40`、`41/50→41-50`、`51/120→>50`、`-1→脏值`。同一变异现在会红：

```
[AGE.toToken(31) 应为 31-40（若内层桶名写错，会被洗成脏值）]
```

两条测试的分工写清楚：域断言守"对外契约不越界"，黄金表守"表本身没写错"。前者是**必要但不充分**，后者才咬得住内层错误。`mvn -o test` **247 例**。

这条与 BF 同族：手写表之间/表与消费方的一致性，只靠"结果仍在合法集合里"不够，必须钉具体取值。

## 附录 BH：布尔维度（婚姻/子女）此前没有任何直接取值断言

BG 教会我们"域断言会被清洗层遮蔽"，于是按维度检查谁还只有域断言。统计结果：`CHILDREN` 的直接取值断言是 **0**（`MARITAL` 有 5 条）。也就是说，`boolToken(raw, "HAS_CHILD", "NO_CHILD")` 的两个桶写反、或桶名拼错，没有任何测试会红——而被清洗成脏值后，"有孩子"的读者会从该维度的有效样本里消失，页面只表现为比例变小。而 P3 的标签映射恰好大量依赖 `CHILDREN → HAS_CHILD`。

补 `booleanDimensionsAreExact`：`1/true → HAS_CHILD`、`0/false → NO_CHILD`、`2 → 脏值`、空值 → 未填写；`MARITAL` 同表；并断言 `CHILDREN.getAllowed()` 里确实有 `HAS_CHILD` / `NO_CHILD` 两个键（规则表指向的就是它们）。

变异：把 `case CHILDREN -> boolToken(raw, "HAS_CHILD", "NO_CHILD")` 的两个桶互换 → 测试红（`expected: "HAS_CHILD" but was: "NO_CHILD"`），还原后全绿。`mvn -o test` **247 例**。

至此 15 个维度全部有直接取值断言：GENDER/AGE/MBTI_TYPE/MBTI 四条轴/CHILD_AGE/OCCUPATION/EDUCATION/INCOME/ENTREPRENEURSHIP/CHAT_STYLE 原本已覆盖，`MARITAL`/`CHILDREN` 由本轮补齐。（原文写「11 个」是笔误：枚举一共 15 个，此处更正。）

## 附录 BI：四个阈值默认值第一次有测试

§4.1 的四条阈值（cohort 10 / bucket 5 / significant 10 / highlight 20）此前只在文档与配置里，**没有任何测试断言它们的默认值**。这四个数是隐私闸门：默认值被改错不会报错，只会悄悄多给或少给分布——比 TTL 更敏感。

新增 `BookProfileThresholdConfigTest`：

1. 四个 `@Value` 的兜底字符串必须逐字等于 `${kbook.admin.book-profile.<key>:10/5/10/20}`；
2. **application.yml 里真正生效的默认值**必须与注解兜底一致、属性路径末段与 yml 键一致、env 名与部署清单一致（与附录 R 对 TTL 的做法同构）。

因为生效的是 yml（属性存在时注解兜底不参与），只测注解等于没测——变异就是对着这一点做的：把 yml 的 `min-cohort-size` 默认从 10 改成 12 → 测试红（`yml 的默认值与注解兜底不同：§4.1 的数字只剩文档意义`），还原后全绿。`mvn -o test` **247 例**。

至此，"配置项 ↔ 文档数字 ↔ 部署清单 env"这条链在阈值与 TTL 两类键上都有测试守着；新增配置项时照这个模板补即可。

## 附录 BJ：快照的四个默认值有测试了，其中"默认关闭"是安全默认

`BookProfileSnapshotConfigTest` 断言：`snapshot-enabled` 默认 **false**、`snapshot-bases` 默认 DEEP、`snapshot-max-books` 默认 500、`@Scheduled` 的 cron 默认 `0 10 3 * * *`。

为什么单独立这条：这四项**只存在于 `@Value`**（没有 yml 条目），此前没有任何测试；而 `snapshot-enabled` 写成 true 意味着生产一启动就每晚自动写库，**不报错、不提示**，只会在磁盘和数据库里留下痕迹。变异把 `:false` 改成 `:true` → 测试红，还原后全绿。

env 名由 `env-names.mjs`（AU）核对，默认值由本测试核对，两者分工不重叠。`mvn -o test` **247 例**。

至此三类配置都有守护：阈值（BI，yml+注解+env）、TTL（R）、快照（BJ，仅注解 + AU 的 env 名）。

## 附录 BK：代码常量与文档数字的对应关系有了断言

有三个数字会被接口或错误文案直接暴露，但此前只是代码里的常量：

| 常量 | 值 | 文档出处 |
|---|---|---|
| `BookProfileService.MAX_QUERY_LIMIT` | 50 | §6.2「limit 1..50」 |
| `AdminDashboardService.MAX_CUSTOM_RANGE_DAYS` | 366 | §2.2 报错文案里的天数 |
| `BookProfileService.ID_CHUNK_SIZE` | 2000 | 与 §9 的「IN 超过 5000 改子查询」配套 |

新增 `BookProfileLimitsTest`：前两个必须等于文档数字；第三个除了等于 2000，还断言 **< 5000**——§9 的"单条 SQL 可行"这个结论成立的前提就是分片小于该阈值，分片被人调大时这条会红，避免文档里的性能判断悄悄失效。

变异：把 `MAX_QUERY_LIMIT` 改成 100 → 红（`接口契约写的是 1..50，改常量等于改契约`），还原后全绿。`mvn -o test` **247 例**。

至此"文档里的数字"在代码侧都有对应断言：阈值默认值（BI）、TTL（R）、快照默认值（BJ）、接口上限与分片（BK）。

### BE.1 正分支的验证（不碰生产）

1f 的"存在达标档位"分支在 dev 上永不触发（各口径最高 0.92% < 20%）。用一个**临时副本**把 20% 改成 0.5% 后实跑（不写库、不改仓库脚本，文件只在 `%TEMP%`）：

| basis | 建议_min_cohort | 建议 |
|---|---|---|
| DEEP | 3 | 采用最小达标档位 3 |
| TOUCH | 3 | 采用最小达标档位 3 |
| READ | 无 | 没有任何档位达到 20%：按 4.1 第 4 条先做「人群选书」 |

两个分支、`COALESCE` 的 '无' 与 `CONCAT` 的文案都按预期输出。**注意这只是分支可达性的验证**：0.5% 是人为阈值，不代表任何真实结论；正式判定仍要按 20% 在生产数据上跑。
