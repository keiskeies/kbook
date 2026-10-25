// 静态核对一键入口：两个零依赖脚本，不需要起服务。
//   node deploy/verify/run-static.mjs
// 覆盖：设计文档里的仓库路径是否存在（附录 U）、§6 两端字段与参数名是否一致（附录 P）。
// 退出码：0 = 全过；1 = 有 FAIL；2 = 连脚本都没跑起来（缺文件），别把 2 读成通过。
//
// 这两个检查之所以放一起：它们都是「不跑应用也能知道对错」的那一层，适合挂在提交前。
// 需要服务才能跑的（矩阵 19 行、抽屉/首屏浏览器断言）刻意不塞进来——混在一起的失败
// 会让人分不清"代码坏了"还是"实例没起"。
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const scripts = [
  ['profile-dto-parity.mjs', '§6 两端字段/参数名（附录 P）'],
  ['doc-paths.mjs', '设计文档路径存在性（附录 U）'],
  ['env-names.mjs', '部署清单环境变量 ↔ 配置源（附录 AU）'],
  ['api-paths.mjs', '前端接口路径 ↔ 后端映射（附录 AV）'],
]

let failed = 0
for (const [file, label] of scripts) {
  const path = resolve(here, file)
  if (!existsSync(path)) {
    console.log(`× 缺脚本 ${file}（${label}）——这项等于没查`)
    failed++
    continue
  }
  const r = spawnSync(process.execPath, [path], { encoding: 'utf8' })
  const verdict = (r.stdout || '').trim().split('\n').pop()
  if (r.status === 0) {
    console.log(`✓ ${label}：${verdict}`)
  } else {
    failed++
    console.log(`× ${label}：${verdict || (r.stderr || '').slice(0, 200)}`)
  }
}

console.log(failed === 0
  ? `静态核对：PASS（${scripts.length}/${scripts.length}）`
  : `静态核对：FAIL（${failed}/${scripts.length}）`)
process.exit(failed === 0 ? 0 : 1)
