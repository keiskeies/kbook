import { useState, useEffect, useCallback, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useKeepAliveStore } from '@/store/keepAlive'
import {
  Swords, CircleDot, BookOpen, ChevronRight,
  Trophy, BarChart3, Clock, Search, User, Eye,
  MessageSquare, Users, ChevronDown, ChevronUp,
} from 'lucide-react'
import { getGlobalDebateSessions } from '@/api/debate'
import { getGlobalRoundTableSessions } from '@/api/roundTable'
import { getHomeTags } from '@/api/home'
import { searchBooks, getHotRank, getMatchScores } from '@/api/book'
import type { Book } from '@/types/book'
import { DEBATE_PERSONALITY_NAMES, DEBATE_PERSONALITY_ICONS } from '@/types/debate'
import { ROLE_NAMES, ROLE_ICONS } from '@/types/roundTable'
import BookCover from '@/components/book/BookCover'
import { Card } from '@/components/ui/card'
import { BookCard } from '@/components/book/BookCard'

// ==================== 类型 ====================

interface DebateFeedItem {
  id: number
  sessionId: string
  bookId: number
  bookTitle: string
  bookCoverUrl: string | null
  topic: string
  proRoleKeys: string
  conRoleKeys: string
  currentRound: number
  currentPhase: string
  status: string
  visibility: string
  isOwner: boolean
  avgScore: number | null
  hotScore: number | null
  createdAt: string
}

interface RoundTableFeedItem {
  id: number
  sessionId: string
  bookId: number
  bookTitle: string
  bookCoverUrl: string | null
  title: string
  roleKeys: string
  status: string
  visibility: string
  isOwner: boolean
  coverageScore: number | null
  hotScore: number | null
  createdAt: string
}

type TabKey = 'debate' | 'roundtable' | 'books'
type SortKey = 'mine' | 'recent' | 'hot'

// ==================== 辅助函数 ====================

function parseRoleKeys(keys: string): string[] {
  if (!keys) return []
  return keys.split(',').map(k => k.trim()).filter(Boolean)
}

function formatTimeAgo(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin}分钟前`
  const diffHr = Math.floor(diffMin / 60)
  if (diffHr < 24) return `${diffHr}小时前`
  const diffDay = Math.floor(diffHr / 24)
  if (diffDay < 30) return `${diffDay}天前`
  return `${Math.floor(diffDay / 30)}个月前`
}

// ==================== 标签筛选栏（参考搜索页面）====================

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
  const needExpand = allTags.length > 20

  const handleTagClick = (t: string) => {
    onTagChange(t === '全部' ? '' : t)
    if (expanded) setExpanded(false)
  }

  return (
    <div>
      {/* 单行模式：前20个标签 + 展开按钮 */}
      {!expanded && (
        <div className="relative flex items-center">
          <div className="flex gap-2 overflow-x-auto scrollbar-hide px-4 md:px-6 lg:px-8 py-2.5 pr-16" style={{ scrollbarWidth: 'none' }}>
            {allTags.slice(0, 20).map((t) => {
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
              className="absolute right-0 top-0 bottom-0 flex items-center gap-0.5 bg-gradient-to-l from-navbar via-navbar/95 to-transparent pl-8 pr-4 md:pr-6 lg:pr-8 text-xs font-semibold text-primary hover:text-primary/80 transition-colors"
            >
              展开
              <ChevronDown className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      )}

      {/* 展开模式：全部标签网格展示 */}
      {expanded && (
        <div className="px-4 md:px-6 lg:px-8 py-3">
          <div className="flex flex-wrap gap-2 max-h-[240px] overflow-y-auto overscroll-y-contain" style={{ scrollbarWidth: 'thin' }}>
            {allTags.slice(0, 100).map((t) => {
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

// ==================== 骨架屏 ====================

function DebateSkeleton() {
  return (
    <div className="rounded-lg bg-card border border-border/50 overflow-hidden">
      <div className="flex gap-3 p-3">
        <div className="w-16 shrink-0 aspect-[3/4] skeleton rounded-none" />
        <div className="flex-1 space-y-2">
          <div className="h-4 w-full skeleton rounded" />
          <div className="h-4 w-3/4 skeleton rounded" />
          <div className="flex gap-2 mt-1">
            <div className="h-5 w-12 skeleton rounded-full" />
            <div className="h-5 w-12 skeleton rounded-full" />
          </div>
          <div className="flex gap-3 mt-2">
            <div className="h-4 w-16 skeleton rounded" />
            <div className="h-4 w-16 skeleton rounded" />
            <div className="h-4 w-12 skeleton rounded" />
          </div>
        </div>
      </div>
    </div>
  )
}

function RoundTableSkeleton() {
  return (
    <div className="rounded-lg bg-card border border-border/50 overflow-hidden">
      <div className="flex gap-3 p-3">
        <div className="w-16 shrink-0 aspect-[3/4] skeleton rounded-none" />
        <div className="flex-1 space-y-2">
          <div className="h-4 w-full skeleton rounded" />
          <div className="h-4 w-3/4 skeleton rounded" />
          <div className="flex gap-1.5 mt-1">
            <div className="h-5 w-14 skeleton rounded-full" />
            <div className="h-5 w-14 skeleton rounded-full" />
            <div className="h-5 w-14 skeleton rounded-full" />
          </div>
          <div className="flex gap-3 mt-2">
            <div className="h-4 w-20 skeleton rounded" />
            <div className="h-4 w-12 skeleton rounded" />
          </div>
        </div>
      </div>
    </div>
  )
}

function BookSkeleton() {
  return (
    <Card padding="none">
      <div className="flex gap-3 p-3 pb-2">
        {/* 封面 h-24 w-16 */}
        <div className="w-16 shrink-0 h-24 skeleton rounded-none" />
        <div className="flex-1 min-w-0 flex flex-col justify-between">
          <div>
            {/* 标题 text-sm */}
            <div className="h-4 w-3/4 skeleton rounded" />
            {/* 作者 text-xs mt-0.5 */}
            <div className="mt-0.5 h-3.5 w-1/2 skeleton rounded" />
            {/* 标签 text-xs px-1.5 py-0.5 */}
            <div className="mt-1.5 flex gap-1">
              <div className="h-5 w-10 skeleton rounded-md" />
              <div className="h-5 w-10 skeleton rounded-md" />
              <div className="h-5 w-10 skeleton rounded-md" />
            </div>
          </div>
          {/* 评分行 text-xs */}
          <div className="mt-1.5 flex gap-2">
            <div className="h-3.5 w-10 skeleton rounded" />
            <div className="h-3.5 w-14 skeleton rounded" />
          </div>
        </div>
      </div>
      {/* 简介 text-xs line-clamp-2 + 展开 */}
      <div className="border-t border-border/30 px-3 pb-2.5 pt-1.5">
        <div className="space-y-1">
          <div className="h-3.5 w-full skeleton rounded" />
          <div className="h-3.5 w-5/6 skeleton rounded" />
        </div>
        <div className="mt-1 h-3 w-8 skeleton rounded" />
      </div>
      {/* 底部按钮 py-2 */}
      <div className="flex items-center justify-end gap-1.5 border-t border-border/30 px-3 py-2">
        <div className="h-5 w-5 skeleton rounded" />
        <div className="h-5 w-5 skeleton rounded" />
        <div className="h-5 w-5 skeleton rounded" />
      </div>
    </Card>
  )
}

// ==================== 辩论卡片 ====================

function DebateCard({ item }: { item: DebateFeedItem }) {
  const navigate = useNavigate()
  const proRoles = parseRoleKeys(item.proRoleKeys)
  const conRoles = parseRoleKeys(item.conRoleKeys)
  const isCompleted = item.status === 'COMPLETED'

  const goToBook = (e: React.MouseEvent) => {
    e.stopPropagation()
    navigate(`/book/${item.bookId}`)
  }

  return (
    <div
      className="rounded-lg bg-card border border-border/50 shadow-sm overflow-hidden cursor-pointer btn-press hover:shadow-md transition-shadow"
      onClick={() => navigate(`/book/${item.bookId}/debate/sessions/${item.sessionId}`)}
    >
      <div className="flex gap-3 p-3">
        {/* 封面 - 点击进入图书详情页 */}
        <div className="w-16 shrink-0" onClick={goToBook}>
          <BookCover
            coverUrl={item.bookCoverUrl}
            title={item.bookTitle}
            size="md"
            className="!rounded-none"
          />
        </div>

        {/* 右侧信息 */}
        <div className="flex-1 min-w-0">
          {/* 辩题 - 两行 */}
          <p className="text-sm font-bold leading-snug line-clamp-2 min-h-[2.5em]">{item.topic}</p>

          {/* 书名 - 仅展示，点击进入辩论页 */}
          <span className="mt-0.5 text-xs text-brand-600 truncate block">
            📖 {item.bookTitle}
          </span>

          {/* 正反方 */}
          <div className="mt-1.5 flex items-center gap-2">
            <div className="flex items-center gap-1">
              <span className="text-xs font-semibold text-blue-500 bg-blue-500/10 rounded px-1.5 py-0.5">正方</span>
              <div className="flex -space-x-1">
                {proRoles.slice(0, 3).map(key => (
                  <span key={key} className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-blue-500/10 text-xs" title={DEBATE_PERSONALITY_NAMES[key] || key}>
                    {DEBATE_PERSONALITY_ICONS[key] || '👤'}
                  </span>
                ))}
              </div>
            </div>
            <Swords className="h-3 w-3 text-muted-foreground/40 shrink-0" />
            <div className="flex items-center gap-1">
              <span className="text-xs font-semibold text-red-500 bg-red-500/10 rounded px-1.5 py-0.5">反方</span>
              <div className="flex -space-x-1">
                {conRoles.slice(0, 3).map(key => (
                  <span key={key} className="inline-flex h-5 w-5 items-center justify-center rounded-full bg-red-500/10 text-xs" title={DEBATE_PERSONALITY_NAMES[key] || key}>
                    {DEBATE_PERSONALITY_ICONS[key] || '👤'}
                  </span>
                ))}
              </div>
            </div>
          </div>

          {/* 维度数据行 */}
          <div className="mt-2 flex items-center gap-2.5 text-xs text-muted-foreground">
            {/* 评分 - 突出显示 */}
            {isCompleted && item.avgScore != null && item.avgScore > 0 && (
              <span className="inline-flex items-center gap-0.5 rounded-md bg-warning/10 px-1.5 py-0.5 font-semibold text-warning">
                <Trophy className="h-3 w-3" />
                {item.avgScore.toFixed(1)}分
              </span>
            )}
            {/* 轮次/状态 */}
            <span className="inline-flex items-center gap-0.5">
              <BarChart3 className="h-3 w-3" />
              {isCompleted ? '已完结' : `第${item.currentRound}轮`}
            </span>
            {/* 消息数（角色数作为代理） */}
            <span className="inline-flex items-center gap-0.5">
              <MessageSquare className="h-3 w-3" />
              {proRoles.length + conRoles.length}人
            </span>
            {/* 时间 */}
            <span className="inline-flex items-center gap-0.5 ml-auto">
              <Clock className="h-3 w-3" />
              {formatTimeAgo(item.createdAt)}
            </span>
          </div>
        </div>
      </div>

      {/* 自己的标识 */}
      {item.isOwner && (
        <div className="border-t border-border/30 px-3 py-1 flex items-center gap-1">
          <User className="h-3 w-3 text-primary" />
          <span className="text-xs font-medium text-primary">我的辩论</span>
          {!isCompleted && (
            <span className="ml-auto text-xs font-medium text-primary bg-primary/10 rounded px-1.5 py-0.5">继续</span>
          )}
          {isCompleted && (
            <span className="ml-auto text-xs font-medium text-success bg-success/10 rounded px-1.5 py-0.5">查看报告</span>
          )}
        </div>
      )}
    </div>
  )
}

// ==================== 圆桌卡片 ====================

function RoundTableCard({ item }: { item: RoundTableFeedItem }) {
  const navigate = useNavigate()
  const roles = parseRoleKeys(item.roleKeys)
  const displayRoles = roles.filter(k => k !== 'HOST')

  const goToBook = (e: React.MouseEvent) => {
    e.stopPropagation()
    navigate(`/book/${item.bookId}`)
  }

  return (
    <div
      className="rounded-lg bg-card border border-border/50 shadow-sm overflow-hidden cursor-pointer btn-press hover:shadow-md transition-shadow"
      onClick={() => navigate(`/book/${item.bookId}/round-table/sessions/${item.sessionId}`)}
    >
      <div className="flex gap-3 p-3">
        {/* 封面 - 点击进入图书详情页 */}
        <div className="w-16 shrink-0" onClick={goToBook}>
          <BookCover
            coverUrl={item.bookCoverUrl}
            title={item.bookTitle}
            size="md"
            className="!rounded-none"
          />
        </div>

        {/* 右侧信息 */}
        <div className="flex-1 min-w-0">
          {/* 讨论主题 - 两行 */}
          <p className="text-sm font-bold leading-snug line-clamp-2 min-h-[2.5em]">{item.title || '圆桌派讨论'}</p>

          {/* 书名 - 仅展示，点击进入圆桌讨论页 */}
          <span className="mt-0.5 text-xs text-brand-600 truncate block">
            📖 {item.bookTitle}
          </span>

          {/* 参与角色（不含主持人） */}
          <div className="mt-1.5 flex items-center gap-1.5 flex-wrap">
            {displayRoles.slice(0, 4).map(key => (
              <span
                key={key}
                className="inline-flex items-center gap-0.5 rounded-md bg-brand-100 px-1.5 py-0.5 text-xs font-medium text-brand-500"
              >
                <span>{ROLE_ICONS[key] || '👤'}</span>
                {ROLE_NAMES[key] || key}
              </span>
            ))}
            {displayRoles.length > 4 && (
              <span className="text-xs text-muted-foreground">+{displayRoles.length - 4}</span>
            )}
          </div>

          {/* 维度数据行 */}
          <div className="mt-2 flex items-center gap-2.5 text-xs text-muted-foreground">
            {/* 覆盖度 - 根据数值变色 */}
            {item.coverageScore != null && item.coverageScore > 0 && (
              (() => {
                const pct = Math.round(item.coverageScore)
                const colorCls = pct >= 80 ? 'text-danger' : pct >= 60 ? 'text-warning' : pct >= 40 ? 'text-success' : 'text-muted-foreground'
                const bgCls = pct >= 80 ? 'bg-danger/10' : pct >= 60 ? 'bg-warning/10' : pct >= 40 ? 'bg-success/10' : 'bg-muted'
                return (
                  <span className={`inline-flex items-center gap-0.5 rounded-md ${bgCls} px-1.5 py-0.5 font-semibold ${colorCls}`}>
                    <Eye className="h-3 w-3" />
                    覆盖度{pct}%
                  </span>
                )
              })()
            )}
            {/* 角色数 */}
            <span className="inline-flex items-center gap-0.5">
              <Users className="h-3 w-3" />
              {roles.length}人
            </span>
            {/* 状态 */}
            <span>{item.status === 'COMPLETED' ? '已完结' : '讨论中'}</span>
            {/* 时间 */}
            <span className="inline-flex items-center gap-0.5 ml-auto">
              <Clock className="h-3 w-3" />
              {formatTimeAgo(item.createdAt)}
            </span>
          </div>
        </div>
      </div>

      {/* 自己的标识 */}
      {item.isOwner && (
        <div className="border-t border-border/30 px-3 py-1 flex items-center gap-1">
          <User className="h-3 w-3 text-primary" />
          <span className="text-xs font-medium text-primary">我的圆桌</span>
          {item.status !== 'COMPLETED' && (
            <span className="ml-auto text-xs font-medium text-primary bg-primary/10 rounded px-1.5 py-0.5">继续</span>
          )}
          {item.status === 'COMPLETED' && (
            <span className="ml-auto text-xs font-medium text-success bg-success/10 rounded px-1.5 py-0.5">查看报告</span>
          )}
        </div>
      )}
    </div>
  )
}

// ==================== 书籍卡片 ====================

function BookListCard({ book, activeTag, matchScore }: { book: Book; activeTag?: string; matchScore?: number }) {
  const navigate = useNavigate()
  return (
    <BookCard
      book={{ ...book, matchScore }}
      onClick={() => navigate(`/book/${book.id}`)}
      activeTag={activeTag}
    />
  )
}

// ==================== 主页面 ====================

export default function DiscoverPage() {
  const [searchParams] = useSearchParams()
  const initialTab = (searchParams.get('tab') as TabKey) || 'books'
  const initialTag = searchParams.get('tag') || ''
  const [activeTab, setActiveTab] = useState<TabKey>(initialTab)
  const [debateSort, setDebateSort] = useState<SortKey>('mine')
  const debateSortRef = useRef<SortKey>('mine')
  const [rtSort, setRtSort] = useState<SortKey>('mine')
  const rtSortRef = useRef<SortKey>('mine')

  // 搜索
  const [searchQuery, setSearchQuery] = useState('')
  const [searchFocused, setSearchFocused] = useState(false)

  // 辩论数据
  const [debates, setDebates] = useState<DebateFeedItem[]>([])
  const [debateLoading, setDebateLoading] = useState(false)
  const [debatePage, setDebatePage] = useState(0)
  const [debateHasMore, setDebateHasMore] = useState(true)

  // 圆桌数据
  const [roundTables, setRoundTables] = useState<RoundTableFeedItem[]>([])
  const [rtLoading, setRtLoading] = useState(false)
  const [rtPage, setRtPage] = useState(0)
  const [rtHasMore, setRtHasMore] = useState(true)

  // 书籍数据
  const [books, setBooks] = useState<Book[]>([])
  const [bookLoading, setBookLoading] = useState(false)
  const [bookPage, setBookPage] = useState(1)
  const [bookHasMore, setBookHasMore] = useState(true)
  const [activeTag, setActiveTag] = useState(initialTag)

  const { data: categories = [] } = useQuery({
    queryKey: ['discover', 'tags'],
    queryFn: () => getHomeTags().then(res => res ?? []),
  })

  const { data: bookMatchScores = {} } = useQuery({
    queryKey: ['discover', 'matchScores', books.map(b => b.id).join(',')],
    queryFn: () => getMatchScores(books.map(b => b.id)).then(res => res ?? {}),
    enabled: books.length > 0,
  })

  // 同步 URL 参数到 state（keep-alive 场景下外部导航会改变 URL 参数）
  useEffect(() => {
    const tab = searchParams.get('tab')
    if (tab && ['debate', 'roundtable', 'books'].includes(tab)) {
      setActiveTab(tab as TabKey)
    }
  }, [searchParams])

  useEffect(() => {
    setActiveTag(searchParams.get('tag') || '')
  }, [searchParams])

  // 加载辩论
  const loadDebates = useCallback(async (pageNum: number, sort?: SortKey) => {
    const currentSort = sort ?? debateSortRef.current
    setDebateLoading(true)
    try {
      const res = await getGlobalDebateSessions(pageNum, 18, currentSort === 'mine' ? 'recent' : currentSort, currentSort === 'mine')
      const data = res
      const content: DebateFeedItem[] = data?.content || []
      // 最热/最新不展示未结束的辩论（ACTIVE）
      const filtered = currentSort !== 'mine' ? content.filter(i => i.status === 'COMPLETED' || i.status === 'ABANDONED') : content
      setDebates(prev => pageNum === 0 ? filtered : [...prev, ...filtered])
      setDebateHasMore(!(data?.last ?? true))
      setDebatePage(pageNum)
    } catch {
      if (pageNum === 0) setDebates([])
    } finally {
      setDebateLoading(false)
    }
  }, [])

  // 加载圆桌
  const loadRoundTables = useCallback(async (pageNum: number, sort?: SortKey) => {
    const currentSort = sort ?? rtSortRef.current
    setRtLoading(true)
    try {
      const res = await getGlobalRoundTableSessions(pageNum, 18, currentSort === 'mine' ? 'recent' : currentSort, currentSort === 'mine')
      const data = res
      const content: RoundTableFeedItem[] = data?.content || []
      setRoundTables(prev => pageNum === 0 ? content : [...prev, ...content])
      setRtHasMore(!(data?.last ?? true))
      setRtPage(pageNum)
    } catch {
      if (pageNum === 0) setRoundTables([])
    } finally {
      setRtLoading(false)
    }
  }, [])

  // 加载书籍
  const loadBooks = useCallback(async (tag: string, pageNum: number, query?: string) => {
    setBookLoading(true)
    try {
      // 无筛选条件时用综合热度接口，保证"全部"标签下有数据
      if (!tag && !query) {
        const res = await getHotRank(pageNum, 18)
        const result = res
        const list: Book[] = result?.list || []
        setBooks(prev => pageNum === 1 ? list : [...prev, ...list])
        setBookHasMore(list.length >= 18)
        setBookPage(pageNum)
      } else {
        const res = await searchBooks({ tag: tag || undefined, keyword: query || undefined, page: pageNum, size: 18 })
        const result = res
        const list: Book[] = result?.list || []
        setBooks(prev => pageNum === 1 ? list : [...prev, ...list])
        setBookHasMore(list.length >= 18)
        setBookPage(pageNum)
      }
    } catch {
      if (pageNum === 1) setBooks([])
    } finally {
      setBookLoading(false)
    }
  }, [])

  // 初始加载 — stable callbacks (empty deps) ensure this runs exactly once
  useEffect(() => {
    loadDebates(0)
    loadRoundTables(0)
    loadBooks(initialTag, 1)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // URL tag 参数变化时重新加载书籍
  useEffect(() => {
    if (initialTag !== undefined) {
      loadBooks(initialTag, 1)
    }
  }, [initialTag, loadBooks])

  // ====== 内部滚动位置保存/恢复 ======
  const scrollRef = useRef<HTMLDivElement>(null)
  const hasRestoredRef = useRef(false)
  const saveScroll = useKeepAliveStore((s) => s.saveScroll)
  const getScroll = useKeepAliveStore((s) => s.getScroll)

  const handleScroll = useCallback(() => {
    const el = scrollRef.current
    if (el) {
      saveScroll('/discover', el.scrollTop)
    }
  }, [saveScroll])

  useEffect(() => {
    if (hasRestoredRef.current) return
    const saved = getScroll('/discover')
    if (saved !== undefined && saved > 0 && scrollRef.current) {
      requestAnimationFrame(() => {
        if (scrollRef.current) {
          scrollRef.current.scrollTop = saved
        }
      })
    }
    hasRestoredRef.current = true
  }, [getScroll])

  const handleDebateSortChange = (sort: SortKey) => {
    setDebateSort(sort)
    debateSortRef.current = sort
    setDebatePage(0)
    loadDebates(0, sort)
  }

  const handleRtSortChange = (sort: SortKey) => {
    setRtSort(sort)
    rtSortRef.current = sort
    setRtPage(0)
    loadRoundTables(0, sort)
  }

  const handleTagChange = (tag: string) => {
    const newTag = tag === '全部' ? '' : tag
    setActiveTag(newTag)
    setBooks([])
    loadBooks(newTag, 1, searchQuery || undefined)
  }

  const handleSearch = () => {
    setActiveTag('')
    setBooks([])
    loadBooks('', 1, searchQuery || undefined)
  }

  const TABS: { key: TabKey; label: string; icon: typeof Swords }[] = [
    { key: 'debate', label: '奇葩说', icon: Swords },
    { key: 'roundtable', label: '圆桌派', icon: CircleDot },
    { key: 'books', label: '书籍', icon: BookOpen },
  ]

  const SORT_OPTS: { key: SortKey; label: string }[] = [
    { key: 'mine', label: '我的' },
    { key: 'hot', label: '最热' },
    { key: 'recent', label: '最新' },
  ]

  const renderTabBar = () => (
    <div className="px-4 md:px-6 lg:px-8">
      <div className="flex h-14 items-end gap-1 overflow-x-auto scrollbar-hide">
        {TABS.map(tab => {
          const Icon = tab.icon
          const isActive = activeTab === tab.key
          return (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className={`relative flex h-12 min-w-[72px] items-center justify-center gap-1.5 px-3 first:pl-0 first:pr-3 text-sm font-medium transition-colors duration-200 md:min-w-0 md:px-4 md:first:pl-0 md:first:pr-4 ${
                isActive
                  ? 'text-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              }`}
            >
              <Icon className={`h-4 w-4 transition-colors ${isActive ? 'text-primary' : 'text-muted-foreground/80'}`} />
              <span className="whitespace-nowrap">{tab.label}</span>
              <span
                className={`absolute inset-x-3 bottom-0 h-0.5 rounded-full transition-all duration-200 ${
                  isActive ? 'bg-primary opacity-100' : 'bg-transparent opacity-0'
                }`}
              />
            </button>
          )
        })}
      </div>
    </div>
  )

  return (
    <div className="flex flex-col h-full page-enter">
      {/* 固定头部 — 不滚动 */}
      <div className="shrink-0 bg-navbar border-b border-border/30 z-50">
        {renderTabBar()}

        {/* 奇葩说子栏 */}
        {activeTab === 'debate' && (
          <div className="flex items-center justify-between px-4 md:px-6 lg:px-8 py-2">
            <span className="text-xs text-muted-foreground">共 {debates.length} 场辩论</span>
            <div className="flex gap-1 bg-background rounded-lg p-0.5">
              {SORT_OPTS.map(opt => (
                <button key={opt.key} onClick={() => handleDebateSortChange(opt.key)} className={`px-2.5 py-1 text-xs font-medium rounded-md transition-colors ${debateSort === opt.key ? 'bg-navbar text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
                  {opt.label}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* 圆桌派子栏 */}
        {activeTab === 'roundtable' && (
          <div className="flex items-center justify-between px-4 md:px-6 lg:px-8 py-2">
            <span className="text-xs text-muted-foreground">共 {roundTables.length} 场圆桌讨论</span>
            <div className="flex gap-1 bg-background rounded-lg p-0.5">
              {SORT_OPTS.map(opt => (
                <button key={opt.key} onClick={() => handleRtSortChange(opt.key)} className={`px-2.5 py-1 text-xs font-medium rounded-md transition-colors ${rtSort === opt.key ? 'bg-navbar text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
                  {opt.label}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* 书籍子栏 */}
        {activeTab === 'books' && (
          <>
            <div className="flex items-center gap-2 px-4 md:px-6 lg:px-8 py-2">
              <div className={`flex-1 flex items-center gap-2 rounded-lg border bg-background px-3 py-2 transition-colors ${searchFocused ? 'border-primary/50 ring-1 ring-primary/20' : 'border-border'}`}>
                <Search className="h-4 w-4 text-muted-foreground shrink-0" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  onFocus={() => setSearchFocused(true)}
                  onBlur={() => setSearchFocused(false)}
                  onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
                  placeholder="搜索书籍、作者..."
                  className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
                />
                {searchQuery && (
                  <button onClick={() => { setSearchQuery(''); loadBooks(activeTag, 1) }} className="text-xs text-muted-foreground hover:text-foreground">清除</button>
                )}
              </div>
              {searchQuery && (
                <button onClick={handleSearch} className="shrink-0 rounded-lg bg-primary px-3 py-2 text-xs font-medium text-primary-foreground">搜索</button>
              )}
            </div>
            {categories.length > 0 && (
              <div className="pb-2">
                <TagFilterBar
                  tags={categories.map(c => c.name)}
                  activeTag={activeTag}
                  onTagChange={handleTagChange}
                />
              </div>
            )}
          </>
        )}
      </div>

      {/* ====== 可滚动内容区（pt-3 留出与头部的间距）====== */}
      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 md:px-6 lg:px-8 pt-3">
        {/* 奇葩说 内容 */}
        {activeTab === 'debate' && (
          <div>
            {debateLoading && debates.length === 0 ? (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                {Array.from({ length: 18 }, (_, i) => <DebateSkeleton key={i} />)}
              </div>
            ) : debates.length > 0 ? (
              <>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                  {debates.map(item => <DebateCard key={item.id} item={item} />)}
                  {debateLoading && Array.from({ length: 6 }, (_, i) => <DebateSkeleton key={`more-${i}`} />)}
                </div>
                {debateHasMore && !debateLoading && (
                  <button onClick={() => loadDebates(debatePage + 1)} className="mt-4 mb-20 flex w-full items-center justify-center gap-1 rounded-lg bg-card border border-border/50 py-2.5 text-xs font-medium text-muted-foreground hover:bg-muted transition-colors btn-press">
                    查看更多 <ChevronRight className="h-3.5 w-3.5" />
                  </button>
                )}
                {!debateHasMore && debates.length > 0 && (
                  <p className="mt-4 mb-20 text-center text-xs text-muted-foreground">没有更多了</p>
                )}
              </>
            ) : (
              <div className="flex flex-col items-center justify-center py-16">
                <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-brand-100 mb-4"><Swords className="h-7 w-7 text-brand-500" /></div>
                <p className="text-sm font-medium">还没有辩论</p>
                <p className="mt-1 text-xs text-muted-foreground">去书籍详情页开启一场精彩辩论吧</p>
              </div>
            )}
          </div>
        )}

        {/* 圆桌派 内容 */}
        {activeTab === 'roundtable' && (
          <div>
            {rtLoading && roundTables.length === 0 ? (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                {Array.from({ length: 18 }, (_, i) => <RoundTableSkeleton key={i} />)}
              </div>
            ) : roundTables.length > 0 ? (
              <>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                  {roundTables.map(item => <RoundTableCard key={item.id} item={item} />)}
                  {rtLoading && Array.from({ length: 6 }, (_, i) => <RoundTableSkeleton key={`more-${i}`} />)}
                </div>
                {rtHasMore && !rtLoading && (
                  <button onClick={() => loadRoundTables(rtPage + 1)} className="mt-4 mb-20 flex w-full items-center justify-center gap-1 rounded-lg bg-card border border-border/50 py-2.5 text-xs font-medium text-muted-foreground hover:bg-muted transition-colors btn-press">
                    查看更多 <ChevronRight className="h-3.5 w-3.5" />
                  </button>
                )}
                {!rtHasMore && roundTables.length > 0 && (
                  <p className="mt-4 mb-20 text-center text-xs text-muted-foreground">没有更多了</p>
                )}
              </>
            ) : (
              <div className="flex flex-col items-center justify-center py-16">
                <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-violet-500/10 mb-4"><CircleDot className="h-7 w-7 text-violet-500" /></div>
                <p className="text-sm font-medium">还没有圆桌讨论</p>
                <p className="mt-1 text-xs text-muted-foreground">去书籍详情页开启一场圆桌讨论吧</p>
              </div>
            )}
          </div>
        )}

        {/* 书籍 内容 */}
        {activeTab === 'books' && (
          <div>
            {bookLoading && books.length === 0 ? (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                {Array.from({ length: 18 }, (_, i) => <BookSkeleton key={i} />)}
              </div>
            ) : books.length > 0 ? (
              <>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                  {books.map(book => <BookListCard key={book.id} book={book} activeTag={activeTag || undefined} matchScore={bookMatchScores[book.id]} />)}
                  {bookLoading && Array.from({ length: 6 }, (_, i) => <BookSkeleton key={`more-${i}`} />)}
                </div>
                {bookHasMore && !bookLoading && (
                  <button onClick={() => loadBooks(activeTag, bookPage + 1, searchQuery || undefined)} className="mt-4 mb-20 flex w-full items-center justify-center gap-1 rounded-lg bg-card border border-border/50 py-2.5 text-xs font-medium text-muted-foreground hover:bg-muted transition-colors btn-press">
                    查看更多 <ChevronRight className="h-3.5 w-3.5" />
                  </button>
                )}
                {!bookHasMore && books.length > 0 && (
                  <p className="mt-4 mb-20 text-center text-xs text-muted-foreground">没有更多了</p>
                )}
              </>
            ) : (
              <div className="flex flex-col items-center justify-center py-16">
                <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted mb-4"><BookOpen className="h-7 w-7 text-muted-foreground/50" /></div>
                <p className="text-sm text-muted-foreground">暂无书籍</p>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
