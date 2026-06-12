import { useEffect, useState, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, MoreVertical, Trash2, ArrowUpDown, Filter, BookOpen } from 'lucide-react'
import { getBookshelf, removeFromBookshelf } from '@/api/bookshelf'
import type { BookshelfItem } from '@/types/book'
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
      <div className="px-4 md:px-6 lg:px-8 page-enter pb-20 md:pb-6">
        <header className="sticky top-0 z-10 -mx-4 md:-mx-6 lg:-mx-8 px-4 md:px-6 lg:px-8 pt-safe-top pb-3 bg-background/80 backdrop-blur-xl border-b border-border/50">
          <h1 className="text-lg font-bold">我的书架</h1>
        </header>
        <div className="grid grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3 mt-4">
          {Array.from({ length: 6 }, (_, i) => (
            <div key={i} className="space-y-3">
              <div className="aspect-[3/4] w-full rounded-2xl skeleton" />
              <div className="h-4 w-3/4 skeleton rounded" />
              <div className="h-3 w-1/2 skeleton rounded" />
            </div>
          ))}
        </div>
      </div>
    )
  }

  if (items.length === 0) {
    return (
      <div className="px-4 md:px-6 lg:px-8 page-enter pb-20 md:pb-6">
        <header className="-mx-4 md:-mx-6 lg:-mx-8 px-4 md:px-6 lg:px-8 pt-safe-top pb-3 bg-background/80 backdrop-blur-xl border-b border-border/50">
          <h1 className="text-lg font-bold">我的书架</h1>
        </header>
        <div className="flex h-[60vh] flex-col items-center justify-center">
          <div className="flex h-20 w-20 items-center justify-center rounded-2xl bg-brand-50">
            <BookOpen className="h-10 w-10 text-brand-300" />
          </div>
          <h3 className="mb-2 mt-4 text-base font-semibold">书架还是空的</h3>
          <p className="mb-6 text-sm text-muted-foreground">去首页发现好书，或搜索你感兴趣的书</p>
          <button
            onClick={() => navigate('/home')}
            className="flex items-center gap-2 rounded-xl bg-primary px-6 py-2.5 text-sm font-semibold text-primary-foreground shadow-md active:scale-[0.97] transition-transform btn-press"
          >
            <Plus className="h-4 w-4" />
            去发现好书
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="px-4 md:px-6 lg:px-8 page-enter pb-20 md:pb-6">
      <header className="sticky top-0 z-10 -mx-4 md:-mx-6 lg:-mx-8 px-4 md:px-6 lg:px-8 pt-safe-top pb-3 bg-background/80 backdrop-blur-xl border-b border-border/50">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-lg font-bold">我的书架</h1>
            <p className="mt-0.5 text-xs text-muted-foreground font-medium">{items.length} 本{filterFormat ? ` · 筛选 ${formatTag(filterFormat)}` : ''}</p>
          </div>
          <div className="flex items-center gap-2">
            {/* 筛选按钮 */}
            <div className="relative">
              <button
                onClick={() => { setShowFilterMenu(!showFilterMenu); setShowSortMenu(false) }}
                className={`flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full px-3 py-1.5 text-xs font-medium transition-colors ${filterFormat ? 'bg-brand-100 text-brand-500' : 'bg-muted text-muted-foreground'}`}
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
                      className={`flex w-full items-center rounded-lg px-3 py-2 text-xs font-medium transition-colors ${filterFormat === fmt ? 'bg-brand-100 text-brand-500' : 'text-foreground hover:bg-muted'}`}
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
                className="flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full bg-muted px-3 py-1.5 text-xs font-medium text-muted-foreground"
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
                      className={`flex w-full items-center rounded-lg px-3 py-2 text-xs font-medium transition-colors ${sortKey === key ? 'bg-brand-100 text-brand-500' : 'text-foreground hover:bg-muted'}`}
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
        <div className="grid grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3 md:gap-4 mt-4">
          {filteredAndSorted.map((item, index) => (
            <div
              key={item.bookshelfId}
              className="group relative flex flex-col list-item-enter"
              style={{ animationDelay: `${index * 50}ms` }}
              onClick={() => navigate(`/book/${item.bookId}`)}
            >
              {/* 封面容器 */}
              <div className="relative aspect-[3/4] w-full overflow-hidden rounded-xl border border-border/50 shadow-sm transition-shadow duration-200 group-hover:shadow-md">
                <div
                  className="absolute inset-0 bg-cover bg-center"
                  style={{ backgroundImage: `url(${item.coverUrl || ''})` }}
                />
                {!item.coverUrl && (
                  <div className="absolute inset-0 flex items-center justify-center bg-gradient-to-br from-brand-50 to-brand-100">
                    <BookOpen className="h-12 w-12 text-brand-200" />
                  </div>
                )}
                <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent" />

                {/* 更多按钮 */}
                <button
                  className="absolute right-2 top-2 z-10 flex h-7 w-7 items-center justify-center rounded-full bg-black/30 opacity-0 backdrop-blur-sm transition-opacity group-hover:opacity-100"
                  onClick={(e) => {
                    e.stopPropagation()
                    setMenuOpen(menuOpen === item.bookId ? null : item.bookId)
                  }}
                >
                  <MoreVertical className="h-3.5 w-3.5 text-white" />
                </button>

                {/* 右键菜单 */}
                {menuOpen === item.bookId && (
                  <div className="absolute right-1 top-10 z-10 w-28 rounded-xl bg-popover p-1 shadow-lg ring-1 ring-border/50">
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


              </div>

              {/* 书名 */}
              <p className="mt-2 text-sm font-semibold leading-tight line-clamp-2">
                {item.title}
              </p>

              {/* 作者 */}
              {item.author && (
                <p className="mt-0.5 text-xs text-muted-foreground truncate">{item.author}</p>
              )}


            </div>
          ))}
        </div>
      )}
    </div>
  )
}
