import { useState, useCallback, useRef, useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { ArrowLeft, Search, X, Star, Sparkles, Tag, ChevronDown, ChevronUp } from 'lucide-react'
import { useKeepAliveStore } from '@/store/keepAlive'

/** 评分徽章（带中文标签） — 5分制分等级配色（无背景） */
function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))

  let colorClass = ''
  if (r >= 5.0) {
    colorClass = 'text-danger dark:text-danger'
  } else if (r >= 4.5) {
    colorClass = 'text-warning dark:text-warning'
  } else if (r >= 4.0) {
    colorClass = 'text-warning dark:text-warning'
  } else if (r >= 3.0) {
    colorClass = 'text-success dark:text-success'
  } else if (r >= 2.5) {
    colorClass = 'text-success dark:text-success'
  } else {
    colorClass = 'text-muted-foreground dark:text-muted-foreground'
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
    colorClass = 'text-danger dark:text-danger'
  } else if (pct >= 80) {
    colorClass = 'text-warning dark:text-warning'
  } else if (pct >= 60) {
    colorClass = 'text-warning dark:text-warning'
  } else if (pct >= 50) {
    colorClass = 'text-success dark:text-success'
  } else if (pct >= 40) {
    colorClass = 'text-success dark:text-success'
  } else {
    colorClass = 'text-muted-foreground dark:text-muted-foreground'
  }

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${colorClass}`}>
      <Sparkles className="h-2.5 w-2.5" />
      匹配度：{pct}%
    </span>
  )
}
import { searchBooks } from '@/api/book'
import { parseFormatTags } from '@/types/book'
import BookCover from '@/components/book/BookCover'
import { useMatchScores } from '@/hooks/useMatchScores'

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
    <div className="border-b border-border/50 bg-card/30">
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
                      : 'bg-muted/60 text-muted-foreground hover:bg-muted hover:text-foreground'
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
              className="absolute right-0 top-0 bottom-0 flex items-center gap-0.5 bg-gradient-to-l from-background via-background/95 to-transparent pl-8 pr-4 text-xs font-semibold text-primary hover:text-primary/80 transition-colors"
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
                      : 'bg-muted/60 text-muted-foreground hover:bg-muted hover:text-foreground'
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

/** 格式化阅读量 */
function fmtReadCount(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万次阅读`
  return `${n}次阅读`
}

/** 格式化文件大小 */
function fmtFileSize(bytes: number | null | undefined): string {
  if (bytes == null || bytes <= 0) return ''
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`
}

const CACHE_KEY = '/search'
const CACHE_TTL = 5 * 60 * 1000

interface SearchCache {
  query: string
  tag: string
  results: any[]
  timestamp: number
}

export default function SearchPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()
  const [searchParams] = useSearchParams()
  const savePageData = useKeepAliveStore((s) => s.savePageData)
  const getPageData = useKeepAliveStore((s) => s.getPageData)

  const cached = getPageData<SearchCache>(CACHE_KEY)
  const isCacheValid = cached && Date.now() - cached.timestamp < CACHE_TTL

  const urlKw = searchParams.get('keyword')
  const urlTag = searchParams.get('tag')
  const initialKw = urlKw ? decodeURIComponent(urlKw) : ''
  const initialTag = urlTag || ''

  const cacheMatch = isCacheValid && cached.query === initialKw && cached.tag === initialTag

  const [keyword, setKeyword] = useState(initialKw || '')
  const [tag, setTag] = useState<string>(() => initialTag || searchParams.get('tag') || '')
  const [results, setResults] = useState<any[]>(() => cacheMatch ? cached.results : [])
  const [loading, setLoading] = useState(() => cacheMatch ? false : false)
  const [searched, setSearched] = useState(() => cacheMatch ? true : false)
  const [suggests, setSuggests] = useState<string[]>([])
  const [showSuggest, setShowSuggest] = useState(false)
  const [popularTags, setPopularTags] = useState<string[]>([])
  const suggestTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)
  const [searchTriggerKey, setSearchTriggerKey] = useState(0)

  // 从 URL 参数自动搜索（如从首页热门标签跳转 / AI 对话点击书名）
  useEffect(() => {
    const kw = searchParams.get('keyword')
    const t = searchParams.get('tag')
    if (cacheMatch) return
    if (kw) {
      const decoded = decodeURIComponent(kw)
      setKeyword(decoded)
      doSearch(decoded, t || '')
    } else if (t) {
      doSearch('', t)
    }
    loadPopularTags()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const loadPopularTags = async () => {
    try {
      const token = localStorage.getItem(import.meta.env.VITE_TOKEN_KEY || 'kbook_token')
      const res = await fetch(
        `${import.meta.env.VITE_API_BASE_URL || '/api'}/home/tags`,
        { headers: { Authorization: token ? `Bearer ${token}` : '' } }
      )
      if (res.ok) {
        const json = await res.json()
        const tags = json?.data || []
        setPopularTags(tags.map((c: any) => c.name))
      }
    } catch { /* ignore */ }
  }

  const doSearch = useCallback(async (kw: string, t: string) => {
    if (!kw && !t) return
    setSearchTriggerKey(prev => prev + 1)
    searchInputRef.current?.blur()
    setLoading(true)
    setSearched(true)
    setShowSuggest(false)
    try {
      const res = await searchBooks({
        keyword: kw || undefined,
        tag: t || undefined,
        page: 1,
        size: 50,
      })
      const list = (res as any)?.list || []
      setResults(list)
      savePageData(CACHE_KEY, { query: kw, tag: t, results: list, timestamp: Date.now() })
    } catch {
      setResults([])
    } finally {
      setLoading(false)
    }
  }, [savePageData])

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

  // 搜索结果的匹配分
  const resultIds = results.map((b: any) => b.id as number)
  const matchScores = useMatchScores(resultIds)

  const highlight = (text: string) => {
    if (!keyword.trim() || !text) return text
    const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    return text.replace(new RegExp(`(${escaped})`, 'gi'), '<mark class="bg-primary/20 text-foreground rounded px-0.5">$1</mark>')
  }

  const handleTagChange = (t: string) => {
    setTag(t === '全部' ? '' : t)
    if (scrollRef.current) scrollRef.current.scrollTop = 0
    doSearch(keyword, t === '全部' ? '' : t)
  }

  return (
    <div className="fixed inset-0 flex flex-col overflow-hidden bg-background page-enter">
      {/* 顶部固定区域：搜索框 + 筛选标签 */}
      <div className="shrink-0 z-10 bg-background/80 backdrop-blur-xl">
        <header className="flex items-center gap-2 border-b border-border/50 px-4 py-3">
          <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <div className="relative flex flex-1 items-center gap-2 rounded-xl bg-muted px-3 py-2">
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

      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain p-4">
        {loading ? (
          <div className="space-y-3">
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i} className="rounded-2xl bg-card p-3 shadow-sm border border-border/50">
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
              </div>
            ))}
          </div>
        ) : searched && results.length === 0 ? (
          <div className="flex h-[50vh] flex-col items-center justify-center">
            <Search className="mb-4 h-12 w-12 text-muted-foreground" />
            <h3 className="mb-2 text-base font-medium">未找到相关图书</h3>
            <p className="text-sm text-muted-foreground">试试其他关键词吧</p>
          </div>
        ) : results.length > 0 ? (
          <div className="space-y-2.5">
            {results.map((book: any) => {
              const tags = parseFormatTags(book.formatTags)
              const ms = matchScores?.[String(book.id)]
              return (
                <div
                  key={book.id}
                  className="rounded-2xl bg-card p-3 shadow-sm border border-border/50 cursor-pointer active:scale-[0.98] transition-all duration-150"
                  onClick={() => navigate(`/book/${book.id}`)}
                >
                  <div className="flex gap-3">
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
                            <p className="truncate text-sm font-semibold" dangerouslySetInnerHTML={{ __html: highlight(book.title) }} />
                            <p className="mt-0.5 truncate text-xs text-muted-foreground">
                              {book.author ? <span dangerouslySetInnerHTML={{ __html: highlight(book.author) }} /> : '未知作者'}
                            </p>
                          </div>

                          {/* 评分 + 匹配度 + 阅读量 + 文件大小 */}
                          <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
                            <RatingBadgeCN rating={book.rating} />
                            <MatchBadgeCN score={ms} />
                            <span className="text-[11px] text-muted-foreground">
                              {fmtReadCount(book.readCount)}
                            </span>
                            {fmtFileSize(book.fileSize) && (
                              <span className="text-[11px] text-muted-foreground">
                                {fmtFileSize(book.fileSize)}
                              </span>
                            )}
                          </div>

                          {/* 标签 */}
                          {tags.length > 0 && (
                            <div className="mt-1.5 flex items-center gap-1.5 overflow-x-auto scrollbar-hide">
                              {tags.map((t) => (
                                <span
                                  key={t}
                                  className="inline-flex items-center gap-0.5 rounded-md bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary flex-shrink-0"
                                >
                                  <Tag className="h-2.5 w-2.5" />
                                  {t}
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
            <Search className="mb-4 h-12 w-12 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">输入关键词搜索图书</p>
          </div>
        )}
      </div>
    </div>
  )
}
