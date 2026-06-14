import { useEffect, useState, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import {
  ArrowLeft, Bookmark, BookmarkCheck, BookOpen, Star, Eye, MessageSquare,
  Pencil, Trash2, Users,
} from 'lucide-react'
import {
  getBook, rateBook, updateBookCover, updateBookTitle, updateBookAuthor,
  updateBookDescription, updateFormatTags,
} from '@/api/book'
import type { BookSpeedRead } from '@/api/book'
import { checkInBookshelf, addToBookshelf, removeFromBookshelf } from '@/api/bookshelf'
import { moveToTrash, checkInTrash } from '@/api/bookTrash'
import { getProgress } from '@/api/progress'
import { getBookComments, countBookComments } from '@/api/comment'
import type { Book } from '@/types/book'
import type { CommentVO } from '@/api/comment'
import { formatProgress, formatFileSize, parseFormatTags } from '@/types/book'
import CommentList from '@/components/comment/CommentList'
import BookChatSheet from '@/components/book/BookChatSheet'
import { BlinkingBot } from '@/components/layout/TabBar'
import BookCover from '@/components/book/BookCover'
import ImageViewer from '@/components/common/ImageViewer'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import {
  RatingBadgeCN,
  MatchBadgeCN,
  MatchScoreCard,
  SpeedReadCard,
  AiQaEntry,
  EditFieldDialog,
  EditTagsDialog,
} from '@/components/book/detail'
import { useMatchScores } from '@/hooks/useMatchScores'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { useKeepAliveStore } from '@/store/keepAlive'
import { useAuthStore } from '@/store/auth'
import { useIsMobile } from '@/hooks/use-mobile'
import { toast } from 'sonner'
import { createSsePostConnection } from '@/utils/sse-request'

const DETAIL_CACHE_PREFIX = '/book/'
const DETAIL_CACHE_TTL = 10 * 60 * 1000

interface DetailCache {
  book: Book
  inShelf: boolean
  inTrash: boolean
  progress: number
  userRating: number
  comments: CommentVO[]
  commentCount: number
  commentPage: number
  hasMoreComments: boolean
  speedReadData: BookSpeedRead | null
  timestamp: number
}

export default function BookDetailPage() {
  const { bookId } = useParams<{ bookId: string }>()
  const navigate = useNavigate()
  const goBack = useGoBack()
  const savePageData = useKeepAliveStore((s) => s.savePageData)
  const getPageData = useKeepAliveStore((s) => s.getPageData)
  const clearPageData = useKeepAliveStore((s) => s.clearPageData)

  const cacheKey = `${DETAIL_CACHE_PREFIX}${bookId}`
  const cached = getPageData<DetailCache>(cacheKey)
  const isCacheValid = cached && Date.now() - cached.timestamp < DETAIL_CACHE_TTL
  const hasSpeedReadCache = isCacheValid && cached.speedReadData != null && cached.speedReadData.corePoints?.length > 0

  const [book, setBook] = useState<Book | null>(() => isCacheValid ? cached.book : null)
  const [inShelf, setInShelf] = useState(() => isCacheValid ? cached.inShelf : false)
  const [progress, setProgress] = useState<number>(() => isCacheValid ? cached.progress : 0)
  const [loading, setLoading] = useState(() => !isCacheValid)
  const [showRating, setShowRating] = useState(false)
  const [userRating, setUserRating] = useState(() => isCacheValid ? cached.userRating : 0)
  const [hoverStar, setHoverStar] = useState(0)
  const [comments, setComments] = useState<CommentVO[]>(() => isCacheValid ? cached.comments : [])
  const [commentCount, setCommentCount] = useState(() => isCacheValid ? cached.commentCount : 0)
  const [commentPage, setCommentPage] = useState(() => isCacheValid ? cached.commentPage : 1)
  const [hasMoreComments, setHasMoreComments] = useState(() => isCacheValid ? cached.hasMoreComments : true)
  const [showBookChat, setShowBookChat] = useState(false)
  const [chatInitialQuestion, setChatInitialQuestion] = useState<string | undefined>(undefined)
  const [descExpanded, setDescExpanded] = useState(false)
  const [showImageViewer, setShowImageViewer] = useState(false)
  const coverInputRef = useRef<HTMLInputElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  const [editTitleOpen, setEditTitleOpen] = useState(false)
  const [editAuthorOpen, setEditAuthorOpen] = useState(false)
  const [editTagsOpen, setEditTagsOpen] = useState(false)
  const [editDescOpen, setEditDescOpen] = useState(false)
  const [inTrash, setInTrash] = useState(() => isCacheValid ? cached.inTrash : false)
  const [trashDialogOpen, setTrashDialogOpen] = useState(false)
  const [trashing, setTrashing] = useState(false)

  const { userInfo } = useAuthStore()
  const isAdmin = userInfo?.role === 'ADMIN'
  const isMobile = useIsMobile()

  const id = Number(bookId)
  const matchScores = useMatchScores(book ? [book.id] : [])
  const ms = book ? matchScores?.[String(book.id)] : null
  const [speedReadData, setSpeedReadData] = useState<BookSpeedRead | null>(() => hasSpeedReadCache ? cached.speedReadData : null)
  const [speedReadLoading, setSpeedReadLoading] = useState(() => !hasSpeedReadCache)
  const speedReadDataRef = useRef(speedReadData)

  // sync ref for updateCache
  useEffect(() => { speedReadDataRef.current = speedReadData }, [speedReadData])

  // Refs for SSE onDone callback to access latest state (avoids stale closure)
  const bookRef = useRef(book)
  const inShelfRef = useRef(inShelf)
  const inTrashRef = useRef(inTrash)
  const progressRef = useRef(progress)
  const userRatingRef = useRef(userRating)
  const commentsRef = useRef(comments)
  const commentCountRef = useRef(commentCount)
  const commentPageRef = useRef(commentPage)
  const hasMoreCommentsRef = useRef(hasMoreComments)
  const hasSpeedReadCacheRef = useRef(hasSpeedReadCache)
  bookRef.current = book
  inShelfRef.current = inShelf
  inTrashRef.current = inTrash
  progressRef.current = progress
  userRatingRef.current = userRating
  commentsRef.current = comments
  commentCountRef.current = commentCount
  commentPageRef.current = commentPage
  hasMoreCommentsRef.current = hasMoreComments
  hasSpeedReadCacheRef.current = hasSpeedReadCache

  const updateCache = useCallback((
    b: Book | null,
    shelf: boolean,
    trash: boolean,
    prog: number,
    rating: number,
    cmts: CommentVO[],
    cmtCount: number,
    cmtPage: number,
    hasMore: boolean,
  ) => {
    if (!b) return
    savePageData(cacheKey, {
      book: b,
      inShelf: shelf,
      inTrash: trash,
      progress: prog,
      userRating: rating,
      comments: cmts,
      commentCount: cmtCount,
      commentPage: cmtPage,
      hasMoreComments: hasMore,
      speedReadData: speedReadDataRef.current,
      timestamp: Date.now(),
    })
  }, [savePageData, cacheKey])

  const loadComments = useCallback(async (page: number = 1) => {
    try {
      const res = await getBookComments(id, page, 20)
      const data = (res as any)?.data || (res as any)
      if (data?.list) {
        setComments(prev => page === 1 ? data.list : [...prev, ...data.list])
        setHasMoreComments(data.list.length >= 20)
      }
    } catch { /* ignore */ }
  }, [id])

  useEffect(() => {
    if (!bookId) return
    if (isCacheValid) return
    Promise.all([
      getBook(id),
      checkInBookshelf(id).catch(() => ({ data: false })),
      getProgress(id).catch(() => ({ data: null })),
      checkInTrash(id).catch(() => ({ data: false })),
    ]).then(([bookRes, shelfRes, progressRes, trashRes]) => {
      const b = bookRes as unknown as Book
      const shelf = (shelfRes as any) || false
      const trash = (trashRes as any) || false
      const progressData = (progressRes as any)
      const prog = progressData?.progress || 0
      const rating = progressData?.userRating || 0
      setBook(b)
      setInShelf(shelf)
      setInTrash(trash)
      setProgress(prog)
      if (rating) setUserRating(rating)
      setLoading(false)
      updateCache(b, shelf, trash, prog, rating, [], 0, 1, true)
    })
    loadComments(1)
    countBookComments(id).then(res => setCommentCount((res as any)?.data || (res as any) || 0)).catch(() => {})
  }, [bookId, id, loadComments, isCacheValid, updateCache])

  // Speed read SSE — only when no speed read cache
  useEffect(() => {
    if (!bookId) return
    if (hasSpeedReadCacheRef.current) return
    setSpeedReadLoading(true)
    setSpeedReadData({ bookId: id, corePoints: [], suitableFor: [], notSuitableFor: [], takeaways: [], difficulty: '' })
    const bufferRef = { current: '' }
    const currentSectionRef = { current: '' }
    const currentItemRef = { current: '' }

    const flushCurrentItem = () => {
      const item = currentItemRef.current.trim()
      const section = currentSectionRef.current
      currentItemRef.current = ''
      if (!item || !section) return

      setSpeedReadData(prev => {
        if (!prev) return prev
        const next = { ...prev, currentItem: undefined, currentSection: undefined }
        if (section === '核心观点') next.corePoints = [...(next.corePoints || []), item]
        else if (section === '适合谁读') next.suitableFor = [...(next.suitableFor || []), item]
        else if (section === '不适合谁读') next.notSuitableFor = [...(next.notSuitableFor || []), item]
        else if (section === '读完能收获什么') next.takeaways = [...(next.takeaways || []), item]
        else if (section === '难度') next.difficulty = item
        return next
      })
    }

    const controller = createSsePostConnection(
      `/books/${id}/speed-read/stream`,
      {},
      {
        onChunk: (text: string) => {
          if (!text) return
          bufferRef.current += text
          const lines = bufferRef.current.split('\n')
          bufferRef.current = lines.pop() || ''
          for (const line of lines) {
            const trimmed = line.trim()
            if (trimmed.startsWith('### ')) {
              flushCurrentItem()
              currentSectionRef.current = trimmed.slice(4).trim()
            } else if (trimmed) {
              currentItemRef.current = currentItemRef.current ? currentItemRef.current + trimmed : trimmed
              flushCurrentItem()
            }
          }
          // 实时展示 buffer 中正在接收的行（打字机效果）
          const section = currentSectionRef.current
          const buffered = bufferRef.current.trim()
          if (section && buffered && !buffered.startsWith('### ')) {
            const displayItem = currentItemRef.current ? currentItemRef.current + buffered : buffered
            setSpeedReadData(prev => {
              if (!prev) return prev
              return { ...prev, currentItem: displayItem, currentSection: section }
            })
          }
        },
        onDone: () => {
          if (bufferRef.current.trim()) {
            const trimmed = bufferRef.current.trim()
            if (trimmed.startsWith('### ')) {
              flushCurrentItem()
              currentSectionRef.current = trimmed.slice(4).trim()
            } else if (trimmed) {
              currentItemRef.current = currentItemRef.current ? currentItemRef.current + trimmed : trimmed
              flushCurrentItem()
            }
          } else {
            flushCurrentItem()
          }
          setSpeedReadLoading(false)
          setSpeedReadData(prev => {
            if (prev) updateCache(bookRef.current, inShelfRef.current, inTrashRef.current, progressRef.current, userRatingRef.current, commentsRef.current, commentCountRef.current, commentPageRef.current, hasMoreCommentsRef.current)
            return prev
          })
        },
        onError: () => {
          flushCurrentItem()
          setSpeedReadLoading(false)
          setSpeedReadData(prev => {
            if (prev && prev.corePoints?.length > 0) updateCache(bookRef.current, inShelfRef.current, inTrashRef.current, progressRef.current, userRatingRef.current, commentsRef.current, commentCountRef.current, commentPageRef.current, hasMoreCommentsRef.current)
            return prev
          })
        },
      },
    )
    return () => controller.abort()
  }, [bookId, id, updateCache])

  const toggleShelf = async () => {
    if (!book) return
    try {
      if (inShelf) {
        await removeFromBookshelf(book.id)
        setInShelf(false)
        toast.success('已从书架移除')
      } else {
        await addToBookshelf(book.id)
        setInShelf(true)
        toast.success('已加入书架')
      }
    } catch {
      toast.error('操作未完成')
    }
  }

  const handleTrash = () => {
    setTrashDialogOpen(true)
  }

  const handleConfirmTrash = async () => {
    if (!book) return
    setTrashing(true)
    try {
      await moveToTrash(book.id)
      setInTrash(true)
      toast.success('已丢入垃圾桶')
    } catch (err: any) {
      toast.error(err?.message || '操作失败')
    } finally {
      setTrashing(false)
      setTrashDialogOpen(false)
    }
  }

  const handleRate = async (rating: number) => {
    if (!book || userRating > 0) return
    try {
      const updatedBook = await rateBook(book.id, rating) as unknown as Book
      setBook(updatedBook)
      setUserRating(rating)
      setShowRating(false)
      toast.success(`评分 ${rating} 星已保存`)
    } catch (err: any) {
      toast.error(err?.message || '评分暂时无法提交')
    }
  }

  const refreshComments = () => {
    setCommentPage(1)
    loadComments(1)
    countBookComments(id).then(res => setCommentCount((res as any)?.data || (res as any) || 0)).catch(() => {})
  }

  const handleCoverClick = () => {
    if (book?.coverUrl || isAdmin) {
      setShowImageViewer(true)
    }
  }

  const handleChangeCover = () => {
    coverInputRef.current?.click()
  }

  const handleCoverFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !book) return

    try {
      const updated = await updateBookCover(book.id, file)
      setBook(updated as unknown as Book)
      // 清除缓存以确保封面刷新
      clearPageData(cacheKey)
      toast.success('封面已更新')
    } catch (err: any) {
      toast.error(err?.message || '封面更新失败')
    } finally {
      e.target.value = ''
    }
  }

  const handleUpdateTitle = async (title: string) => {
    if (!book) return
    const updated = await updateBookTitle(book.id, title) as unknown as Book
    setBook(updated)
    toast.success('书名已更新')
  }

  const handleUpdateAuthor = async (author: string) => {
    if (!book) return
    const updated = await updateBookAuthor(book.id, author || null) as unknown as Book
    setBook(updated)
    toast.success('作者已更新')
  }

  const handleUpdateTags = async (tags: string[]) => {
    if (!book) return
    const updated = await updateFormatTags(book.id, tags) as unknown as Book
    setBook(updated)
    toast.success('标签已更新')
  }

  const handleUpdateDescription = async (description: string) => {
    if (!book) return
    const updated = await updateBookDescription(book.id, description || null) as unknown as Book
    setBook(updated)
    toast.success('简介已更新')
  }

  const handleOpenChat = useCallback((initialQuestion?: string) => {
    setChatInitialQuestion(initialQuestion)
    setShowBookChat(true)
  }, [])

  if (loading || !book) {
    return (
      <div className="absolute inset-0 flex flex-col overflow-hidden bg-background page-enter overscroll-contain">
        <header className="shrink-0 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl z-20">
          <div className="h-9 w-9 shrink-0 animate-pulse rounded-xl bg-muted" />
          <div className="h-5 flex-1 animate-pulse rounded bg-muted" />
          <div className="h-9 w-9 shrink-0 animate-pulse rounded-xl bg-muted" />
        </header>
        <div className="flex-1 overflow-y-auto overscroll-contain">
          <div className="bg-gradient-to-b from-primary/5 to-transparent px-4 py-5">
            <div className="flex gap-4">
              <div className="h-36 w-24 flex-shrink-0 animate-pulse rounded-xl bg-muted shadow-lg" />
              <div className="flex flex-1 flex-col justify-between py-1">
                <div className="space-y-2">
                  <div className="h-5 w-4/5 animate-pulse rounded bg-muted" />
                  <div className="h-3.5 w-1/2 animate-pulse rounded bg-muted" />
                </div>
                <div className="flex flex-wrap items-center gap-3">
                  <div className="h-4 w-12 animate-pulse rounded bg-muted" />
                  <div className="h-4 w-16 animate-pulse rounded bg-muted" />
                  <div className="h-5 w-10 animate-pulse rounded-md bg-muted" />
                  <div className="h-4 w-14 animate-pulse rounded bg-muted" />
                </div>
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <div className="h-3 w-12 animate-pulse rounded bg-muted" />
                    <div className="h-3 w-8 animate-pulse rounded bg-muted" />
                  </div>
                  <div className="h-2 w-full animate-pulse rounded-full bg-muted" />
                </div>
              </div>
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-7 animate-pulse rounded-full bg-muted" style={{ width: `${50 + i * 15}px` }} />
              ))}
            </div>
          </div>
          <div className="mx-4 mb-4 rounded-2xl border border-border/50 bg-card p-4">
            <div className="flex items-center gap-3">
              <div className="h-14 w-14 animate-pulse rounded-full bg-muted" />
              <div className="flex-1 space-y-2">
                <div className="h-4 w-24 animate-pulse rounded bg-muted" />
                <div className="h-3 w-16 animate-pulse rounded bg-muted" />
              </div>
            </div>
          </div>
          <div className="mx-4 mb-4 rounded-2xl border border-border/50 bg-card p-4">
            <div className="flex items-center gap-2 mb-3">
              <div className="h-4 w-4 animate-pulse rounded bg-muted" />
              <div className="h-4 w-24 animate-pulse rounded bg-muted" />
            </div>
            <div className="space-y-2">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-3 w-full animate-pulse rounded bg-muted" />
              ))}
            </div>
          </div>
          <div className="px-4 pb-4 space-y-2">
            <div className="h-4 w-10 animate-pulse rounded bg-muted" />
            <div className="space-y-1.5">
              <div className="h-3.5 w-full animate-pulse rounded bg-muted" />
              <div className="h-3.5 w-full animate-pulse rounded bg-muted" />
              <div className="h-3.5 w-4/5 animate-pulse rounded bg-muted" />
            </div>
            <div className="h-3 w-8 animate-pulse rounded bg-muted" />
          </div>
          <div className="mx-4 mb-4 rounded-2xl border border-border/50 bg-card p-4">
            <div className="flex items-center gap-2 mb-3">
              <div className="h-4 w-4 animate-pulse rounded bg-muted" />
              <div className="h-4 w-24 animate-pulse rounded bg-muted" />
            </div>
            <div className="flex flex-wrap gap-2">
              {[0, 1, 2].map((i) => (
                <div key={i} className="h-8 w-28 animate-pulse rounded-full bg-muted" />
              ))}
            </div>
          </div>
          <div className="px-4 border-t border-border/50 pt-4">
            <div className="flex items-center gap-2 mb-3">
              <div className="h-4 w-4 animate-pulse rounded bg-muted" />
              <div className="h-4 w-10 animate-pulse rounded bg-muted" />
              <div className="h-3 w-8 animate-pulse rounded bg-muted" />
            </div>
            <div className="space-y-3">
              {[0, 1, 2].map((i) => (
                <div key={i} className="flex gap-3">
                  <div className="h-8 w-8 flex-shrink-0 animate-pulse rounded-full bg-muted" />
                  <div className="flex-1 space-y-2">
                    <div className="flex items-center gap-2">
                      <div className="h-3 w-16 animate-pulse rounded bg-muted" />
                      <div className="h-3 w-10 animate-pulse rounded bg-muted" />
                    </div>
                    <div className="h-3 w-full animate-pulse rounded bg-muted" />
                    <div className="h-3 w-3/4 animate-pulse rounded bg-muted" />
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
        <div className="shrink-0 border-t border-border/50 bg-background/80 backdrop-blur-xl px-3 pt-3" style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 0.75rem)' }}>
          <div className="flex gap-2">
            <div className="h-11 w-14 animate-pulse rounded-2xl bg-muted" />
            <div className="h-11 w-14 animate-pulse rounded-2xl bg-muted" />
            <div className="h-11 flex-1 animate-pulse rounded-2xl bg-muted" />
          </div>
        </div>
      </div>
    )
  }

  const tags = parseFormatTags(book.formatTags)

  return (
    <div className="absolute inset-0 md:relative md:inset-auto md:h-full flex flex-col overflow-hidden bg-background page-enter overscroll-contain">
      {/* 顶部导航 — 移动端 */}
      <header className="md:hidden shrink-0 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl z-20">
        <button onClick={() => goBack()} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 truncate text-base font-bold">{book.title}</h1>
        {!inTrash && (
          <button onClick={handleTrash} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors">
            <Trash2 className="h-5 w-5 text-muted-foreground" />
          </button>
        )}
        <button onClick={toggleShelf} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          {inShelf ? (
            <BookmarkCheck className="h-5 w-5 text-primary" />
          ) : (
            <Bookmark className="h-5 w-5" />
          )}
        </button>
        {isAdmin && (
          <button onClick={() => navigate(`/reader/${book.id}`)} className="flex items-center gap-1.5 rounded-xl bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground">
            <BookOpen className="h-4 w-4" />
            阅读
          </button>
        )}
      </header>

      {/* PC端顶部导航 */}
      <header className="hidden md:flex shrink-0 items-center gap-3 border-b border-border/50 bg-background/80 px-4 md:px-6 lg:px-8 py-3 backdrop-blur-xl z-20">
        <button onClick={() => goBack()} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 truncate text-base font-bold">{book.title}</h1>
        {!inTrash && (
          <button onClick={handleTrash} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors">
            <Trash2 className="h-5 w-5 text-muted-foreground" />
          </button>
        )}
        <button onClick={toggleShelf} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          {inShelf ? (
            <BookmarkCheck className="h-5 w-5 text-primary" />
          ) : (
            <Bookmark className="h-5 w-5" />
          )}
        </button>
        {isAdmin && (
          <button
            onClick={() => navigate(`/reader/${book.id}`)}
            className="flex items-center gap-2 rounded-xl bg-muted px-4 py-2 text-sm font-medium text-foreground hover:bg-muted/80 transition-colors"
          >
            <BookOpen className="h-4 w-4" />
            {progress > 0 ? '继续阅读' : '开始阅读'}
          </button>
        )}
        <button
          onClick={() => handleOpenChat()}
          className="flex items-center gap-2 rounded-xl bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground hover:bg-primary/90 transition-colors shadow-lg shadow-primary/25"
        >
          <BlinkingBot className="h-4 w-4" />
          AI 问答
        </button>
        <button
          onClick={() => navigate(`/book/${book.id}/round-table`)}
          className="flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-500 to-purple-600 px-4 py-2 text-sm font-semibold text-white hover:from-violet-600 hover:to-purple-700 transition-colors shadow-lg shadow-violet-500/25"
        >
          <Users className="h-4 w-4" />
          圆桌派
        </button>
        <button
          onClick={() => navigate(`/book/${book.id}/debate`)}
          className="flex items-center gap-2 rounded-xl bg-gradient-to-r from-brand-400 to-brand-500 px-4 py-2 text-sm font-semibold text-white hover:from-brand-500 hover:to-brand-600 transition-colors shadow-lg shadow-brand-500/25"
        >
          <MessageSquare className="h-4 w-4" />
          奇葩说
        </button>
      </header>

      {/* 内容区域 - 移动端单列 / PC端双栏 */}
      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain">

      {/* 移动端: 原始单列布局 */}
      <div className="md:hidden">

      {/* 图书信息 — 渐变背景 */}
      <div className="bg-gradient-to-b from-primary/5 to-transparent px-4 py-5">
        <div className="flex gap-4">
          <div onClick={handleCoverClick} className={book.coverUrl || isAdmin ? 'cursor-pointer' : ''}>
            <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} size="xl" className="flex-shrink-0 shadow-lg" />
          </div>
          <div className="flex flex-1 flex-col justify-between">
            <div>
              <div className="flex items-start gap-1.5">
                <h2 className="text-lg font-bold leading-tight">{book.title}</h2>
                {isAdmin && (
                  <button
                    onClick={() => setEditTitleOpen(true)}
                    className="mt-0.5 shrink-0 rounded-md p-0.5 text-muted-foreground/50 hover:text-primary hover:bg-primary/10 transition-colors"
                  >
                    <Pencil className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>
              <div className="flex items-center gap-1.5">
                {book.author && <p className="text-body text-muted-foreground">{book.author}</p>}
                {isAdmin && (
                  <button
                    onClick={() => setEditAuthorOpen(true)}
                    className="shrink-0 rounded-md p-0.5 text-muted-foreground/50 hover:text-primary hover:bg-primary/10 transition-colors"
                  >
                    <Pencil className="h-3 w-3" />
                  </button>
                )}
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
              <button
                onClick={() => userRating > 0 ? undefined : setShowRating(!showRating)}
                className={`flex items-center gap-1 transition-transform ${userRating > 0 ? 'cursor-default opacity-60' : 'active:scale-95'}`}
                disabled={userRating > 0}
              >
                <RatingBadgeCN rating={book.rating} />
                {book.rating <= 0 && <span className="text-xs text-muted-foreground">暂无评分</span>}
                <span className="text-xs text-primary ml-1">{userRating > 0 ? '已评' : '评'}</span>
              </button>
              <MatchBadgeCN score={ms} />
              <span className="flex items-center gap-1"><Eye className="h-3 w-3" />{book.readCount} 阅读</span>
              <span className="rounded-md bg-primary/8 px-1.5 py-0.5 font-medium text-primary">{book.format}</span>
              {book.fileSize && <span>{formatFileSize(book.fileSize)}</span>}
            </div>
            {progress > 0 && (
              <div className="mt-2">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-muted-foreground">阅读进度</span>
                  <span className="font-bold text-primary">{formatProgress(progress)}</span>
                </div>
                <div className="mt-1.5 h-2 rounded-full bg-primary/10">
                  <div className="h-full rounded-full bg-gradient-to-r from-primary to-primary/70 transition-all" style={{ width: `${Math.round(progress * 100)}%` }} />
                </div>
              </div>
            )}
          </div>
        </div>

        {(tags.length > 0 || isAdmin) && (
          <div className="mt-4 flex flex-wrap items-center gap-2">
            {tags.map((tag) => (
              <span key={tag} className="shrink-0 whitespace-nowrap rounded-full bg-primary/8 px-3 py-1 text-xs font-semibold text-primary border border-primary/10">{tag}</span>
            ))}
            {isAdmin && (
              <button
                onClick={() => setEditTagsOpen(true)}
                className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full border border-dashed border-primary/30 px-3 py-1 text-xs font-medium text-primary/60 hover:text-primary hover:border-primary/50 hover:bg-primary/5 transition-colors"
              >
                <Pencil className="h-3 w-3" />
                编辑标签
              </button>
            )}
          </div>
        )}

        {showRating && (
          <div className="mt-4 rounded-2xl bg-card p-4 border border-border/50">
            <p className="text-sm font-semibold mb-3">为这本书评分</p>
            <div className="flex items-center gap-1">
              {[1, 2, 3, 4, 5].map((star) => (
                <button
                  key={star}
                  onClick={() => handleRate(star)}
                  onMouseEnter={() => setHoverStar(star)}
                  onMouseLeave={() => setHoverStar(0)}
                  className="transition-transform active:scale-90"
                >
                  <Star
                    className={`h-9 w-9 transition-colors ${
                      (hoverStar || userRating) >= star
                        ? 'fill-warning text-warning'
                        : 'text-muted-foreground/30 hover:text-warning/50'
                    }`}
                  />
                </button>
              ))}
              <span className="ml-2 text-sm font-semibold text-foreground">
                {hoverStar || userRating || ''}{(hoverStar || userRating) ? ' 星' : ''}
              </span>
            </div>
            <p className="mt-2 text-xs text-muted-foreground">点击星星评分（1-5 星）</p>
          </div>
        )}
      </div>

      {/* 匹配度卡片 */}
      <div className="px-4">
        <MatchScoreCard bookId={id} ms={ms} />
        <SpeedReadCard data={speedReadData} loading={speedReadLoading} />

        {/* 简介 */}
        {(book.description || isAdmin) && (
          <div className="mb-4 rounded-2xl border border-border/50 bg-card p-4">
          <div className="mb-2 flex items-center gap-1.5">
            <h3 className="text-sm font-bold">简介</h3>
            {isAdmin && (
              <button
                onClick={() => setEditDescOpen(true)}
                className="rounded-md p-0.5 text-muted-foreground/50 hover:text-primary hover:bg-primary/10 transition-colors"
              >
                <Pencil className="h-3 w-3" />
              </button>
            )}
          </div>
          {book.description ? (
            <>
              <p
                className={`text-body leading-relaxed text-muted-foreground text-justify ${!descExpanded ? 'line-clamp-3' : ''}`}
              >
                {book.description}
              </p>
              {book.description.length > 80 && (
                <button
                  onClick={() => setDescExpanded(!descExpanded)}
                  className="mt-1 text-xs font-medium text-primary"
                >
                  {descExpanded ? '收起' : '展开'}
                </button>
              )}
            </>
          ) : (
            <p className="text-body text-muted-foreground/50 italic">暂无简介</p>
          )}
        </div>
      )}

        {/* AI 深度问答入口 */}
        <AiQaEntry bookId={id} onOpenChat={handleOpenChat} isMobile />
      </div>

      {/* 评论区 */}
      <div className="px-4 border-t border-border/50 pt-4">
        <div className="flex items-center gap-2 mb-3">
          <MessageSquare className="h-4 w-4 text-primary" />
          <h3 className="text-sm font-bold">评论</h3>
          <span className="text-xs text-muted-foreground">({commentCount})</span>
        </div>
        <CommentList comments={comments} bookId={id} onRefresh={refreshComments} />
        {hasMoreComments && comments.length > 0 && (
          <button onClick={() => { const next = commentPage + 1; setCommentPage(next); loadComments(next) }} className="mt-3 w-full rounded-xl bg-muted py-2 text-sm font-medium text-muted-foreground">
            加载更多评论
          </button>
        )}
      </div>
      </div>{/* end md:hidden */}

      {/* PC端：左右两栏布局 */}
      <div className="hidden md:block">
        <div className="px-4 lg:px-6 py-6">
          <div className="flex gap-6">
            {/* 左栏：图书信息 + 简介 + 评论 */}
            <div className="w-[380px] lg:w-[420px] shrink-0">
              <div>
                {/* 封面 */}
                <div onClick={handleCoverClick} className={`flex justify-center mb-6 ${book.coverUrl || isAdmin ? 'cursor-pointer' : ''}`}>
                  <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} size="lg" className="w-44 lg:w-52 shadow-xl rounded-xl" />
                </div>

                {/* 标题 + 作者 */}
                <div className="text-center mb-4">
                  <div className="flex items-start justify-center gap-2">
                    <h2 className="text-h2 font-bold leading-tight">{book.title}</h2>
                    {isAdmin && (
                      <button onClick={() => setEditTitleOpen(true)} className="mt-1 shrink-0 rounded-md p-1 text-muted-foreground/50 hover:text-primary hover:bg-primary/10 transition-colors">
                        <Pencil className="h-4 w-4" />
                      </button>
                    )}
                  </div>
                  <div className="flex items-center justify-center gap-1.5 mt-1">
                    {book.author && <p className="text-base text-muted-foreground">{book.author}</p>}
                    {isAdmin && (
                      <button onClick={() => setEditAuthorOpen(true)} className="shrink-0 rounded-md p-0.5 text-muted-foreground/50 hover:text-primary hover:bg-primary/10 transition-colors">
                        <Pencil className="h-3 w-3" />
                      </button>
                    )}
                  </div>
                </div>

                {/* 元信息 */}
                <div className="flex flex-wrap items-center justify-center gap-3 text-sm text-muted-foreground mb-4">
                  <button
                    onClick={() => userRating > 0 ? undefined : setShowRating(!showRating)}
                    className={`flex items-center gap-1 transition-transform ${userRating > 0 ? 'cursor-default opacity-60' : 'active:scale-95'}`}
                    disabled={userRating > 0}
                  >
                    <RatingBadgeCN rating={book.rating} />
                    {book.rating <= 0 && <span className="text-xs text-muted-foreground">暂无评分</span>}
                    <span className="text-xs text-primary ml-1">{userRating > 0 ? '已评' : '评'}</span>
                  </button>
                  <MatchBadgeCN score={ms} />
                  <span className="flex items-center gap-1"><Eye className="h-3.5 w-3.5" />{book.readCount} 阅读</span>
                  <span className="rounded-md bg-primary/8 px-2 py-0.5 text-xs font-medium text-primary">{book.format}</span>
                  {book.fileSize && <span>{formatFileSize(book.fileSize)}</span>}
                </div>

                {/* 标签 */}
                {(tags.length > 0 || isAdmin) && (
                  <div className="flex flex-wrap items-center justify-center gap-2 mb-4">
                    {tags.map((tag) => (
                      <span key={tag} className="shrink-0 whitespace-nowrap rounded-full bg-primary/8 px-3 py-1 text-xs font-semibold text-primary border border-primary/10">{tag}</span>
                    ))}
                    {isAdmin && (
                      <button onClick={() => setEditTagsOpen(true)} className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full border border-dashed border-primary/30 px-3 py-1 text-xs font-medium text-primary/60 hover:text-primary hover:border-primary/50 hover:bg-primary/5 transition-colors">
                        <Pencil className="h-3 w-3" />编辑标签
                      </button>
                    )}
                  </div>
                )}

                {/* 阅读进度 */}
                {progress > 0 && (
                  <div className="mb-4 max-w-sm mx-auto">
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-muted-foreground">阅读进度</span>
                      <span className="font-bold text-primary">{formatProgress(progress)}</span>
                    </div>
                    <div className="mt-1.5 h-2 rounded-full bg-primary/10">
                      <div className="h-full rounded-full bg-gradient-to-r from-primary to-primary/70 transition-all" style={{ width: `${Math.round(progress * 100)}%` }} />
                    </div>
                  </div>
                )}

                {/* 评分 */}
                {showRating && (
                  <div className="mb-4 rounded-2xl bg-card p-4 border border-border/50 inline-flex flex-col mx-auto">
                    <p className="text-sm font-semibold mb-3">为这本书评分</p>
                    <div className="flex items-center gap-1">
                      {[1, 2, 3, 4, 5].map((star) => (
                        <button key={star} onClick={() => handleRate(star)} onMouseEnter={() => setHoverStar(star)} onMouseLeave={() => setHoverStar(0)} className="transition-transform active:scale-90">
                          <Star className={`h-9 w-9 transition-colors ${(hoverStar || userRating) >= star ? 'fill-warning text-warning' : 'text-muted-foreground/30 hover:text-warning/50'}`} />
                        </button>
                      ))}
                      <span className="ml-2 text-sm font-semibold text-foreground">{hoverStar || userRating || ''}{(hoverStar || userRating) ? ' 星' : ''}</span>
                    </div>
                    <p className="mt-2 text-xs text-muted-foreground">点击星星评分（1-5 星）</p>
                  </div>
                )}

                {/* 简介 */}
                {(book.description || isAdmin) && (
                  <div className="mb-4 rounded-2xl border border-border/50 bg-card p-4">
                    <div className="flex items-center gap-1.5 mb-1.5">
                      <h3 className="text-sm font-bold">简介</h3>
                      {isAdmin && (
                        <button onClick={() => setEditDescOpen(true)} className="rounded-md p-0.5 text-muted-foreground/50 hover:text-primary hover:bg-primary/10 transition-colors">
                          <Pencil className="h-3 w-3" />
                        </button>
                      )}
                    </div>
                    {book.description ? (
                      <>
                        <p className={`text-body leading-relaxed text-muted-foreground text-justify ${!descExpanded ? 'line-clamp-6' : ''}`}>{book.description}</p>
                        {book.description.length > 120 && (
                          <button onClick={() => setDescExpanded(!descExpanded)} className="mt-1 text-xs font-medium text-primary">{descExpanded ? '收起' : '展开'}</button>
                        )}
                      </>
                    ) : (
                      <p className="text-body text-muted-foreground/50 italic">暂无简介</p>
                    )}
                  </div>
                )}

                {/* 评论 */}
                <div className="rounded-2xl border border-border/50 bg-card p-4">
                  <div className="flex items-center gap-2 mb-3">
                    <MessageSquare className="h-4 w-4 text-primary" />
                    <h3 className="text-sm font-bold">评论</h3>
                    <span className="text-xs text-muted-foreground">({commentCount})</span>
                  </div>
                  <CommentList comments={comments} bookId={id} onRefresh={refreshComments} />
                  {hasMoreComments && comments.length > 0 && (
                    <button onClick={() => { const next = commentPage + 1; setCommentPage(next); loadComments(next) }} className="mt-3 w-full rounded-xl bg-muted py-2 text-sm font-medium text-muted-foreground">
                      加载更多评论
                    </button>
                  )}
                </div>
              </div>
            </div>

            {/* 右栏：内容卡片 */}
            <div className="flex-1 min-w-0 space-y-4">
              <MatchScoreCard bookId={id} ms={ms} />
              <SpeedReadCard data={speedReadData} loading={speedReadLoading} />
              <AiQaEntry bookId={id} onOpenChat={handleOpenChat} />
            </div>
          </div>
        </div>
      </div>
      </div>

      {/* 底部操作栏 — 仅移动端 */}
      <div className="md:hidden shrink-0 border-t border-border/50 bg-background/95 backdrop-blur-xl px-3 pt-3 z-20" style={{ paddingBottom: 'calc(env(safe-area-inset-bottom, 0px) + 0.75rem)' }}>
        <div className="flex gap-2">
          <button
            onClick={() => handleOpenChat()}
            className="flex h-11 flex-[2] items-center justify-center gap-2 rounded-2xl bg-primary text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/25 active:scale-[0.97] transition-transform"
          >
            <BlinkingBot className="h-4 w-4" />
            AI 问答
          </button>
          <button
            onClick={() => navigate(`/book/${book.id}/round-table`)}
            className="flex h-11 flex-1 flex-col items-center justify-center rounded-2xl text-xs font-medium transition-all active:scale-[0.97] leading-none gap-1 bg-muted text-foreground hover:bg-muted/80"
          >
            <Users className="h-4 w-4 text-muted-foreground" />
            <span>圆桌派</span>
          </button>
          <button
            onClick={() => navigate(`/book/${book.id}/debate`)}
            className="flex h-11 flex-1 flex-col items-center justify-center rounded-2xl text-xs font-medium transition-all active:scale-[0.97] leading-none gap-1 bg-muted text-foreground hover:bg-muted/80"
          >
            <MessageSquare className="h-4 w-4 text-muted-foreground" />
            <span>奇葩说</span>
          </button>
        </div>
      </div>

      {/* AI 书籍问答 Sheet */}
      {book && (
        <BookChatSheet
          book={book}
          open={showBookChat}
          onOpenChange={(open) => {
            setShowBookChat(open)
            if (!open) setChatInitialQuestion(undefined)
          }}
          initialQuestion={chatInitialQuestion}
          side={isMobile ? 'bottom' : 'right'}
        />
      )}

      {/* 封面全屏查看 */}
      <ImageViewer
        src={book.coverUrl}
        alt={book.title}
        isOpen={showImageViewer}
        onClose={() => setShowImageViewer(false)}
        showChangeCover={isAdmin}
        onChangeCover={handleChangeCover}
      />

      {/* 隐藏的文件输入 */}
      <input
        ref={coverInputRef}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={handleCoverFileChange}
      />

      {/* 垃圾桶确认弹窗 */}
      <Dialog open={trashDialogOpen} onOpenChange={setTrashDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>丢入垃圾桶</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">
            确定将「{book?.title}」丢入垃圾桶吗？丢入垃圾桶后，该图书将不再出现在推荐列表中。你可以前往「我的 → 推荐 → 垃圾桶」中恢复。
          </p>
          <DialogFooter>
            <button
              onClick={() => setTrashDialogOpen(false)}
              className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-muted transition-colors"
            >
              取消
            </button>
            <button
              onClick={handleConfirmTrash}
              disabled={trashing}
              className="rounded-lg bg-red-500 px-4 py-2 text-sm font-medium text-white hover:bg-red-600 transition-colors disabled:opacity-50"
            >
              {trashing ? '处理中...' : '丢入垃圾桶'}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 管理员编辑对话框 */}
      <EditFieldDialog
        open={editTitleOpen}
        onOpenChange={setEditTitleOpen}
        title="书名"
        value={book.title}
        onSubmit={handleUpdateTitle}
        placeholder="请输入书名"
        required
      />
      <EditFieldDialog
        open={editAuthorOpen}
        onOpenChange={setEditAuthorOpen}
        title="作者"
        value={book.author || ''}
        onSubmit={handleUpdateAuthor}
        placeholder="请输入作者（留空则清除）"
      />
      <EditTagsDialog
        open={editTagsOpen}
        onOpenChange={setEditTagsOpen}
        tags={tags}
        onSubmit={handleUpdateTags}
      />
      <EditFieldDialog
        open={editDescOpen}
        onOpenChange={setEditDescOpen}
        title="简介"
        value={book.description || ''}
        onSubmit={handleUpdateDescription}
        type="textarea"
        placeholder="请输入简介（留空则清除）"
      />
    </div>
  )
}
