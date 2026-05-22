import { useEffect, useState, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, MoreVertical, Trash2, ArrowUpDown, Filter, BookOpen, Sparkles, Star } from 'lucide-react'
import { getBookshelf, removeFromBookshelf } from '@/api/bookshelf'
import type { BookshelfItem } from '@/types/book'
import { parseFormatTags, formatProgress } from '@/types/book'
import { formatTag } from '@/utils/time'
import { toast } from 'sonner'

type SortKey = 'recent' | 'progress' | 'title' | 'addTime'
type FilterFormat = '' | 'TXT' | 'EPUB' | 'PDF'

export default function BookshelfPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<BookshelfItem[]>([])
  const [loading, setLoading] = useState(true)
  const [menuOpen, setMenuOpen] = useState<number | null>(null)
  const [sortKey, setSortKey] = useState<SortKey>('recent')
  const [filterFormat, setFilterFormat] = useState<FilterFormat>('')
  const [showSortMenu, setShowSortMenu] = useState(false)
  const [showFilterMenu, setShowFilterMenu] = useState(false)

  const fetchBookshelf = async () => {
    try {
      const res = await getBookshelf()
      setItems((res as any) || [])
    } catch {
      toast.error('加载书架失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchBookshelf()
  }, [])

  const handleRemove = async (bookId: number) => {
    try {
      await removeFromBookshelf(bookId)
      setItems((prev) => prev.filter((item) => item.bookId !== bookId))
      toast.success('已从书架移除')
      setMenuOpen(null)
    } catch {
      toast.error('暂时无法移除')
    }
  }

  const SORT_LABELS: Record<SortKey, string> = {
    recent: '最近阅读',
    progress: '阅读进度',
    title: '书名排序',
    addTime: '加入时间',
  }

  const filteredAndSorted = useMemo(() => {
    let list = [...items]
    if (filterFormat) {
      list = list.filter((item) => item.format === filterFormat)
    }
    list.sort((a, b) => {
      switch (sortKey) {
        case 'progress':
          return b.progress - a.progress
        case 'title':
          return a.title.localeCompare(b.title, 'zh-CN')
        case 'addTime':
          return b.bookshelfId - a.bookshelfId
        case 'recent':
        default:
          return b.bookshelfId - a.bookshelfId
      }
    })
    return list
  }, [items, sortKey, filterFormat])

  if (loading) {
    return (
      <div className="px-4 pt-safe-top page-enter">
        <header className="py-4">
          <h1 className="text-xl font-bold">我的书架</h1>
        </header>
        <div className="grid grid-cols-2 gap-3">
          {Array.from({ length: 4 }, (_, i) => (
            <div key={i} className="aspect-[3/4] w-full rounded-2xl bg-muted animate-pulse" />
          ))}
        </div>
      </div>
    )
  }

  if (items.length === 0) {
    return (
      <div className="px-4 pt-safe-top page-enter">
        <header className="py-4">
          <h1 className="text-xl font-bold">我的书架</h1>
        </header>
        <div className="flex h-[60vh] flex-col items-center justify-center">
          <div className="flex h-20 w-20 items-center justify-center rounded-2xl bg-muted">
            <BookOpen className="h-10 w-10 text-muted-foreground/40" />
          </div>
          <h3 className="mb-2 mt-4 text-base font-semibold">书架还是空的</h3>
          <p className="mb-6 text-sm text-muted-foreground">去首页发现好书吧</p>
          <button
            onClick={() => navigate('/home')}
            className="flex items-center gap-2 rounded-full bg-primary px-6 py-2.5 text-sm font-semibold text-primary-foreground shadow-md shadow-primary/20 active:scale-[0.97] transition-transform"
          >
            <Plus className="h-4 w-4" />
            去逛逛
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="px-4 pt-safe-top page-enter">
      <header className="py-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-bold">我的书架</h1>
            <p className="mt-1 text-xs text-muted-foreground font-medium">{items.length} 本{filterFormat ? ` · 筛选 ${formatTag(filterFormat)}` : ''}</p>
          </div>
          <div className="flex items-center gap-2">
            {/* 筛选按钮 */}
            <div className="relative">
              <button
                onClick={() => { setShowFilterMenu(!showFilterMenu); setShowSortMenu(false) }}
                className={`flex h-8 items-center gap-1 rounded-full px-3 text-xs font-medium transition-colors ${filterFormat ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'}`}
              >
                <Filter className="h-3.5 w-3.5" />
                {filterFormat ? formatTag(filterFormat) : '筛选'}
              </button>
              {showFilterMenu && (
                <div className="absolute right-0 top-10 z-20 w-28 rounded-xl bg-popover p-1 shadow-lg ring-1 ring-border/50">
                  {(['', 'TXT', 'EPUB', 'PDF'] as FilterFormat[]).map((fmt) => (
                    <button
                      key={fmt}
                      onClick={() => { setFilterFormat(fmt); setShowFilterMenu(false) }}
                      className={`flex w-full items-center rounded-lg px-3 py-2 text-xs font-medium transition-colors ${filterFormat === fmt ? 'bg-primary/10 text-primary' : 'text-foreground hover:bg-muted'}`}
                    >
                      {fmt ? formatTag(fmt) : '全部格式'}
                    </button>
                  ))}
                </div>
              )}
            </div>
            {/* 排序按钮 */}
            <div className="relative">
              <button
                onClick={() => { setShowSortMenu(!showSortMenu); setShowFilterMenu(false) }}
                className="flex h-8 items-center gap-1 rounded-full bg-muted px-3 text-xs font-medium text-muted-foreground"
              >
                <ArrowUpDown className="h-3.5 w-3.5" />
                {SORT_LABELS[sortKey]}
              </button>
              {showSortMenu && (
                <div className="absolute right-0 top-10 z-20 w-28 rounded-xl bg-popover p-1 shadow-lg ring-1 ring-border/50">
                  {(Object.keys(SORT_LABELS) as SortKey[]).map((key) => (
                    <button
                      key={key}
                      onClick={() => { setSortKey(key); setShowSortMenu(false) }}
                      className={`flex w-full items-center rounded-lg px-3 py-2 text-xs font-medium transition-colors ${sortKey === key ? 'bg-primary/10 text-primary' : 'text-foreground hover:bg-muted'}`}
                    >
                      {SORT_LABELS[key]}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </header>

      {filteredAndSorted.length === 0 ? (
        <div className="flex h-[40vh] flex-col items-center justify-center">
          <BookOpen className="h-10 w-10 text-muted-foreground/40" />
          <p className="mt-3 text-sm text-muted-foreground">当前筛选无结果</p>
          <button onClick={() => setFilterFormat('')} className="mt-2 text-xs text-primary">清除筛选</button>
        </div>
      ) : (
        <div className="grid grid-cols-3 gap-2">
          {filteredAndSorted.map((item) => {
            parseFormatTags(item.formatTags);
            const pct = Math.round(Math.max(0, item.matchScore ?? 0) * 100)
            return (
              <div
                key={item.bookshelfId}
                className="group relative flex flex-col active:scale-[0.97] transition-transform duration-150"
                onClick={() => navigate(`/book/${item.bookId}`)}
              >
                {/* 封面容器 */}
                <div className="relative aspect-[3/4] w-full overflow-hidden rounded-xl border border-border/50 shadow-sm">
                  <div
                    className="absolute inset-0 bg-cover bg-center"
                    style={{ backgroundImage: `url(${item.coverUrl || ''})` }}
                  />
                  {!item.coverUrl && (
                    <div className="absolute inset-0 flex items-center justify-center bg-gradient-to-br from-primary/10 to-primary/5">
                      <BookOpen className="h-10 w-10 text-primary/30" />
                    </div>
                  )}
                  <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent" />

                  {/* 更多按钮 */}
                  <button
                    className="absolute right-1.5 top-1.5 z-10 flex h-6 w-6 items-center justify-center rounded-full bg-black/30 opacity-0 backdrop-blur-sm transition-opacity group-hover:opacity-100"
                    onClick={(e) => {
                      e.stopPropagation()
                      setMenuOpen(menuOpen === item.bookId ? null : item.bookId)
                    }}
                  >
                    <MoreVertical className="h-3 w-3 text-white" />
                  </button>

                  {/* 右键菜单 */}
                  {menuOpen === item.bookId && (
                    <div className="absolute right-0 top-8 z-10 w-28 rounded-xl bg-popover p-1 shadow-lg ring-1 ring-border/50">
                      <button
                        className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-xs text-destructive hover:bg-destructive/10"
                        onClick={(e) => {
                          e.stopPropagation()
                          handleRemove(item.bookId)
                        }}
                      >
                        <Trash2 className="h-3 w-3" />
                        移出书架
                      </button>
                    </div>
                  )}

                  {/* 底部评分 & 匹配度 */}
                  <div className="absolute bottom-1.5 left-1.5 right-1.5 flex items-center gap-1">
                    {item.rating != null && item.rating >= 0 && (
                      <span className="inline-flex items-center gap-0.5 rounded-md bg-black/40 px-1 py-0.5 text-[9px] font-semibold text-amber-400 backdrop-blur-sm">
                        <Star className="h-2 w-2" />
                        {Number(item.rating.toFixed(1))}
                      </span>
                    )}
                    <span className="inline-flex items-center gap-0.5 rounded-md bg-black/40 px-1 py-0.5 text-[9px] font-semibold text-orange-400 backdrop-blur-sm">
                      <Sparkles className="h-2 w-2" />
                      {pct}%
                    </span>
                  </div>
                </div>

                {/* 书名 — 固定两行 */}
                <p className="mt-1.5 px-0.5 text-xs font-semibold leading-tight line-clamp-2">
                  {item.title}
                </p>

                {/* 作者 & 进度 */}
                <div className="mt-0.5 flex items-center justify-between px-0.5">
                  {item.author ? (
                    <p className="truncate text-[10px] text-muted-foreground">{item.author}</p>
                  ) : <div />}
                  <p className="shrink-0 text-[10px] text-primary font-medium">
                    {item.progress >= 1 ? '已读完' : formatProgress(item.progress)}
                  </p>
                </div>

                {/* 进度条 */}
                <div className="mt-1 h-1 overflow-hidden rounded-full bg-muted">
                  <div
                    className="h-full rounded-full bg-gradient-to-r from-primary to-primary/70 transition-all"
                    style={{ width: `${Math.round(item.progress * 100)}%` }}
                  />
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
