import { useState, useCallback, useRef, useMemo } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { ArrowLeft, Search, X, ChevronDown, ChevronUp } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { searchBooks } from '@/api/book'
import { getHomeTags } from '@/api/home'
import { BookCard } from '@/components/book/BookCard'
import { Card } from '@/components/ui/card'

import { reportProgress, getProgressBatch } from '@/api/progress'

/** 标签筛选栏 — 一行展示+展开全部 */
function TagFilterBar({
  tags,
  activeTag,
  onTagChange,
}: {
  tags: string[]
  activeTag: string
  onTagChange: (tag: string) => void
}) {
  const [expanded, setExpanded] = useState(false)
  const allTags = ['全部', ...tags]
  const needExpand = allTags.length > 10

  const handleTagClick = (t: string) => {
    onTagChange(t === '全部' ? '' : t)
    if (expanded) setExpanded(false)
  }

  return (
    <div className="border-b border-border/50 bg-navbar">
      {/* 单行模式：前10个标签 + 展开按钮 */}
      {!expanded && (
        <div className="relative flex items-center">
          <div className="flex gap-2 overflow-x-auto scrollbar-hide px-4 py-2.5 pr-16" style={{ scrollbarWidth: 'none' }}>
            {allTags.slice(0, 10).map((t) => {
              const isActive = (t === '全部' && activeTag === '') || activeTag === t
              return (
                <button
                  key={t}
                  onClick={() => handleTagClick(t)}
                  className={`shrink-0 whitespace-nowrap rounded-full px-3.5 py-1.5 text-xs font-medium transition-all duration-200 ${
                    isActive
                      ? 'bg-primary text-primary-foreground shadow-sm shadow-primary/20'
                      : 'bg-muted text-muted-foreground hover:bg-muted/80 hover:text-foreground'
                  }`}
                >
                  {t}
                </button>
              )
            })}
            </div>
          {needExpand && (
            <button
              onClick={() => setExpanded(true)}
              className="absolute right-0 top-0 bottom-0 flex items-center gap-0.5 bg-gradient-to-l from-navbar via-navbar/95 to-transparent pl-8 pr-4 text-xs font-semibold text-primary hover:text-primary/80 transition-colors"
            >
              展开
              <ChevronDown className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      )}

      {/* 展开模式：全部标签网格展示，限制高度可滚动 */}
      {expanded && (
        <div className="px-4 py-3">
          <div className="flex flex-wrap gap-2 max-h-[240px] overflow-y-auto overscroll-y-contain" style={{ scrollbarWidth: 'thin' }}>
            {allTags.map((t) => {
              const isActive = (t === '全部' && activeTag === '') || activeTag === t
              return (
                <button
                  key={t}
                  onClick={() => handleTagClick(t)}
                  className={`shrink-0 whitespace-nowrap rounded-full px-3.5 py-1.5 text-xs font-medium transition-all duration-200 ${
                    isActive
                      ? 'bg-primary text-primary-foreground shadow-sm shadow-primary/20'
                      : 'bg-muted text-muted-foreground hover:bg-muted/80 hover:text-foreground'
                  }`}
                >
                  {t}
                </button>
              )
            })}
            </div>
          <button
            onClick={() => setExpanded(false)}
            className="mt-2 flex items-center gap-0.5 text-xs text-muted-foreground hover:text-primary transition-colors mx-auto"
          >
            <ChevronUp className="h-3.5 w-3.5" />
            收起
          </button>
        </div>
      )}
    </div>
  )
}

/** 根据进度值判断阅读状态 */
function getReadingStatus(progress: number | null | undefined): string | null {
  if (progress == null) return null
  if (progress <= 0) return 'WANT'
  if (progress >= 1) return 'READ'
  return 'READING'
}

export default function SearchPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()
  const [searchParams] = useSearchParams()

  const urlKw = searchParams.get('keyword')
  const urlTag = searchParams.get('tag')

  const [keyword, setKeyword] = useState(urlKw ? decodeURIComponent(urlKw) : '')
  const [tag, setTag] = useState<string>(urlTag || '')
  const [searchKeyword, setSearchKeyword] = useState(urlKw ? decodeURIComponent(urlKw) : (urlTag ? '' : ''))
  const [suggests, setSuggests] = useState<string[]>([])
  const [showSuggest, setShowSuggest] = useState(false)
  const suggestTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)
  const [searchTriggerKey, setSearchTriggerKey] = useState(0)

  // --- React Query ---

  const { data: rawResult, isLoading: loading, isFetched: searched } = useQuery({
    queryKey: ['search', searchKeyword, tag],
    queryFn: () => searchBooks({ keyword: searchKeyword || undefined, tag: tag || undefined, page: 1, size: 50 }),
    enabled: !!searchKeyword || !!tag,
  })
  const results = (rawResult as any)?.list ?? []

  const { data: popularTagObjs } = useQuery({
    queryKey: ['popular-tags'],
    queryFn: async () => {
      const data = await getHomeTags()
      return data.map(c => c.name)
    },
    staleTime: 5 * 60 * 1000,
  })
  const popularTags = popularTagObjs ?? []

  const bookIds = useMemo(() => results.map((b: any) => b.id as number).filter(Boolean), [results])
  const [optimisticProgress, setOptimisticProgress] = useState<Record<number, number>>({})
  const { data: fetchedProgress = {} } = useQuery({
    queryKey: ['search-progress', ...bookIds],
    queryFn: async () => {
      const data = await getProgressBatch(bookIds)
      const map: Record<number, number> = {}
      for (const [id, p] of Object.entries(data || {})) {
        const rp = p as { progress?: number }
        map[Number(id)] = rp.progress ?? 0
      }
      return map
    },
    enabled: bookIds.length > 0,
  })
  const progressMap = useMemo(() => ({ ...fetchedProgress, ...optimisticProgress }), [fetchedProgress, optimisticProgress])



  /** 处理阅读状态变更 */
  const handleStatusChange = useCallback(async (bookId: number, status: string) => {
    let newProgress: number
    switch (status) {
      case 'WANT': newProgress = 0; break
      case 'READING': newProgress = 0.01; break
      case 'READ': newProgress = 1; break
      default: return
    }
    try {
      await reportProgress({ bookId, progress: newProgress, currentPosition: null })
      setOptimisticProgress(prev => ({ ...prev, [bookId]: newProgress }))
      const labels: Record<string, string> = { WANT: '想读', READING: '在读', READ: '已读' }
      toast.success(`已标记为「${labels[status]}」`)
    } catch {
      toast.error('操作失败')
    }
  }, [])

  // NOTE: Initial search from URL params is handled by useQuery
  // (searchKeyword and tag are initialized from URL params above)

  const doSearch = useCallback((kw: string, t: string) => {
    if (!kw && !t) return
    setSearchKeyword(kw)
    setTag(t)
    setSearchTriggerKey(prev => prev + 1)
    setShowSuggest(false)
    searchInputRef.current?.blur()
  }, [])

  const handleInputChange = (value: string) => {
    setKeyword(value)
    if (suggestTimer.current) clearTimeout(suggestTimer.current)
    if (!value.trim()) {
      setSuggests([])
      setShowSuggest(false)
      return
    }
    suggestTimer.current = setTimeout(async () => {
      try {
        const token = localStorage.getItem(import.meta.env.VITE_TOKEN_KEY || 'kbook_token')
        const res = await fetch(
          `${import.meta.env.VITE_API_BASE_URL || '/api'}/books/suggest?keyword=${encodeURIComponent(value)}`,
          { headers: { Authorization: token ? `Bearer ${token}` : '' } }
        )
        if (res.ok) {
          const json = await res.json()
          setSuggests(json.data || [])
          setShowSuggest(true)
        }
      } catch { /* ignore */ }
    }, 300)
  }

  const handleSearch = () => doSearch(keyword, tag)
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSearch()
  }

  const highlight = (text: string) => {
    if (!keyword.trim() || !text) return text
    const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    return text.replace(new RegExp(`(${escaped})`, 'gi'), '<mark class="bg-primary/20 text-foreground rounded px-0.5">$1</mark>')
  }

  const handleTagChange = (t: string) => {
    const newTag = t === '全部' ? '' : t
    if (scrollRef.current) scrollRef.current.scrollTop = 0
    doSearch(keyword, newTag)
  }

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background page-enter">
      {/* 顶部固定区域：搜索框 + 筛选标签 */}
      <div className="shrink-0 z-10 bg-navbar/95 backdrop-blur-xl">
        <header className="flex items-center gap-2 border-b border-border/50 px-4 md:px-6 lg:px-8 py-3">
          <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <div className="relative flex flex-1 items-center gap-2 rounded-xl border border-border bg-background px-3 py-2">
            <Search className="h-4 w-4 text-muted-foreground" />
            <input
              ref={searchInputRef}
              type="text"
              value={keyword}
              onChange={(e) => handleInputChange(e.target.value)}
              onKeyDown={handleKeyDown}
              onFocus={() => suggests.length > 0 && setShowSuggest(true)}
              onBlur={() => setTimeout(() => setShowSuggest(false), 200)}
              placeholder="搜索书籍、作者..."
              className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
              autoFocus
            />
            {keyword && (
              <button onClick={() => { setKeyword(''); setSuggests([]); setShowSuggest(false) }} className="text-muted-foreground">
                <X className="h-4 w-4" />
              </button>
            )}
            {showSuggest && suggests.length > 0 && (
              <div className="absolute left-0 right-0 top-full z-20 mt-1 rounded-xl border bg-background shadow-lg">
                {suggests.map((s, i) => (
                  <button
                    key={i}
                    className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-muted first:rounded-t-xl last:rounded-b-xl"
                    onClick={() => { setKeyword(s); setShowSuggest(false); doSearch(s, tag) }}
                  >
                    <Search className="h-3.5 w-3.5 text-muted-foreground" />
                    <span>{s}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
          <button onClick={handleSearch} className="text-sm font-medium text-primary">搜索</button>
        </header>

        {/* 热门标签筛选 — 固定在搜索框下方 */}
        {popularTags.length > 0 && (
          <TagFilterBar
            key={searchTriggerKey}
            tags={popularTags}
            activeTag={tag}
            onTagChange={handleTagChange}
          />
        )}
      </div>

      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 md:px-6 lg:px-8 py-4">
        {loading ? (
          <div className="columns-1 sm:columns-2 lg:columns-3 gap-3 space-y-3">
            {Array.from({ length: 5 }, (_, i) => (
              <Card key={i} padding="sm" className="break-inside-avoid">
                <div className="flex gap-3">
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
              </Card>
            ))}
          </div>
        ) : searched && results.length === 0 ? (
          <div className="flex h-[50vh] flex-col items-center justify-center">
            <Search className="mb-4 h-12 w-12 text-muted-foreground" />
            <h3 className="mb-2 text-base font-medium">未找到相关图书</h3>
            <p className="text-sm text-muted-foreground">试试其他关键词吧</p>
          </div>
        ) : results.length > 0 ? (
          <div className="columns-1 sm:columns-2 lg:columns-3 gap-3 space-y-3">
            {results.map((book: any) => {
              const readingStatus = getReadingStatus(progressMap[book.id])
              return (
                <BookCard
                  key={book.id}
                  book={book}
                  onClick={() => navigate(`/book/${book.id}`)}
                  highlight={highlight}
                  readingStatus={readingStatus}
                  onStatusChange={(status) => handleStatusChange(book.id, status)}
                />
              )
            })}
          </div>
        ) : (
          <div className="flex h-[50vh] flex-col items-center justify-center">
            <Search className="mb-4 h-12 w-12 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">输入关键词搜索图书</p>
          </div>
        )}
      </div>
    </div>
  )
}
