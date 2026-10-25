// §6 接口契约核对：后端 DTO 字段 ↔ 前端 TS interface，以及 query 参数名。
// 零依赖、纯静态，不启服务。只比名字（不比类型、不比默认值），因为出过事的正是"前端新、后端旧会整片 undefined"。
// 用法：node deploy/verify/profile-dto-parity.mjs   （退出码 0 = 齐，1 = 有漂移）
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const JAVA = 'backend/src/main/java/com/kbook'
const TS_API = 'frontend/src/api/adminDashboard.ts'

function read(rel) {
  return fs.readFileSync(path.join(ROOT, rel), 'utf8')
}

/**
 * Java：把 `private T name;` 归到它所在的类名上。
 * 只按"class X {"推进栈、"}"弹栈——这两个 DTO 里没有别的花括号，够用且不引入解析器。
 */
function javaClasses(src) {
  const out = new Map()
  const stack = []
  const token = /class\s+([A-Za-z0-9_]+)\s*\{|\bprivate\s+(?:static\s+)?[\w.<>[\]]+\s+([a-z][\w]*)\s*[;=]|^\}/gm
  let m
  while ((m = token.exec(src)) !== null) {
    if (m[1]) {
      stack.push(m[1])
      out.set(m[1], [])
    } else if (m[2]) {
      const owner = stack[stack.length - 1]
      if (owner) out.get(owner).push(m[2])
    } else {
      stack.pop()
    }
  }
  return out
}

/** TS：`export interface X { ... }` 块里的成员名（注释行与索引签名之外都算成员候选） */
function tsInterfaces(src) {
  const out = new Map()
  const re = /export interface (\w+)\s*\{([\s\S]*?)\n\}/g
  let m
  while ((m = re.exec(src)) !== null) {
    const members = m[2]
      .split('\n')
      .map(l => l.trim())
      .filter(l => l && !l.startsWith('/'))
      .map(l => l.match(/^(\w+)\??:/)?.[1])
      .filter(Boolean)
    out.set(m[1], members)
  }
  return out
}

/** Controller：方法签名里每个 @RequestParam 的形参名（逐行取行尾标识符） */
function requestParams(src, method) {
  const start = src.indexOf(`${method}(`)
  if (start < 0) throw new Error(`找不到方法 ${method}`)
  const end = src.indexOf(') {', start)
  const sig = src.slice(start, end)
  return sig
    .split('\n')
    .filter(l => l.includes('@RequestParam'))
    .map(l => l.trim().match(/(\w+),?$/)?.[1])
    .filter(Boolean)
}

/** api 文件：请求函数 params 标注里的键名（`A & { ... }` 两半都展开） */
function tsParams(src, fn, ifaces) {
  const start = src.indexOf(`export function ${fn}(`)
  if (start < 0) throw new Error(`找不到函数 ${fn}`)
  const sig = src.slice(start, src.indexOf(') {', start))
  const anno = sig.slice(sig.indexOf('params:') + 'params:'.length)
  const names = []
  for (const part of anno.split('&')) {
    const named = part.trim().match(/^(\w+)$/)?.[1]
    if (named) {
      names.push(...(ifaces.get(named) || []))
      continue
    }
    const inline = part.match(/\{([\s\S]*)\}/)
    if (inline) {
      names.push(...inline[1].split(/[;\n]/).map(s => s.trim().match(/^(\w+)\??:/)?.[1]).filter(Boolean))
    }
  }
  return names
}

const voClasses = javaClasses(read(`${JAVA}/dto/admin/BookProfileVO.java`))
const pbClasses = javaClasses(read(`${JAVA}/dto/admin/ProfileBookVO.java`))
const afClasses = javaClasses(read(`${JAVA}/dto/admin/BookAudienceFitVO.java`))
const trendClasses = javaClasses(read(`${JAVA}/dto/admin/BookProfileTrendPointVO.java`))
const snapClasses = javaClasses(read(`${JAVA}/dto/admin/BookProfileSnapshotResultVO.java`))
const ifaces = tsInterfaces(read(TS_API))
const ctrl = read(`${JAVA}/controller/AdminDashboardController.java`)
const apiSrc = read(TS_API)

const problems = []
const lines = []

function checkFields(javaName, javaFields, tsName, tsFields) {
  const a = javaFields || []
  const b = tsFields || []
  const onlyJava = a.filter(x => !b.includes(x))
  const onlyTs = b.filter(x => !a.includes(x))
  if (!a.length) problems.push(`${javaName}：后端一个字段都没解析出来，脚本对不上这份文件了`)
  if (!b.length) problems.push(`${tsName}：前端一个成员都没解析出来`)
  if (onlyJava.length || onlyTs.length) {
    problems.push(`${javaName} ↔ ${tsName}：后端独有 ${JSON.stringify(onlyJava)} / 前端独有 ${JSON.stringify(onlyTs)}`)
  }
  lines.push(`${javaName}(${a.length}) ↔ ${tsName}(${b.length}) ${onlyJava.length || onlyTs.length ? 'DIFF' : 'OK'}`)
}

checkFields('BookProfileVO', voClasses.get('BookProfileVO'), 'BookProfile', ifaces.get('BookProfile'))
checkFields('BookProfileVO.Dimension', voClasses.get('Dimension'), 'BookProfileDimension', ifaces.get('BookProfileDimension'))
checkFields('BookProfileVO.Bucket', voClasses.get('Bucket'), 'BookProfileBucket', ifaces.get('BookProfileBucket'))
checkFields('ProfileBookVO', pbClasses.get('ProfileBookVO'), 'ProfileBook', ifaces.get('ProfileBook'))
checkFields('BookAudienceFitVO', afClasses.get('BookAudienceFitVO'), 'BookAudienceFit', ifaces.get('BookAudienceFit'))
checkFields('BookAudienceFitVO.Fit', afClasses.get('Fit'), 'BookAudienceFitItem', ifaces.get('BookAudienceFitItem'))
checkFields('BookProfileTrendPointVO', trendClasses.get('BookProfileTrendPointVO'), 'BookProfileTrendPoint', ifaces.get('BookProfileTrendPoint'))
checkFields('BookProfileSnapshotResultVO', snapClasses.get('BookProfileSnapshotResultVO'), 'BookProfileSnapshotResult', ifaces.get('BookProfileSnapshotResult'))

for (const fn of ['getBookProfile', 'getProfileBooks', 'getBookAudienceFit', 'getBookProfileTrend']) {
  const java = requestParams(ctrl, fn)
  const ts = tsParams(apiSrc, fn, ifaces)
  const onlyJava = java.filter(x => !ts.includes(x))
  const onlyTs = ts.filter(x => !java.includes(x))
  if (onlyJava.length || onlyTs.length) {
    problems.push(`${fn} 参数：后端独有 ${JSON.stringify(onlyJava)} / 前端独有 ${JSON.stringify(onlyTs)}`)
  }
  lines.push(`${fn} 参数(${java.length}) ↔ params(${ts.length}) ${onlyJava.length || onlyTs.length ? 'DIFF' : 'OK'}`)
}

console.log(lines.join('\n'))
if (problems.length) {
  console.log('漂移：\n- ' + problems.join('\n- '))
  console.log('判定：FAIL')
  process.exit(1)
}
console.log('判定：PASS（字段名与参数名两端一致；不比类型，也不比默认值）')
