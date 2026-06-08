import { Star } from 'lucide-react'

export function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))
  let colorClass = ''
  if (r >= 5.0) colorClass = 'text-red-600 dark:text-red-400'
  else if (r >= 4.5) colorClass = 'text-orange-600 dark:text-orange-400'
  else if (r >= 4.0) colorClass = 'text-warning dark:text-warning'
  else if (r >= 3.0) colorClass = 'text-success dark:text-success'
  else if (r >= 2.5) colorClass = 'text-teal-600 dark:text-teal-400'
  else colorClass = 'text-slate-400 dark:text-slate-500'

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-semibold ${colorClass}`}>
      <Star className="h-3 w-3" />
      评分：{r}
    </span>
  )
}
