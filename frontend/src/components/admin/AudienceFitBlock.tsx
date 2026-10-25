import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Tags } from 'lucide-react'
import {
  getBookAudienceFit,
  type AudienceFitVerdict,
  type BookAudienceFitItem,
  type DashboardQuery,
  type ReaderBasis,
} from '@/api/adminDashboard'

const VERDICT: Record<AudienceFitVerdict, { text: string; tone: string }> = {
  ALIGNED: { text: '吻合', tone: 'text-success' },
  MISMATCH: { text: '错配', tone: 'text-danger' },
  NOT_ENOUGH_SAMPLE: { text: '样本不足', tone: 'text-muted-foreground' },
  NO_DATA: { text: '无数据', tone: 'text-muted-foreground' },
}

/** 有限性兜底与画像抽屉同一规矩：undefined 直接打印是踩过的坑（设计文档 8.2） */
function pct(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return `${(n * 100).toFixed(0)}%`
}

function times(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return `×${n.toFixed(2)}`
}

function FitRow({ fit }: { fit: BookAudienceFitItem }) {
  const verdict = VERDICT[fit.verdict] ?? VERDICT.NO_DATA
  return (
    <div className="rounded-md bg-muted/40 px-2.5 py-2">
      <div className="flex items-baseline justify-between gap-2">
        <span className="min-w-0 truncate text-caption">
          <span className="text-muted-foreground">{fit.dimLabel} · </span>
          {fit.tokenLabel}
        </span>
        <span className={`shrink-0 text-[10px] font-medium ${verdict.tone}`}>{verdict.text}</span>
      </div>
      <div className="mt-0.5 text-[10px] text-muted-foreground">
        {fit.count != null && fit.ratio != null
          ? <>本书 {pct(fit.ratio)}（{fit.count} 人）· 全站 {pct(fit.siteRatio)} · {times(fit.lift)}</>
          : <>本书该桶人数不足，不提供占比（全站 {pct(fit.siteRatio)}）</>}
      </div>
      {fit.verdict === 'MISMATCH' && fit.topTokenLabel && fit.topRatio != null && (
        <div className="mt-0.5 text-[10px] text-muted-foreground">
          实际最多：{fit.topTokenLabel} {pct(fit.topRatio)}（{fit.topCount} 人）
        </div>
      )}
    </div>
  )
}

interface Props {
  bookId: number
  query: DashboardQuery
  basis: ReaderBasis
}

/** P3 单书版吻合度：书侧声称的受众 vs 实际读者分布（口径见设计文档附录 Z） */
export function AudienceFitBlock({ bookId, query, basis }: Props) {
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['admin', 'book-audience-fit', bookId, basis, query],
    queryFn: () => getBookAudienceFit({ ...query, bookId, basis }),
    placeholderData: keepPreviousData,
  })

  return (
    <div className="rounded-lg bg-muted/50 p-3">
      <div className="mb-1.5 flex items-center gap-1.5">
        <Tags className="h-3.5 w-3.5 text-primary" />
        <span className="text-caption font-medium">受众吻合度</span>
        <span className="text-[10px] text-muted-foreground">书标的是谁 · 实际谁在读</span>
      </div>

      {isLoading && <p className="py-1 text-[10px] text-muted-foreground">正在比对…</p>}

      {isError && !isLoading && (
        <p className="py-1 text-[10px] text-muted-foreground">
          {error instanceof Error ? error.message : '加载失败'}
          <button onClick={() => refetch()} className="ml-1 text-primary underline-offset-2 active:underline">重试</button>
        </p>
      )}

      {data && data.sampleProtected && (
        <p className="py-1 text-[10px] text-muted-foreground">
          当前 {data.readers} 位读者，低于 {data.minCohortSize} 位阈值，不等于判定标签是否准确
        </p>
      )}

      {data && !data.sampleProtected && data.fits.length === 0 && (
        <p className="py-1 text-[10px] text-muted-foreground">
          本书 {data.claimedTags} 个标签里没有一个能落到画像桶（兴趣 {data.interestTags} · 泛化 {data.genericTags} ·
          表达不了 {data.noBucketTags} · 未识别 {data.unrecognizedTags}）
        </p>
      )}

      {data && !data.sampleProtected && data.fits.length > 0 && (
        <div className="space-y-1.5">
          {data.fits.map(fit => (
            <FitRow key={`${fit.dimKey}:${fit.token}`} fit={fit} />
          ))}
          {data.claimedTags > data.claimedAudience && (
            <p className="text-[10px] leading-relaxed text-muted-foreground">
              另有 {data.claimedTags - data.claimedAudience} 个标签无法落到画像桶（兴趣 {data.interestTags} ·
              泛化 {data.genericTags} · 表达不了 {data.noBucketTags} · 未识别 {data.unrecognizedTags}），不参与判定
            </p>
          )}
        </div>
      )}
    </div>
  )
}
