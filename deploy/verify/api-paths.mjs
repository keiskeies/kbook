// 前端 api 文件里请求的 /admin/dashboard/* 路径，后端控制器是否真的暴露了。
//
// 为什么需要：路径字符串两端都是"裸字符串"。后端把 /book-audience-fit 改成别的名字，前端编译照样通过，
// 运行时才会 404——而且 404 会被全局异常包装成"接口不存在"，很容易被当成环境问题。
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repo = resolve(here, '../..')

const TS_API = resolve(repo, 'frontend/src/api/adminDashboard.ts')
const JAVA_CTRL = resolve(repo, 'backend/src/main/java/com/kbook/controller/AdminDashboardController.java')

// 1) 前端：request.get/post('<path>' ...)
const ts = readFileSync(TS_API, 'utf8')
const fromFrontend = new Set()
for (const m of ts.matchAll(/request\.(?:get|post|put|delete)<[^>]*>\(\s*[`'"](\/admin\/dashboard\/[^`'"?]+)/g)) {
  fromFrontend.add(m[1])
}

// 2) 后端：类级前缀 + 方法级映射
const java = readFileSync(JAVA_CTRL, 'utf8')
const prefix = (java.match(/@RequestMapping\("([^"]+)"\)/) || [])[1]
if (!prefix) {
  console.error('找不到 @RequestMapping 前缀——解析器对不上这份控制器了')
  process.exit(2)
}
const fromBackend = new Set()
for (const m of java.matchAll(/@(Get|Post|Put|Delete)Mapping\("([^"]*)"\)/g)) {
  fromBackend.add(prefix + m[2])
}

if (fromFrontend.size < 3) {
  console.error(`只解析出 ${fromFrontend.size} 个前端路径——解析器坏了，这条检查等于没跑`)
  process.exit(2)
}

// 后端前缀是 /api/admin/dashboard，前端 request 基地址已含 /api；模板路径还会带 ${...}
const normalize = p => p.replace(/^\/api/, '').split('${')[0].split('?')[0]
const fe = new Set([...fromFrontend].map(normalize))
const be = new Set([...fromBackend].map(normalize))
const missing = [...fe].filter(p => !be.has(p)).sort()
const unused = [...be].filter(p => !fe.has(p)).sort()

console.log(`前端路径 ${fromFrontend.size} 个 | 后端映射 ${fromBackend.size} 个`)
if (missing.length) console.log('前端在调、后端没有：\n  - ' + missing.join('\n  - '))
if (unused.length) console.log('后端有、前端未用（提示，不判失败）：\n  - ' + unused.join('\n  - '))
console.log(missing.length === 0
  ? '判定：PASS（前端调用的路径都存在）'
  : `判定：FAIL（${missing.length} 个路径会 404）`)
process.exit(missing.length === 0 ? 0 : 1)
