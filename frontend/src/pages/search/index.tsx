import { useState, useCallback, useRef, useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Search, X, Star, Sparkles } from 'lucide-react'
import { searchBooks } from '@/api/book'
import type { Book } from '@/types/book'
import { parseFormatTags, formatFileSize } from '@/types/book'
import { BOOK_FORMAT } from '@/constants'
import BookCover from '@/components/book/BookCover'
import { useMatchScores } from '@/hooks/useMatchScores'

export default function SearchPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [keyword, setKeyword] = useState('')
  const [format, setFormat] = useState<string>(() => searchParams.get('format') || '')
  const [results, setResults] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)
  const [suggests, setSuggests] = useState<string[]>([])
  const [showSuggest, setShowSuggest] = useState(false)
  const suggestTimer = useRef<ReturnType<typeof setTimeout>>()

  // 从 URL 参数自动搜索（如从首页分类发现跳转）
  useEffect(() => {
    const fmt = searchParams.get('format')
    if (fmt) {
      doSearch('', fmt)
    }
  }, [])

  const doSearch = useCallback(async (kw: string, fmt: string) => {
    if (!kw && !fmt) return
    setLoading(true)
    setSearched(true)
    setShowSuggest(false)
    try {
      const res = await searchBooks({
        keyword: kw || undefined,
        format: fmt || undefined,
        page: 1,
        size: 50,
      })
      setResults(res?.list || [])
    } catch {
      setResults([])
    } finally {
      setLoading(false)
    }
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

  const handleSearch = () => doSearch(keyword, format)
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSearch()
  }

  const formats = [
    { value: '', label: '全部' },
    { value: BOOK_FORMAT.TXT, label: 'TXT' },
    { value: BOOK_FORMAT.EPUB, label: 'EPUB' },
    { value: BOOK_FORMAT.PDF, label: 'PDF' },
  ]

  // 搜索结果的匹配分
  const resultIds = results.map((b: any) => b.id as number)
  const matchScores = useMatchScores(resultIds)

  const highlight = (text: string) => {
    if (!keyword.trim() || !text) return text
    const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    return text.replace(new RegExp(`(${escaped})`, 'gi'), '<mark class="bg-primary/20 text-foreground rounded px-0.5">$1</mark>')
  }

  return (
    <div className="min-h-screen bg-background page-enter">
      <header className="sticky top-0 z-10 flex items-center gap-2 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div className="relative flex flex-1 items-center gap-2 rounded-xl bg-muted px-3 py-2">
          <Search className="h-4 w-4 text-muted-foreground" />
          <input
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
                  onClick={() => { setKeyword(s); setShowSuggest(false); doSearch(s, format) }}
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

      <div className="flex gap-2 border-b px-4 py-2">
        {formats.map((f) => (
          <button
            key={f.value}
            onClick={() => { setFormat(f.value); if (keyword || f.value) doSearch(keyword, f.value) }}
            className={`rounded-full px-4 py-1.5 text-xs font-medium transition-colors ${
              format === f.value ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'
            }`}
          >
            {f.label}
          </button>
        ))}
      </div>

      <div className="p-4">
        {loading ? (
          <div className="space-y-3">
            {Array.from({ length: 5 }, (_, i) => (
              <div key={i} className="flex items-center gap-3">
                <div className="h-16 w-12 flex-shrink-0 rounded bg-muted animate-pulse" />
                <div className="flex-1 space-y-2">
                  <div className="h-4 w-3/4 rounded bg-muted animate-pulse" />
                  <div className="h-3 w-1/2 rounded bg-muted animate-pulse" />
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
          <div className="space-y-3">
            <p className="text-xs text-muted-foreground">共找到相关结果</p>
            {results.map((book: any) => {
              const tags = parseFormatTags(book.formatTags)
              const ms = matchScores?.[String(book.id)]
              return (
                <div key={book.id} className="flex items-center gap-3 rounded-2xl bg-card p-3 shadow-sm border border-border/50" onClick={() => navigate(`/book/${book.id}`)}>
                  <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} format={book.format} size="md" className="flex-shrink-0" />
                  <div className="flex-1 min-w-0">
                    <p className="truncate text-sm font-medium" dangerouslySetInnerHTML={{ __html: highlight(book.title) }} />
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {book.author ? <span dangerouslySetInnerHTML={{ __html: highlight(book.author) }} /> : '未知作者'}
                    </p>
                    <div className="mt-1 flex items-center gap-2">
                      {book.rating > 0 && (
                        <div className="flex items-center gap-0.5">
                          <Star className="h-2.5 w-2.5 fill-amber-400 text-amber-400" />
                          <span className="text-[10px] font-semibold text-amber-600 dark:text-amber-400">{book.rating.toFixed(1)}</span>
                        </div>
                      )}
                      {ms != null && ms > 0 && (
                        <div className="flex items-center gap-0.5">
                          <Sparkles className="h-2.5 w-2.5 text-amber-500" />
                          <span className="text-[10px] font-semibold text-amber-600 dark:text-amber-400">{Math.round(ms * 100)}%</span>
                        </div>
                      )}
                      {tags.slice(0, 2).map((tag: string) => <span key={tag} className="text-[10px] text-muted-foreground">{tag}</span>)}
                      {book.fileSize && <span className="text-[10px] text-muted-foreground">{formatFileSize(book.fileSize)}</span>}
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
