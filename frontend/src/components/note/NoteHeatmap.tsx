import { useMemo } from 'react'
import type { NoteDailyCount } from '@/api/note'
import { cn } from '@/utils/cn'

const MONTHS = ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月']
const WEEK_ROWS = [1, 3, 5] // 只标注周一/周三/周五

function parseDay(key: string) {
  const [y, m, d] = key.split('-').map(Number)
  return new Date(y, m - 1, d)
}

function levelClass(count: number) {
  if (count <= 0) return 'bg-muted'
  if (count < 3) return 'bg-primary/25'
  if (count < 6) return 'bg-primary/45'
  if (count < 10) return 'bg-primary/65'
  return 'bg-primary'
}

interface Props {
  days: NoteDailyCount[]
  selected?: string | null
  onSelectDay?: (date: string) => void
}

/** 全年写作热力图 — 7 行 × N 周，按周排列，点击有笔记的格子可筛选 */
export function NoteHeatmap({ days, selected, onSelectDay }: Props) {
  const { cells, monthLabels } = useMemo(() => {
    if (!days.length) return { cells: [] as (NoteDailyCount | null)[], monthLabels: [] as (string | null)[] }
    const first = parseDay(days[0].date)
    const pad = first.getDay()
    const list: (NoteDailyCount | null)[] = [...Array(pad).fill(null), ...days]
    const weekCount = Math.ceil(list.length / 7)

    const labels: (string | null)[] = Array(weekCount).fill(null)
    let lastMonth = -1
    days.forEach((d, i) => {
      const date = parseDay(d.date)
      if (date.getDate() <= 7 && date.getMonth() !== lastMonth) {
        const week = Math.floor((i + pad) / 7)
        if (week >= 0 && week < weekCount && !labels[week]) {
          labels[week] = MONTHS[date.getMonth()]
          lastMonth = date.getMonth()
        }
      }
    })
    // 首周若已经是月初，也补一个月份标签
    if (first.getDate() <= 7 && !labels[0]) labels[0] = MONTHS[first.getMonth()]
    return { cells: list, monthLabels: labels }
  }, [days])

  if (!cells.length) {
    return <p className="py-8 text-center text-sm text-muted-foreground">还没有足够的记录</p>
  }

  return (
    <div className="overflow-x-auto pb-1 scrollbar-hide">
      <div className="min-w-[900px] sm:min-w-[820px]">
        {/* 月份标签 */}
        <div className="mb-1 flex gap-[3px] pl-7">
          {monthLabels.map((label, i) => (
            <div key={i} className="relative w-3.5 shrink-0 sm:w-3">
              {label && <span className="absolute left-0 top-0 whitespace-nowrap text-xs text-muted-foreground">{label}</span>}
            </div>
          ))}
        </div>

        <div className="flex gap-1">
          {/* 星期标签 */}
          <div className="flex w-7 shrink-0 flex-col gap-[3px]">
            {Array.from({ length: 7 }, (_, i) => (
              <span key={i} className="flex h-3.5 items-center text-[11px] leading-none text-muted-foreground sm:h-3">
                {WEEK_ROWS.includes(i) ? ['日', '一', '二', '三', '四', '五', '六'][i] : ''}
              </span>
            ))}
          </div>

          <div className="grid grid-flow-col grid-rows-7 gap-[3px]">
            {cells.map((cell, i) =>
              cell ? (
                <button
                  key={i}
                  type="button"
                  title={`${cell.date} · ${cell.count} 条`}
                  aria-label={`${cell.date}，${cell.count} 条笔记`}
                  disabled={cell.count === 0}
                  onClick={() => onSelectDay?.(cell.date)}
                  className={cn(
                    'h-3.5 w-3.5 rounded-[4px] transition-transform sm:h-3 sm:w-3',
                    levelClass(cell.count),
                    cell.count > 0 && 'hover:scale-125',
                    selected === cell.date && 'ring-2 ring-foreground/50 ring-offset-1 ring-offset-background'
                  )}
                />
              ) : (
                <span key={i} className="h-3.5 w-3.5 sm:h-3 sm:w-3" />
              )
            )}
          </div>
        </div>

        <div className="mt-2 flex items-center justify-end gap-1.5 text-xs text-muted-foreground">
          <span>少</span>
          {[0, 1, 3, 6, 10].map((n) => (
            <span key={n} className={cn('h-3.5 w-3.5 rounded-[4px] sm:h-3 sm:w-3', levelClass(n))} />
          ))}
          <span>多</span>
        </div>
      </div>
    </div>
  )
}
