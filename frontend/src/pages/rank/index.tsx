import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {Flame, Award, Sparkles, Tag, Clock, Star, ChevronDown, ChevronUp, TrendingUp} from 'lucide-react'
import { getReadRank, getRatingRank, getNewBooksRank } from '@/api/book'
import type { Book } from '@/types/book'
import { parseFormatTags } from '@/types/book'
import BookCover from '@/components/book/BookCover'
import { useMatchScores } from '@/hooks/useMatchScores'

type RankType = 'read' | 'rating' | 'new'

const RANK_TABS: { key: RankType; label: string; icon: React.ReactNode }[] = [
  { key: 'read', label: '热门阅读', icon: <Flame className="h-4 w-4" /> },
  { key: 'rating', label: '高分推荐', icon: <Award className="h-4 w-4" /> },
  { key: 'new', label: '新书速递', icon: <Clock className="h-4 w-4" /> },
]

/** 格式化阅读量 */
function fmtReadCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万次阅读`
  return `${n}次阅读`
}

/** 简介展开/收起组件 */
function BookDescription({ description }: { description: string }) {
  const [expanded, setExpanded] = useState(false)
  const isLong = description.length > 80

  return (
    <div className="mt-2 border-t border-border/30 pt-2">
      <p
        className={`text-[11px] text-muted-foreground/70 leading-relaxed transition-all duration-200 ${
          expanded ? '' : 'line-clamp-2'
        }`}
      >
        {description}
      </p>
      {isLong && (
        <button
          onClick={(e) => {
            e.stopPropagation()
            setExpanded(!expanded)
          }}
          className="mt-1 flex items-center gap-0.5 text-[10px] text-primary/80 hover:text-primary font-medium"
        >
          {expanded ? (
            <>
              <ChevronUp className="h-3 w-3" />
              收起
            </>
          ) : (
            <>
              <ChevronDown className="h-3 w-3" />
              展开
            </>
          )}
        </button>
      )}
    </div>
  )
}

/** 评分徽章（带中文标签） — 5分制分等级配色（无背景） */
function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))

  let colorClass = ''
  if (r >= 5.0) {
    colorClass = 'text-red-600 dark:text-red-400'
  } else if (r >= 4.5) {
    colorClass = 'text-orange-600 dark:text-orange-400'
  } else if (r >= 4.0) {
    colorClass = 'text-amber-600 dark:text-amber-400'
  } else if (r >= 3.0) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
  } else if (r >= 2.5) {
    colorClass = 'text-teal-600 dark:text-teal-400'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass}`}>
      <Star className="h-2.5 w-2.5" />
      评分：{r}
    </span>
  )
}

/** 匹配度徽章（带中文标签） — 根据匹配度分等级配色（无背景） */
function MatchBadgeCN({ score }: { score: number | undefined | null }) {
  const pct = Math.round(Math.max(0, score ?? 0) * 100)

  let colorClass = ''
  if (pct >= 100) {
    colorClass = 'text-red-600 dark:text-red-400'
  } else if (pct >= 80) {
    colorClass = 'text-orange-600 dark:text-orange-400'
  } else if (pct >= 60) {
    colorClass = 'text-amber-600 dark:text-amber-400'
  } else if (pct >= 50) {
    colorClass = 'text-emerald-600 dark:text-emerald-400'
  } else if (pct >= 40) {
    colorClass = 'text-teal-600 dark:text-teal-400'
  } else {
    colorClass = 'text-slate-400 dark:text-slate-500'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass}`}>
      <Sparkles className="h-2.5 w-2.5" />
      匹配度：{pct}%
    </span>
  )
}

export default function RankPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const urlType = searchParams.get('type') as RankType | null
  const [type, setType] = useState<RankType>(
    urlType && ['read', 'rating', 'new'].includes(urlType) ? urlType : 'read'
  )
  const [books, setBooks] = useState<Book[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    const fetcher = type === 'read' ? getReadRank
      : type === 'rating' ? getRatingRank
      : getNewBooksRank
    fetcher(1, 50)
      .then((res) => setBooks((res as any)?.list || []))
      .catch(() => setBooks([]))
      .finally(() => setLoading(false))
  }, [type])

  const handleTypeChange = (key: RankType) => {
    window.scrollTo(0, 0)
    setType(key)
  }

  const matchScores = useMatchScores(books.map(b => b.id))

  return (
    <div className="page-enter">
      {/* 顶部头部 + Tab */}
      <div className="fixed top-0 left-0 right-0 z-50 bg-gradient-to-b from-background/95 via-background/80 to-background/60 pt-safe-top backdrop-blur-xl border-b border-border/30">
        <div className="px-4">
          <header className="py-4">
            <h1 className="text-xl font-bold">发现好书</h1>
          </header>
          <div className="mb-4 flex gap-2">
            {RANK_TABS.map((tab) => (
              <button
                key={tab.key}
                onClick={() => handleTypeChange(tab.key)}
                className={`flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-4 py-2 text-sm font-medium transition-colors ${
                  type === tab.key ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground'
                }`}
              >
                {tab.icon}
                {tab.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="h-[140px]" />

      <div className="px-4 pb-8">
        {loading ? (
          <div className="space-y-3">
            {Array.from({ length: 8 }, (_, i) => (
              <div key={i} className="rounded-2xl bg-card p-3 shadow-sm border border-border/50">
                <div className="flex gap-3">
                  <div className="h-7 w-7 flex-shrink-0 rounded-xl bg-muted animate-pulse" />
                  <div className="flex-1">
                    <div className="flex gap-3">
                      <div className="h-24 w-16 flex-shrink-0 rounded-lg bg-muted animate-pulse" />
                      <div className="flex-1 space-y-2">
                        <div className="h-4 w-3/4 rounded bg-muted animate-pulse" />
                        <div className="h-3 w-1/2 rounded bg-muted animate-pulse" />
                        <div className="h-3 w-full rounded bg-muted animate-pulse" />
                      </div>
                    </div>
                    <div className="mt-2 h-4 w-full rounded bg-muted animate-pulse" />
                    <div className="mt-1 h-4 w-4/5 rounded bg-muted animate-pulse" />
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : books.length > 0 ? (
          <div className="space-y-2.5">
            {books.map((book, index) => {
              const ms = matchScores?.[String(book.id)]
              const tags = parseFormatTags(book.formatTags)
              return (
                <div
                  key={book.id}
                  className="rounded-2xl bg-card p-3 shadow-sm border border-border/50 cursor-pointer active:scale-[0.98] transition-all duration-150"
                  onClick={() => navigate(`/book/${book.id}`)}
                >
                  <div className="flex gap-3">
                    {/* 排名 */}
                    <span className={`flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-xl text-xs font-bold shadow-sm ${
                      index === 0 ? 'bg-amber-400 text-white' :
                      index === 1 ? 'bg-zinc-400 text-white' :
                      index === 2 ? 'bg-orange-400 text-white' :
                      'bg-muted text-muted-foreground'
                    }`}>
                      {index + 1}
                    </span>

                    {/* 封面 + 信息 + 简介 */}
                    <div className="flex-1 min-w-0">
                      <div className="flex gap-3">
                        {/* 封面 */}
                        <BookCover
                          coverUrl={book.coverUrl}
                          title={book.title}
                          author={book.author}
                          format={book.format}
                          size="md"
                          className="flex-shrink-0"
                        />

                        {/* 信息区 */}
                        <div className="flex-1 min-w-0 flex flex-col justify-between">
                          <div>
                            <p className="truncate text-sm font-semibold">{book.title}</p>
                            <p className="mt-0.5 truncate text-xs text-muted-foreground">
                              {book.author || '未知作者'}
                            </p>
                          </div>

                          {/* 评分 + 匹配度 + 阅读量 */}
                          <div className="mt-1.5 flex items-center gap-2 flex-wrap">
                            <RatingBadgeCN rating={book.rating} />
                            <MatchBadgeCN score={ms} />
                            <span className="text-[11px] text-muted-foreground">
                              {fmtReadCount(book.readCount)}
                            </span>
                          </div>

                          {/* 标签 — 另起一行 */}
                          {tags.length > 0 && (
                            <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
                              {tags.slice(0, 3).map((tag) => (
                                <span key={tag} className="inline-flex items-center gap-0.5 rounded-md bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
                                  <Tag className="h-2.5 w-2.5" />
                                  {tag}
                                </span>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>

                      {/* 简介 — 点击展开/收起 */}
                      {book.description && (
                        <BookDescription description={book.description} />
                      )}
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        ) : (
          <div className="flex h-[50vh] flex-col items-center justify-center">
            <TrendingUp className="mb-4 h-12 w-12 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">还没有相关数据</p>
          </div>
        )}
      </div>
    </div>
  )
}
