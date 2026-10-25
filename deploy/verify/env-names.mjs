// 部署清单里写的 KBOOK_* 环境变量，是否都能在配置源里找到（yml 的 ${ENV:default} 或 @Value 的属性键）。
//
// 来历：部署清单是运维唯一会看的那张表。里面出现一个不存在的变量名，后果不是报错，而是
// "设了但没生效"——最坏情况下被误读成"配置改了、阈值没变"。这类漂移人工核对必然漏。
//
// 映射规则与 Spring 的 relaxed binding 一致：属性键 a.b-c 对应 A_B_C。
//   来源一：application*.yml 里的 ${KBOOK_...:default}
//   来源二：Java 里的 @Value("${kbook...:default}")，按同一规则反推 env 名
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { resolve, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repo = resolve(here, '../..')

function walk(dir, filter, acc = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, filter, acc)
    else if (filter(p)) acc.push(p)
  }
  return acc
}

// 1) 清单里的变量名
// 设计文档里会出现三类"不是部署变量"的提及：引用历史错误名、讲 relaxed binding 的示例、通配写法。
// 只对设计文档放行，部署清单仍然严格——清单是运维唯一会照着设的地方。
const DESIGN_DOC_ALLOW = new Map([
  ['KBOOK_PROFILE_SNAPSHOT_ENABLED', '附录 AU 引用的是历史错误名'],
  ['KBOOK_A_B', '附录 AU 的映射示例'],
  ['KBOOK_ADMIN_BOOK_PROFILE_SNAPSHOT_', '附录 AK 的通配写法'],
  ['KBOOK_PROFILE_', '通配写法'],
])

const DOCS = ['deploy/docs/deploy-guide.md', 'docs/plans/admin-book-reader-profile.md']
const documented = new Set()
for (const rel of DOCS) {
  const text = readFileSync(resolve(repo, rel), 'utf8')
  for (const m of text.matchAll(/\bKBOOK_[A-Z0-9_]+\b/g)) {
    if (rel.includes('docs/plans') && DESIGN_DOC_ALLOW.has(m[0])) continue
    if (m[0].endsWith('_')) continue // 通配残留
    documented.add(m[0])
  }
}

// 2) yml 里显式声明的名字
const ymlFiles = walk(resolve(repo, 'backend/src/main/resources'), p => /application.*\.yml$/.test(p))
const fromYaml = new Set()
for (const f of ymlFiles) {
  const text = readFileSync(f, 'utf8')
  for (const m of text.matchAll(/\$\{(KBOOK_[A-Z0-9_]+)(?::[^}]*)?\}/g)) fromYaml.add(m[1])
}

// 3) Java 里的占位符 → env 名。@Value 与 @Scheduled(cron = "${...}") 是同一种写法，统一扫，
//    否则 cron 那个真实存在的变量会被误判成"设了不生效"。
const javaFiles = walk(resolve(repo, 'backend/src/main/java'), p => p.endsWith('.java'))
const fromValue = new Set()
for (const f of javaFiles) {
  const text = readFileSync(f, 'utf8')
  for (const m of text.matchAll(/\$\{([a-zA-Z0-9._-]+)(?::[^}]*)?\}/g)) {
    fromValue.add(m[1].toUpperCase().replace(/[^A-Z0-9]+/g, '_'))
  }
}

// 4) 反向：yml 里有、清单里没有 —— 只提示，不算失败（有些变量确实不建议暴露给运维）
const known = new Set([...fromYaml, ...fromValue])
const missing = [...documented].filter(name => !known.has(name)).sort()
const undocumented = [...fromYaml].filter(name => !documented.has(name)).sort()

if (documented.size < 5) {
  console.error(`只解析出 ${documented.size} 个 KBOOK_* 变量——解析器坏了，这条检查等于没跑`)
  process.exit(2)
}

console.log(`清单变量 ${documented.size} 个 | yml 声明 ${fromYaml.size} | @Value 推导 ${fromValue.size}`)
if (missing.length) console.log('清单里有、配置源里没有：\n  - ' + missing.join('\n  - '))
if (undocumented.length) console.log('yml 有、清单未登记（提示，不判失败）：\n  - ' + undocumented.join('\n  - '))
console.log(missing.length === 0
  ? '判定：PASS（清单里的变量都能被应用识别）'
  : `判定：FAIL（${missing.length} 个变量设了也不会生效）`)
process.exit(missing.length === 0 ? 0 : 1)
