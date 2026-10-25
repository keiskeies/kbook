// 设计文档里的仓库路径是否真的存在。纯静态、零依赖。
// 用法：node deploy/verify/doc-paths.mjs [--doc docs/plans/admin-book-reader-profile.md]
//
// 来历：这份文档 1800 行、十几次改版，引用的文件改过名（B.2 的 book-profiles → profile-books）、
// 行号搬过家、索引名与实现分过叉。人工核对每次都漏，所以把"路径存在"这一层做成可重跑的检查；
// 行号正确性不在本脚本范围内（那要 AST，见 P 的教训：宁可窄而真）。
//
// 两种写法都要认：带目录前缀的绝对路径（backend/src/...），以及树状图里的相对路径
// （service/admin/BookProfileService.java 写在 backend/src/main/java/com/kbook/ 下面）——
// 后者按"全仓存在某个以它结尾的文件"来判，否则会得到一片假红。
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs'
import { resolve, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repo = resolve(here, '../..')

// 默认同时核设计文档与部署指南：后者是运维唯一会照着做的那份
const argIdx = process.argv.indexOf('--doc')
const docs = argIdx > -1
  ? [process.argv[argIdx + 1]]
  : ['docs/plans/admin-book-reader-profile.md', 'deploy/docs/deploy-guide.md']

// 已知且已在文档里记过的偏差：路径不存在是"文档描述"而非"引用失效"，列在这里并打印原因
const ALLOW = new Map([
  ['pages/admin/book-profiles.tsx', 'B.2 已记：实际文件是 profile-books.tsx'],
  ['frontend/vite.verify.config.ts', 'C.4 / G.5 自述为临时文件、验证后删除；不是失效引用'],
  ['frontend/dist', '构建产物，gitignore 不追踪；文档只把它当输出目录'],
  ['package/package.sh', '本机打包工具，package/ 已 gitignore（指南改为 download.sh）'],
  ['package/package.bat', '同上'],
  ['backend/.verify-token.txt', '本机自签 token 文件，已 gitignore；文档描述的是它被忽略这件事'],
])

const SKIP = new Set(['node_modules', 'dist', 'target', '.git', '.gitnexus', '.idea', '.vscode', 'logs', 'tmp'])

function walk(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    if (SKIP.has(name)) continue
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p, acc)
    else acc.push(p.slice(repo.length + 1).replace(/\\/g, '/'))
  }
  return acc
}
const allFiles = walk(repo)

const spans = []
for (const rel of docs) {
  const text = readFileSync(resolve(repo, rel), 'utf8')
  spans.push(...[...text.matchAll(/`([^`\n]+)`/g)].map(m => m[1]))
}

const PREFIX = /^(backend|frontend|deploy|docs|scripts|\.claude)\//
const SUFFIX = /\.(java|ts|tsx|js|mjs|sql|yml|yaml|md|json|sh|bat|properties)$/

const candidates = new Set()
for (const raw of spans) {
  let t = raw.trim().replace(/^['"]|['"]$/g, '')
  t = t.replace(/:\d+(-\d+)?$/, '')            // 去掉 :123 / :123-456
  if (t.startsWith('/')) continue               // /src/api/user.ts 这类是浏览器里的模块 URL，不是仓库路径
  if (t.startsWith('./') || t.startsWith('../')) continue // ./package.sh 是命令写法，不是仓库路径
  if (!PREFIX.test(t) && !(t.includes('/') && SUFFIX.test(t))) continue
  if (/[\s*{}?=<>…]|\.\.\./.test(t) || /^https?:/.test(t)) continue
  candidates.add(t)
}

if (candidates.size < 5) {
  console.error(`只解析出 ${candidates.size} 个路径——解析器坏了，这条检查等于没跑`)
  process.exit(2)
}

const missing = []
for (const p of candidates) {
  if (PREFIX.test(p) ? existsSync(resolve(repo, p)) : allFiles.some(f => f === p || f.endsWith('/' + p))) continue
  missing.push({ p, allowed: ALLOW.get(p) ?? null })
}
const real = missing.filter(m => !m.allowed)

console.log(`文档：${docs.join(' + ')}`)
console.log(`解析路径：${candidates.size} 个｜缺失：${missing.length}（其中已在文档记过的偏差 ${missing.length - real.length}）`)
for (const m of missing) console.log(`  ${m.allowed ? '~' : '×'} ${m.p}${m.allowed ? '  ← ' + m.allowed : ''}`)
console.log(real.length === 0 ? '判定：PASS（没有未登记的失效引用）' : `判定：FAIL（${real.length} 条未登记的失效引用）`)
process.exit(real.length === 0 ? 0 : 1)
