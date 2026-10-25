import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { TrendingUp } from 'lucide-react'
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { getBookProfileTrend, type ReaderBasis } from '@/api/adminDashboard'

/** 与画像其它格式化同一规矩：非有限值一律 '—' */
function num(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—'
  return String(n)
}

interface Props {
  bookId: number
  basis: ReaderBasis
}

/**
 * 单书读者数趋势（P3 第三步的展示面）。
 *
 * 只画人数：分布的历史在 payload 里，按日取 book-profile 即可；趋势块把它塞进响应会让页面变重，
 * 也违背"人数不是分布"的隐私口径（设计文档 §2/附录 AA.3）。
 */
export function ProfileTrendBlock({ bookId, basis }: Props) {
  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['admin', 'book-profile-trend', bookId, basis],
    queryFn: () => getBookProfileTrend({ bookId, basis, days: 30 }),
    placeholderData: keepPreviousData,
  })

  const points = data ?? []
  const enough = points.length >= 2

  return (
    <div className="rounded-lg bg-muted/50 p-3">
      <div className="mb-1.5 flex items-center gap-1.5">
        <TrendingUp className="h-3.5 w-3.5 text-primary" />
        <span className="text-caption font-medium">读者数趋势</span>
        <span className="text-[10px] text-muted-foreground">近 30 天每日快照</span>
      </div>

      {isLoading && <p className="py-1 text-[10px] text-muted-foreground">正在读取快照…</p>}

      {isError && !isLoading && (
        <p className="py-1 text-[10px] text-muted-foreground">
          {error instanceof Error ? error.message : '加载失败'}
          <button onClick={() => refetch()} className="ml-1 text-primary underline-offset-2 active:underline">重试</button>
        </p>
      )}

      {data && !enough && (
        <p className="py-1 text-[10px] text-muted-foreground">
          暂无足够快照（当前 {points.length} 天）。快照每天 03:10 生成，至少需要 2 天才能画趋势。
        </p>
      )}

      {data && enough && (
        <>
          {/* 高度写死：自适应容器在抽屉里算不出高度时不会报错，只会画不出来 */}
          <div className="h-[120px] w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={points} margin={{ top: 4, right: 6, bottom: 0, left: -18 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                <XAxis dataKey="date" tick={{ fontSize: 9 }} tickFormatter={(v: string) => v.slice(5)} />
                <YAxis tick={{ fontSize: 9 }} allowDecimals={false} />
                <Tooltip formatter={(v: number) => [`${num(v)} 位读者`, '读者数']} labelFormatter={l => String(l)} />
                <Line type="monotone" dataKey="readers" stroke="hsl(var(--primary))" strokeWidth={2} dot={{ r: 2 }} />
              </LineChart>
            </ResponsiveContainer>
          </div>
          <p className="mt-1 text-[10px] text-muted-foreground">
            {points[0]?.date} → {points[points.length - 1]?.date} · {points.length} 个快照日
          </p>
        </>
      )}
    </div>
  )
}
