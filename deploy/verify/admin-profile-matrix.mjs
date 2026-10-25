// 管理端「图书 × 读者画像」接口矩阵：把设计文档 §11（测试计划）与 §4（隐私硬性要求）
// 的 14 条取证固化成可重跑脚本，避免每轮验证重新发明断言（附录 I.5 的三个假 PASS 就是这么来的）。
//
//   node deploy/verify/admin-profile-matrix.mjs \
//     --base=http://127.0.0.1:8300 \
//     --loose=http://127.0.0.1:8301 \
//     --token-file=$TEMP/kbook-verify/.tok --token-user-file=$TEMP/kbook-verify/.tok-user
//
// 退出码：0=全绿；1=有 FAIL（真回归，读明细）；2=前置不满足（矩阵没跑，别读结论）。
// 把「环境不对」和「行为回归」分成两个退出码是本脚本唯一的硬规矩：附录 I.5 记过一次，
// 全 401 的环境会让「响应无标识符」这类否定式断言虚假变绿。
//
// --base 需要默认阈值（min-cohort=10 / min-bucket=5）；--loose 需要 KBOOK_PROFILE_MIN_COHORT_SIZE=1
// 且 bucket 仍为 5——小桶并「其他」与出榜下限两条只在 loose 实例上观测得到。
// 全程只读 GET，不写库。token 走文件而不是 argv（Windows 进程列表会暴露命令行）。
import fs from 'node:fs'

const args = Object.fromEntries(process.argv.slice(2).map(a => {
  const i = a.indexOf('=')
  return [a.slice(2, i), a.slice(i + 1)]
}))
const readTok = flag => {
  const file = args[flag]
  if (file) return fs.readFileSync(file, 'utf8').trim()
  return process.env[flag === 'token-file' ? 'KBOOK_VERIFY_TOKEN' : 'KBOOK_VERIFY_TOKEN_USER'] || null
}
const BASE = args.base || 'http://127.0.0.1:8181/api/admin/dashboard'
const LOOSE = args.loose || null
const ADMIN = readTok('token-file')
const USER = readTok('token-user-file')
// 样本 id 属于 dev 库（附录 A.1）。换数据集时改这两个参数，不要改断言。
const BOOK = args.book || '24715'
const PROTECTED_BOOK = args['protected-book'] || '12638'

const results = []
const prereqs = []
// ok === null 是 SKIP，不是 PASS：需要外部真值而真值没给的行，不许混进全绿里。
const row = (id, ok, detail) => results.push([id, ok === null ? 'SKIP' : ok ? 'PASS' : 'FAIL', detail])
const missing = reason => { prereqs.push(reason); throw new Error('PREREQ') }

const call = async (base, path, tok) => {
  const r = await fetch(base + path, tok === undefined ? {} : { headers: { Authorization: 'Bearer ' + (tok || '') } })
  const text = await r.text()
  let j = null
  try { j = JSON.parse(text) } catch { /* 非 JSON 响应交给各行的 status/message 判定 */ }
  return { status: r.status, j, text }
}

const finish = code => {
  console.log(`base=${BASE}${LOOSE ? ' loose=' + LOOSE : ' loose=<未提供>'}`)
  for (const [id, st, detail] of results) console.log(`\n[${st}] ${id}\n      ${detail}`)
  const fails = results.filter(r => r[1] === 'FAIL').length
  const skips = results.filter(r => r[1] === 'SKIP').length
  console.log(`\nFAIL 数 = ${fails} / ${results.length}${skips ? `  （另有 ${skips} 行 SKIP，需补外部真值）` : ''}`)
  if (prereqs.length) console.log('前置不满足：\n  - ' + prereqs.join('\n  - '))
  // 不能就地 process.exit()：fetch 的连接池还在关闭中，Windows 下会撞 libuv 断言并把退出码变成 127。
  // 先挂 exitCode 让事件循环自然排空；再放一个 unref 的定时器兜住不排空的情况。
  process.exitCode = code
  setTimeout(() => process.exit(code), 500).unref()
}

;(async () => {
  if (!ADMIN) missing('没有 ADMIN token。--token-file 指一份自签 HS256 token；'
    + 'secret 必须取当前 active profile 那份（application-dev.yml 会覆盖 application.yml），签错就是全线 401')
  if (!USER) missing('没有 USER token，§4-6 的 403 一支无法判定')
  if (!LOOSE) missing('没有 --loose 实例（KBOOK_PROFILE_MIN_COHORT_SIZE=1），小桶并「其他」与出榜下限两条无法观测')

  // 前置一：token 真的可用（401 会让下面的否定式断言虚假变绿）
  const me = await fetch(BASE.replace(/\/admin\/dashboard$/, '/user/me'),
    { headers: { Authorization: 'Bearer ' + ADMIN } }).then(r => r.json()).catch(() => null)
  if (!me || me.code !== 0) missing(`ADMIN token 打 /api/user/me 得到 ${JSON.stringify(me && me.message)}，不是 code:0`)
  const meUser = await fetch(BASE.replace(/\/admin\/dashboard$/, '/user/me'),
    { headers: { Authorization: 'Bearer ' + USER } }).then(r => r.json()).catch(() => null)
  if (!meUser || meUser.code !== 0) missing(`USER token 打 /api/user/me 得到 ${JSON.stringify(meUser && meUser.message)}，§4-6 需要它能通过认证`)

  // 前置二：loose 实例的闸门真的被调低了。判据不能用"有读者的书"——它在默认阈值下本来就不是保护态，
  // 于是把严格实例当 loose 传也会通过（本轮实测踩过：14 行全绿，而 §4-2/3 那两行其实什么都没观测到）。
  // 用 protected-book 反着证：同一本书在 base 上是保护态、在 loose 上必须变成非保护态。
  const visible = await call(LOOSE, `/book-profile?bookId=${BOOK}&basis=DEEP&days=0`, ADMIN)
  if (!visible.j || visible.j.code !== 0) {
    missing(`loose 实例上 bookId=${BOOK} 返回 ${JSON.stringify(visible.j && visible.j.message)}，换 --book 指向一本有读者的书`)
  }
  const strictProbe = await call(BASE, `/book-profile?bookId=${PROTECTED_BOOK}&basis=TOUCH&days=0`, ADMIN)
  const looseProbe = await call(LOOSE, `/book-profile?bookId=${PROTECTED_BOOK}&basis=TOUCH&days=0`, ADMIN)
  const strictProt = strictProbe.j && strictProbe.j.data && strictProbe.j.data.sampleProtected
  const looseProt = looseProbe.j && looseProbe.j.data && looseProbe.j.data.sampleProtected
  if (!strictProt) missing(`base 实例上 bookId=${PROTECTED_BOOK} 不是保护态，换 --protected-book 指向读者少于 min-cohort-size 的书`)
  if (looseProt) missing(`loose 实例上 bookId=${PROTECTED_BOOK} 仍是保护态，min-cohort-size 没降到 1（把严格实例当 --loose 传就是这个症状）`)

  // ---------- 时间窗契约（§2.2）：自定义区间回显与全部时间 ----------
  const custom = await call(BASE, `/book-profile?bookId=${BOOK}&basis=DEEP&start=2026-10-01&end=2026-10-10`, ADMIN)
  const c = custom.j && custom.j.data
  row('§2.2 自定义区间：rangeDays=10 且起止日回显',
    !!c && c.rangeDays === 10 && c.rangeStart === '2026-10-01' && c.rangeEnd === '2026-10-10',
    c ? `rangeDays=${c.rangeDays} ${c.rangeStart}~${c.rangeEnd}` : '无数据')

  const allTime = await call(BASE, `/book-profile?bookId=${BOOK}&basis=DEEP&days=0`, ADMIN)
  const at = allTime.j && allTime.j.data
  row('§2.2 days=0：rangeDays=0 且起止为 null（全部时间）',
    !!at && at.rangeDays === 0 && at.rangeStart === null && at.rangeEnd === null,
    at ? `rangeDays=${at.rangeDays} start=${at.rangeStart} end=${at.rangeEnd}` : '无数据')

  const inverted = await call(BASE, `/book-profile?bookId=${BOOK}&basis=DEEP&start=2026-10-10&end=2026-10-01`, ADMIN)
  row('§2.2 start>end：业务错误而不是静默换序',
    !!inverted.j && inverted.j.code !== 0 && /开始日期不能晚于结束日期/.test(inverted.j.message || ''),
    `code=${inverted.j && inverted.j.code} message=${inverted.j && inverted.j.message}`)

  // ---------- §11 接口层 ----------
  const gone = await call(BASE, '/book-profile?bookId=&basis=READ&days=0', ADMIN)
  const nodays = await call(BASE, `/book-profile?bookId=${BOOK}&basis=READ`, ADMIN)
  const badbasis = await call(BASE, `/book-profile?bookId=${BOOK}&basis=%7B%7B7*7%7D%7D&days=0`, ADMIN)
  row('§11 入参笔误给可读的 400 而不是 500 兜底',
    gone.status === 400 && /bookId/.test((gone.j && gone.j.message) || '') && badbasis.status === 200
    && /口径/.test((badbasis.j && badbasis.j.message) || ''),
    '空 bookId=HTTP ' + gone.status + ' ' + JSON.stringify(gone.j && gone.j.message)
    + ' ｜ 脏 basis=HTTP ' + badbasis.status + ' ' + JSON.stringify(badbasis.j && badbasis.j.message)
    + ' ｜ 缺 days 有默认值=HTTP ' + nodays.status)

  const missingBook = await call(BASE, '/book-profile?bookId=999999&basis=READ&days=0', ADMIN)
  row('§11 不存在的 bookId', missingBook.j && missingBook.j.code !== 0 && /不存在/.test(missingBook.j.message),
    JSON.stringify(missingBook.j && missingBook.j.message))

  const far = { start: '2000-01-01', end: '2026-10-01' }
  const prof = await call(BASE, `/book-profile?bookId=${BOOK}&basis=READ&start=${far.start}&end=${far.end}`, ADMIN)
  const dash = await call(BASE, `?start=${far.start}&end=${far.end}`, ADMIN)
  const m1 = prof.j && prof.j.message, m2 = dash.j && dash.j.message
  row('§11 跨度>366 文案与主看板一致', !!m1 && !!m2 && m1 === m2, JSON.stringify(m1) + ' vs ' + JSON.stringify(m2))

  // days 与 start/end 同给：以 start/end 为准（附录 G.9 已下沉为单测，这里仍留接口层证据）
  const both = await call(LOOSE, `/book-profile?bookId=${BOOK}&basis=DEEP&days=0&start=2026-10-20&end=2026-10-24`, ADMIN)
  const onlyRange = await call(LOOSE, `/book-profile?bookId=${BOOK}&basis=DEEP&start=2026-10-20&end=2026-10-24`, ADMIN)
  const onlyDays = await call(LOOSE, `/book-profile?bookId=${BOOK}&basis=DEEP&days=0`, ADMIN)
  const key = j => JSON.stringify(j && j.data && {
    rangeDays: j.data.rangeDays, rangeStart: j.data.rangeStart, rangeEnd: j.data.rangeEnd,
    readers: j.data.readers, dims: (j.data.dimensions || []).length, prot: j.data.sampleProtected,
  })
  if (key(both.j) === undefined || key(onlyRange.j) === undefined) missing('优先级三条请求没拿到 data，区间字段无法比较')
  // 三条必须「两两可能不同」：同区间 vs days=0 必须真的不同，否则这条 PASS 是假的（附录 I.5 第 3 条）
  row('§11 days+start/end 同给以 start/end 为准',
    key(both.j) === key(onlyRange.j) && key(both.j) !== key(onlyDays.j),
    '同区间=' + (key(both.j) === key(onlyRange.j)) + ' 与 days=0 不同=' + (key(both.j) !== key(onlyDays.j))
    + ' || bothData=' + key(both.j) + ' || daysData=' + key(onlyDays.j))

  const store = await call(LOOSE, '/profile-books?dim=GENDER&value=MALE&days=0&basis=READ&limit=10', ADMIN)
  const label = await call(LOOSE, '/profile-books?dim=GENDER&value=' + encodeURIComponent('男') + '&days=0&basis=READ&limit=10', ADMIN)
  row('§11 反查存储值命中、展示名返回空而非 500',
    store.j && store.j.code === 0 && label.j.code === 0 && Array.isArray(label.j.data) && label.j.data.length === 0,
    'MALE 行数=' + ((store.j && store.j.data) || []).length + '，「男」=' + JSON.stringify(label.j && label.j.data))

  const badDim = await call(BASE, '/profile-books?dim=NOPE&value=X&days=0', ADMIN)
  row('§11 非法维度硬失败并给出可选值', badDim.j && badDim.j.code !== 0 && /book-profile/.test(badDim.j.message),
    JSON.stringify(badDim.j && badDim.j.message))

  const basisReaders = {}
  for (const b of ['READ', 'TOUCH', 'DEEP']) {
    const r = await call(LOOSE, `/book-profile?bookId=${BOOK}&basis=${b}&days=0`, ADMIN)
    basisReaders[b] = r.j && r.j.data && r.j.data.readers
  }
  // 第 2.1 节：实测已推翻 basis 之间的单调关系，这里只断言三口径各自可取数
  row('§11 三种 basis 各自可用（不断言单调）',
    Object.values(basisReaders).every(v => typeof v === 'number'), JSON.stringify(basisReaders))

  // ---------- §4 隐私硬要求 ----------
  const EPS = ['', '/budget', `/book-profile?bookId=${BOOK}&basis=DEEP&days=0`,
    '/profile-books?dim=GENDER&value=MALE&days=0&basis=DEEP', '/profile-dimensions']
  let gateOk = true
  const gateDetail = []
  for (const p of EPS) {
    const none = await call(BASE, p, null)
    const u = await call(BASE, p, USER)
    const a = await call(BASE, p, ADMIN)
    gateDetail.push(`${(p.split('?')[0] || 'dashboard')}:${none.status}/${u.status}/${a.status}`)
    if (!(none.status === 401 && u.status === 403 && a.status === 200)) gateOk = false
  }
  row('§4-6 五个端点 无token=401 / USER=403 / ADMIN=200', gateOk, gateDetail.join(' '))

  const protBook = await call(BASE, `/book-profile?bookId=${PROTECTED_BOOK}&basis=TOUCH&days=0`, ADMIN)
  const pd = (protBook.j && protBook.j.data) || {}
  const allowedKeys = ['bookId', 'bookTitle', 'basis', 'rangeDays', 'rangeStart', 'rangeEnd', 'readers',
    'minCohortSize', 'sampleProtected', 'profiledUsers', 'siteReaders', 'basisNote', 'dimensions', 'highlights']
  const extra = Object.keys(pd).filter(k => !allowedKeys.includes(k))
  row('§4-1 保护态只给总数与阈值（无分桶）',
    pd.sampleProtected === true && (pd.dimensions || []).length === 0 && (pd.highlights || []).length === 0 && extra.length === 0,
    'readers=' + pd.readers + ' 未知字段=' + JSON.stringify(extra) + ' dimensions=' + JSON.stringify(pd.dimensions))

  const dist = await call(LOOSE, `/book-profile?bookId=${BOOK}&basis=DEEP&days=0`, ADMIN)
  const dd = (dist.j && dist.j.data) || {}
  if ((dd.dimensions || []).length === 0) missing(`loose 实例上 bookId=${BOOK} 没有任何维度分桶，§4-2/3 无观测对象`)
  let mergedNoLift = false
  const smallKept = []
  for (const d of dd.dimensions || []) {
    for (const b of d.buckets) {
      if (b.label === '其他') mergedNoLift = mergedNoLift || (b.lift === null && b.significant === false)
      if (b.label !== '其他' && b.count < 5) smallKept.push(d.key + '/' + b.label + '=' + b.count)
    }
  }
  row('§4-2/3 小桶并进「其他」且「其他」无 lift', mergedNoLift && smallKept.length === 0,
    '维度数=' + (dd.dimensions || []).length + ' 违规小桶=' + JSON.stringify(smallKept))

  const keys = new Set()
  const walk = n => {
    if (Array.isArray(n)) return n.forEach(walk)
    if (n && typeof n === 'object') return Object.entries(n).forEach(([k, v]) => { keys.add(k); walk(v) })
  }
  for (const p of [`/book-profile?bookId=${BOOK}&basis=DEEP&days=0`,
    '/profile-books?dim=GENDER&value=MALE&days=0&basis=DEEP&limit=20', '/profile-dimensions']) {
    walk((await call(LOOSE, p, ADMIN)).j)
  }
  const DENY = /user_?id|userid|user_?ids|userids|reader_?ids|nickname|nick_name|email|phone|mobile|avatar|password|token/i
  const leaks = [...keys].filter(k => DENY.test(k))
  row('§4-4 响应无个体级标识符键', leaks.length === 0, '命中=' + JSON.stringify(leaks) + ' 键总数=' + keys.size)

  const single = await call(LOOSE, `/profile-books?dim=GENDER&value=FEMALE&days=0&basis=DEEP&limit=20`, ADMIN)
  const comma = await call(LOOSE, '/profile-books?dim=GENDER,AGE&values=FEMALE,31-40&days=0&basis=DEEP', ADMIN)
  const repeated = await call(LOOSE, '/profile-books?dim=GENDER&dim=AGE&value=FEMALE&value=31-40&days=0&basis=DEEP', ADMIN)
  const ignored = await call(LOOSE, `/profile-books?dim=GENDER&value=FEMALE&days=0&basis=DEEP&limit=20&dim2=AGE&second=31-40`, ADMIN)
  row('§4-4 AND 下钻不可行（未知参数忽略、多值/重复参数硬失败）',
    JSON.stringify(ignored.j) === JSON.stringify(single.j) && comma.j.code !== 0 && repeated.j.code !== 0,
    '未知参数被忽略=' + (JSON.stringify(ignored.j) === JSON.stringify(single.j))
    + ' 逗号=' + JSON.stringify(comma.j.message) + ' 重复=' + JSON.stringify(repeated.j.message))

  const PAIRS = [['GENDER', 'FEMALE'], ['GENDER', 'MALE'], ['AGE', '31-40'], ['AGE', '25-30'],
    ['MBTI_EI', 'E'], ['MBTI_EI', 'I'], ['MARITAL', 'MARRIED'], ['CHILDREN', 'NO_CHILD'],
    ['CHAT_STYLE', 'DEEP'], ['CHAT_STYLE', 'WITTY'], ['MBTI_TYPE', 'INFP']]
  let rows = 0, minCount = Infinity
  const viol = [], memberMismatch = []
  for (const [dim, value] of PAIRS) {
    for (const basis of ['READ', 'TOUCH', 'DEEP']) {
      const q = `/profile-books?dim=${dim}&value=${value}&days=0&basis=${basis}&limit=50`
      const a = ((await call(LOOSE, q + '&sortBy=READERS', ADMIN)).j || {}).data || []
      const b = ((await call(LOOSE, q + '&sortBy=LIFT', ADMIN)).j || {}).data || []
      rows += a.length + b.length
      const setA = a.map(x => x.bookId).sort().join(',')
      const setB = b.map(x => x.bookId).sort().join(',')
      if (setA !== setB) memberMismatch.push(`${dim}/${basis}: ${setA} vs ${setB}`)
      for (const x of [...a, ...b]) {
        minCount = Math.min(minCount, x.count)
        if (x.count < 5) viol.push(`${dim}/${value}/${basis} 《${x.title}》 count=${x.count} readers=${x.readers}`)
      }
    }
  }
  row('§4-5 出榜下限=5 且与 sortBy 无关', viol.length === 0 && memberMismatch.length === 0,
    '总行数=' + rows + ' 最小 count=' + (minCount === Infinity ? 'n/a' : minCount)
    + ' 泄漏=' + JSON.stringify(viol.slice(0, 3)) + ' 成员差异=' + JSON.stringify(memberMismatch.slice(0, 3)))

  // 附录 L：看板的「全站用户画像」区块要和读者画像同口径——只算 APPROVED、脏值不上桶标签、
  // 未填写单独成行。逐桶人数必须对得上库里的真值：优先 --db-male（只读 SQL），缺省时从 /admin/users 分页统计；
  // 不传就 SKIP。dev 上旧实现各桶之和恰好等于 siteReaders（空值全被 IS NOT NULL 丢掉），
  // 只比 HTTP 自洽是抓不到它的——这正是那条恒绿断言的翻版。
  const dashL = await call(BASE, '?days=0', ADMIN)
  const genderL = ((dashL.j && dashL.j.data && dashL.j.data.userProfile) || {}).genderDistribution || {}
  const profSite = await call(BASE, `/book-profile?bookId=${PROTECTED_BOOK}&basis=DEEP&days=0`, ADMIN)
  const siteReaders = profSite.j && profSite.j.data && profSite.j.data.siteReaders
  const oddKeys = Object.entries(genderL)
    .filter(([k]) => k !== '未填写' && k !== '脏值' && !['MALE', 'FEMALE', 'OTHER'].includes(k))
    .map(([k]) => k)
  const genderTotal = Object.values(genderL).reduce((s, n) => s + n, 0)
  // 真值来源：优先 --db-male（只读 SQL），否则从 /admin/users 分页统计。
  // 后者走的是用户管理的 JPA 路径，与画像的聚合 SQL 不同源，足以交叉验证；分页上限 10 页 × 500。
  let dbMale = args['db-male']
  let dbMaleSource = '--db-male'
  let truthTruncated = false
  if (!dbMale) {
    const MAX_PAGES = 50 // 2.5 万用户；超过就判定"真值不完整"，宁可 SKIP 也不拿截断的真值去判 FAIL
    let page = 1
    let approved = 0
    let male = 0
    while (page <= MAX_PAGES) {
      const res = await fetch(BASE.replace(/\/admin\/dashboard$/, `/admin/users?page=${page}&size=500`),
        { headers: { Authorization: 'Bearer ' + ADMIN } }).then(r => r.json()).catch(() => null)
      const rows = (res && res.data && (res.data.records || res.data.list || res.data.content)) || []
      if (!rows.length) break
      for (const u of rows) {
        if (u.status !== 'APPROVED') continue
        approved++
        if (String(u.gender ?? '').trim().toUpperCase() === 'MALE') male++
      }
      if (rows.length < 500) break
      page++
    }
    truthTruncated = page > MAX_PAGES
    if (approved > 0 && !truthTruncated) {
      dbMale = String(male)
      dbMaleSource = `/admin/users 分页（APPROVED=${approved}）`
    }
  }
  const detailL = `genderDistribution=${JSON.stringify(genderL)} siteReaders=${siteReaders} 求和=${genderTotal}`
  if (!dbMale) {
    row('L 看板画像同口径（SKIP：缺真值）', null, detailL
      + (truthTruncated ? '；/admin/users 分页达上限，真值不完整' : '')
      + "；取真值：SELECT COUNT(*) FROM users WHERE status='APPROVED' AND TRIM(IFNULL(gender,''))='MALE'")
  } else {
    row('L 看板画像与读者画像同口径',
      Number(genderL.MALE) === Number(dbMale) && genderTotal === siteReaders && oddKeys.length === 0,
      detailL + ' 库内 APPROVED 男=' + dbMale + '（来源：' + dbMaleSource + '）'
      + ' 非白名单键=' + JSON.stringify(oddKeys))
  }

  const sel = await call(BASE, '/profile-dimensions', ADMIN)
  const dims = (sel.j && sel.j.data) || []
  const degenerate = dims.filter(d => d.buckets.filter(b => b.value).length === 0)
  const badQueryable = degenerate.filter(d => d.queryable)
  row('H.5 ② 只剩「其他」的维度不再标 queryable（H.4 的决策已落地）',
    dims.length > 0 && badQueryable.length === 0,
    `${dims.length} 个维度里 ${degenerate.length} 个只剩「其他」，其中仍 queryable 的 ${badQueryable.length} 个；`
    + `可反查维度 ${dims.filter(d => d.queryable).length} 个`)

  // ---------- P3 单书版吻合度（附录 Z）----------
  const fitOk = await call(BASE, `/book-audience-fit?bookId=${BOOK}&basis=DEEP&days=0`, ADMIN)
  const fitAnon = await call(BASE, `/book-audience-fit?bookId=${BOOK}&basis=DEEP&days=0`, null)
  const fitUser = await call(BASE, `/book-audience-fit?bookId=${BOOK}&basis=DEEP&days=0`, USER)
  row('§4-6 新端点 book-audience-fit 401/403/200',
    fitAnon.status === 401 && fitUser.status === 403 && fitOk.status === 200 && !!fitOk.j && fitOk.j.code === 0,
    `${fitAnon.status}/${fitUser.status}/${fitOk.status}`)

  const profForFit = await call(BASE, `/book-profile?bookId=${BOOK}&basis=DEEP&days=0`, ADMIN)
  row('P3 新端点与单书画像的 readers 一致（同一个 reader 池）',
    !!profForFit.j && !!profForFit.j.data && !!fitOk.j && !!fitOk.j.data
      && profForFit.j.data.readers === fitOk.j.data.readers,
    `book-profile=${profForFit.j && profForFit.j.data && profForFit.j.data.readers} audience-fit=${fitOk.j && fitOk.j.data && fitOk.j.data.readers}`)

  const fitProtected = await call(BASE, `/book-audience-fit?bookId=${PROTECTED_BOOK}&basis=DEEP&days=0`, ADMIN)
  const fp = fitProtected.j && fitProtected.j.data
  row('P3 保护态：读者不足 min-cohort 时一条吻合度都不出',
    !!fp && (fp.readers < fp.minCohortSize) === fp.sampleProtected && (!fp.sampleProtected || fp.fits.length === 0),
    `readers=${fp && fp.readers} min=${fp && fp.minCohortSize} protected=${fp && fp.sampleProtected} fits=${fp && fp.fits && fp.fits.length}`)

  const fitMissing = await call(BASE, '/book-audience-fit?bookId=&basis=DEEP&days=0', ADMIN)
  const fitGhost = await call(BASE, '/book-audience-fit?bookId=987654321&basis=DEEP&days=0', ADMIN)
  row('P3 入参缺失 / 书本不存在 → 可读错误而不是 500',
    fitMissing.status === 400 && fitGhost.status !== 500 && !!fitGhost.j && fitGhost.j.code !== 0,
    `空 bookId=${fitMissing.status} 不存在 id=${fitGhost.status}/${fitGhost.j && fitGhost.j.code}`)

  // ---------- 吻合度：主导桶也要过 min-bucket 闸门（§4 规则 5）----------
  const fitLoose = await call(LOOSE, `/book-audience-fit?bookId=${BOOK}&basis=DEEP&days=0`, ADMIN)
  const fitFits = (fitLoose.j && fitLoose.j.data && fitLoose.j.data.fits) || []
  const smallTop = fitFits.filter(f => f.topTokenLabel && Number(f.topCount) < 5)
  // 主声称桶同样不许发布小格：人数与占比要么一起有、要么一起没有
  const smallClaimed = fitFits.filter(f => f.count != null && Number(f.count) < 5)
  const pairClaimed = fitFits.filter(f => (f.count == null) !== (f.ratio == null))
  const leakySuppressed = fitFits.filter(f => f.verdict === 'NOT_ENOUGH_SAMPLE'
    && (f.ratio != null || f.lift != null || f.significant === true))
  const halfPair = fitFits.filter(f => (f.topTokenLabel == null) !== (f.topRatio == null)
      || (f.topTokenLabel == null) !== (f.topCount == null))
  row('P3 吻合度不发布小于 min-bucket 的单格（主导桶三项一起有或一起无）',
    fitFits.length > 0 && smallTop.length === 0 && halfPair.length === 0
      && smallClaimed.length === 0 && pairClaimed.length === 0 && leakySuppressed.length === 0,
    `fits=${fitFits.length} 小于5的主导桶=${smallTop.length} 主导桶字段不齐=${halfPair.length} `
      + `小于5的本书格=${smallClaimed.length} 本书格字段不齐=${pairClaimed.length} 抑制后仍泄露=${leakySuppressed.length}`)

  // ---------- siteRatio 的分子也必须是可发布的全站格（§4 规则 5，附录 AH）----------
  const looseProfile = await call(LOOSE, `/book-profile?bookId=${BOOK}&basis=DEEP&days=0`, ADMIN)
  const siteDims = await call(BASE, '/profile-dimensions', ADMIN)
  const siteBuckets = new Map((((siteDims.j || {}).data) || []).map(d => [d.key, new Set(
    (d.buckets || []).map(b => b.value).filter(Boolean))]))
  const looseDims = (((looseProfile.j || {}).data) || {}).dimensions || []
  let checkedSiteRatios = 0
  const leakedSite = []
  for (const dim of looseDims) {
    const visible = siteBuckets.get(dim.key) || new Set()
    for (const b of (dim.buckets || [])) {
      if (b.siteRatio == null) continue
      checkedSiteRatios++
      if (!visible.has(b.value)) leakedSite.push(dim.key + ':' + b.value)
    }
  }
  row('P3 siteRatio 的分子必须是可发布的全站格（书侧 vs 全站维度交叉核对）',
    looseDims.length > 0 && leakedSite.length === 0,
    `核对到的 siteRatio=${checkedSiteRatios} 越界=${JSON.stringify(leakedSite)}` +
      (checkedSiteRatios === 0 ? '（dev 全站各桶都 <5，本条在此数据集上只做回归守卫，正面证据靠单测+变异）' : ''))

  // ---------- 入参边界的兜底语义 ----------
  const daysFallback = await call(BASE, `/book-profile?bookId=${BOOK}&basis=DEEP&days=1`, ADMIN)
  const df = daysFallback.j && daysFallback.j.data
  row('§2.2 days=1 回落为近 7 天（8 天窗口）',
    !!df && df.rangeDays === 7, df ? `rangeDays=${df.rangeDays}` : '无数据')

  const limitZero = await call(BASE, `/profile-books?dim=GENDER&value=FEMALE&days=0&basis=DEEP&limit=0`, ADMIN)
  const limitHuge = await call(BASE, `/profile-books?dim=GENDER&value=FEMALE&days=0&basis=DEEP&limit=999`, ADMIN)
  const zeroLen = ((limitZero.j && limitZero.j.data) || []).length
  const hugeLen = ((limitHuge.j && limitHuge.j.data) || []).length
  // 两个列表都空时断言会平凡成立（0 <= 1 且 0 <= 50），所以记 SKIP 而不是 PASS
  row('§6.2 limit 截断到 1..50（0 与 999 都不越界）',
    zeroLen + hugeLen === 0 ? null
      : (limitZero.status === 200 && limitHuge.status === 200 && zeroLen <= 1 && hugeLen <= 50),
    `limit=0 行数=${zeroLen} limit=999 行数=${hugeLen}`
      + (zeroLen + hugeLen === 0 ? '（bucket=5 下无行，截断未被观测；见附录 BT.1 的 bucket=1 验证）' : ''))

  const fitBadBasis = await call(BASE, `/book-audience-fit?bookId=${BOOK}&basis=%7B%7B7*7%7D%7D`, ADMIN)
  row('P3 吻合度非法口径：业务错误而不是 500',
    fitBadBasis.status !== 500 && !!fitBadBasis.j && fitBadBasis.j.code !== 0,
    `status=${fitBadBasis.status} code=${fitBadBasis.j && fitBadBasis.j.code}`)

  // ---------- P3 受众吻合度与反查榜的时间窗契约 ----------
  const fitWindow = await call(LOOSE, `/book-audience-fit?bookId=${BOOK}&basis=DEEP&start=2026-10-01&end=2026-10-10`, ADMIN)
  const fw = fitWindow.j && fitWindow.j.data
  row('P3 受众吻合度区间：rangeDays=10 且起止回显',
    !!fw && fw.rangeDays === 10 && fw.rangeStart === '2026-10-01' && fw.rangeEnd === '2026-10-10',
    fw ? `rangeDays=${fw.rangeDays} ${fw.rangeStart}~${fw.rangeEnd}` : '无数据')

  const booksInverted = await call(BASE, `/profile-books?dim=GENDER&value=FEMALE&start=2026-10-10&end=2026-10-01`, ADMIN)
  row('§2.2 反查榜 start>end：业务错误且文案一致',
    !!booksInverted.j && booksInverted.j.code !== 0 && /开始日期不能晚于结束日期/.test(booksInverted.j.message || ''),
    `code=${booksInverted.j && booksInverted.j.code} message=${booksInverted.j && booksInverted.j.message}`)

  // ---------- 趋势端点（只读；不触发快照写入）----------
  const trendOk = await call(BASE, `/book-profile-trend?bookId=${BOOK}&basis=DEEP&days=7`, ADMIN)
  const trendAnon = await call(BASE, `/book-profile-trend?bookId=${BOOK}&basis=DEEP&days=7`, null)
  const trendUser = await call(BASE, `/book-profile-trend?bookId=${BOOK}&basis=DEEP&days=7`, USER)
  row('§4-6 趋势端点 book-profile-trend 401/403/200',
    trendAnon.status === 401 && trendUser.status === 403 && trendOk.status === 200 && !!trendOk.j && trendOk.j.code === 0,
    trendAnon.status + '/' + trendUser.status + '/' + trendOk.status + ' 点数=' + (trendOk.j && trendOk.j.data ? trendOk.j.data.length : '-'))

  const trendBadBasis = await call(BASE, '/book-profile-trend?bookId=' + BOOK + '&basis=%7B%7B7*7%7D%7D&days=7', ADMIN)
  const trendGhost = await call(BASE, '/book-profile-trend?bookId=987654321&basis=DEEP&days=7', ADMIN)
  const trendAsc = await call(BASE, `/book-profile-trend?bookId=${BOOK}&basis=DEEP&days=30`, ADMIN)
  const pts = (trendAsc.j && trendAsc.j.data) || []
  const ascending = pts.every((p, i) => i === 0 || pts[i - 1].date < p.date)
  row('P3 趋势日期严格升序（图表按此顺序连线）', pts.length > 1 && ascending,
    pts.map(p => p.date).join(' → ') || '无数据')

  const trendZero = await call(BASE, `/book-profile-trend?bookId=${BOOK}&basis=DEEP&days=0`, ADMIN)
  const trendHuge = await call(BASE, `/book-profile-trend?bookId=${BOOK}&basis=DEEP&days=9999`, ADMIN)
  row('P3 趋势 days 边界：0 与超大值都不报错且点数受窗口约束',
    trendZero.status === 200 && trendHuge.status === 200
      && ((trendZero.j && trendZero.j.data) || []).length <= 1
      && ((trendHuge.j && trendHuge.j.data) || []).length <= 366,
    `days=0 点数=${((trendZero.j && trendZero.j.data) || []).length} days=9999 点数=${((trendHuge.j && trendHuge.j.data) || []).length}`)

  row('P3 趋势入参：非法口径给业务错误（HTTP 200 + code≠0）、无快照的书返回空而不是 500',
    trendBadBasis.status !== 500 && !!trendBadBasis.j && trendBadBasis.j.code !== 0 && trendGhost.status !== 500 && Array.isArray(trendGhost.j && trendGhost.j.data)
      && trendGhost.j.data.length === 0,
    '非法 basis=' + trendBadBasis.status + ' 不存在的书=' + trendGhost.status + (Array.isArray(trendGhost.j && trendGhost.j.data) ? '/空' : '/-'))
  finish(results.some(r => r[1] === 'FAIL') ? 1 : 0)
})().catch(e => {
  if (e.message === 'PREREQ') finish(2)
  console.log('运行异常：' + e.message)
  finish(2)
})
