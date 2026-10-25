import { ChevronDown } from 'lucide-react'
import { useCollapsed } from './CollapsibleSection'
import type { BookProfileBucket, BookProfileDimension } from '@/api/adminDashboard'

/** 与主看板一致的色板，保证同一站点头脑里的颜色语义不分裂 */
const PALETTE = ['#5B8C5A', '#D4A574', '#6B8FA8', '#9A948A', '#A8763E', '#5A7D9A', '#8C5B6B', '#5B8C7A']

/** 所有格式化都必须先过有限性检查：undefined 直接打印是主看板踩过的坑 */
function share(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return `${(n * 100).toFixed(0)}%`
}

function countText(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return `${n} 人`
}

export function LiftBadge({ bucket }: { bucket: BookProfileBucket }) {
  if (bucket.lift == null || !Number.isFinite(bucket.lift)) {
    return <span className="text-[10px] text-muted-foreground">无基线</span>
  }
  // 不显著的桶不上色，避免视觉误导
  if (!bucket.significant) {
    return <span className="text-[10px] text-muted-foreground">×{bucket.lift.toFixed(2)}</span>
  }
  const tone = bucket.lift >= 1.3
    ? 'bg-primary/15 text-primary'
    : bucket.lift <= 0.7
      ? 'bg-danger/15 text-danger'
      : 'bg-muted text-muted-foreground'
  return (
    <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${tone}`}>
      全站 ×{bucket.lift.toFixed(2)}
    </span>
  )
}

function BucketRow({ bucket, index, maxCount }: { bucket: BookProfileBucket; index: number; maxCount: number }) {
  const width = maxCount > 0 ? Math.max(2, (bucket.count / maxCount) * 100) : 0
  return (
    <div className="flex items-center gap-2" role="listitem" data-bucket={bucket.label}>
      <span className="w-20 shrink-0 truncate text-caption" title={bucket.label}>{bucket.label}</span>
      <div className="h-3 min-w-0 flex-1 overflow-hidden rounded-full bg-muted">
        <div className="h-full rounded-full" style={{ width: `${width}%`, background: PALETTE[index % PALETTE.length] }} />
      </div>
      <span className="w-12 shrink-0 text-right text-caption tabular-nums">{share(bucket.ratio)}</span>
      <span className="w-12 shrink-0 text-right text-caption tabular-nums text-muted-foreground">{bucket.count}</span>
      <span className="w-20 shrink-0 text-right"><LiftBadge bucket={bucket} /></span>
    </div>
  )
}

export function BookProfileSection({ dimension, storageKey }: {
  dimension: BookProfileDimension
  storageKey: string
}) {
  const { buckets } = dimension
  const maxCount = buckets.reduce((max, b) => Math.max(max, b.count), 0)
  const lowCoverage = dimension.coverage < 0.3
  const { open, toggle } = useCollapsed(storageKey)

  return (
    <section className="rounded-lg bg-muted/50 p-3">
      <button type="button" onClick={toggle} aria-expanded={open}
        className="mb-2 flex w-full items-center justify-between gap-2 text-left">
        <span className="flex min-w-0 items-center gap-1.5">
          <ChevronDown className={`h-3.5 w-3.5 shrink-0 text-muted-foreground transition-transform ${open ? 'rotate-180' : ''}`} />
          <span className="truncate text-sm font-medium">{dimension.label}</span>
        </span>
        <span className={`shrink-0 text-[10px] ${lowCoverage ? 'text-warning' : 'text-muted-foreground'}`}>
          {dimension.multiValue && <span className="mr-1">多值·占比之和可&gt;100%</span>}
          填写率 {share(dimension.coverage)}
        </span>
      </button>

      {open && (
        <>
      {buckets.length === 0 && (
        <p className="py-2 text-center text-caption text-muted-foreground">
          {dimension.unknownCount > 0 ? '该维度无人填写' : '暂无有效样本'}
        </p>
      )}

      {buckets.length > 0 && (
        <>
          {/* 两桶维度用一条堆叠条表达整体结构；多值维度一人可进多桶，堆叠条会失真，不画 */}
          {buckets.length === 2 && !dimension.multiValue && (
            <div className="mb-2 flex h-2 overflow-hidden rounded-full">
              {buckets.map((b, i) => (
                <div key={b.value ?? `other-${i}`}
                  style={{ width: `${Math.min(100, b.ratio * 100)}%`, background: PALETTE[i % PALETTE.length] }} />
              ))}
            </div>
          )}
          <div className="space-y-1.5" role="list" aria-label={`${dimension.label}分桶`}>
            {buckets.map((b, i) => (
              <BucketRow key={b.value ?? `other-${i}`} bucket={b} index={i} maxCount={maxCount} />
            ))}
          </div>
          {(dimension.unknownCount > 0 || dimension.dirtyCount > 0) && (
            <p className="mt-2 text-[10px] leading-relaxed text-muted-foreground">
              另有 {countText(dimension.unknownCount)}未填写
              {dimension.dirtyCount > 0 && ` · ${countText(dimension.dirtyCount)}取值不规范（不计入占比）`}
            </p>
          )}
          {lowCoverage && (
            <p className="mt-1 text-[10px] leading-relaxed text-warning">
              填写率偏低，占比只反映填过该项的读者
            </p>
          )}
        </>
      )}
        </>
      )}
    </section>
  )
}
