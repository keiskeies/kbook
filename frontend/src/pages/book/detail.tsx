import { useEffect, useState, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Bookmark, BookmarkCheck, BookOpen, Star, Eye, MessageSquare, Sparkles,
  Pencil, X, Plus, Trash2, Clock, Target, Users, UserX, Lightbulb, Gauge,
  ChevronDown, ChevronUp, MessageCircle,
} from 'lucide-react'
import {
  getBook, rateBook, updateBookCover, updateBookTitle, updateBookAuthor,
  updateBookDescription, updateFormatTags, getMatchScoreDetail, getBookSpeedRead,
} from '@/api/book'
import type { MatchScoreDetail, DimensionScore, BookSpeedRead } from '@/api/book'
import { checkInBookshelf, addToBookshelf, removeFromBookshelf } from '@/api/bookshelf'
import { moveToTrash, checkInTrash } from '@/api/bookTrash'
import { getProgress } from '@/api/progress'
import { getBookComments, countBookComments } from '@/api/comment'
import { getBookSuggestedQuestions } from '@/api/bookChat'
import type { Book } from '@/types/book'
import type { CommentVO } from '@/api/comment'
import { formatProgress, formatFileSize, parseFormatTags } from '@/types/book'
import CommentList from '@/components/comment/CommentList'
import BookChatSheet from '@/components/book/BookChatSheet'
import { BlinkingBot } from '@/components/layout/TabBar'
import BookCover from '@/components/book/BookCover'
import ImageViewer from '@/components/common/ImageViewer'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { useMatchScores } from '@/hooks/useMatchScores'
import { useAuthStore } from '@/store/auth'
import { toast } from 'sonner'

function RatingBadgeCN({ rating }: { rating: number | undefined | null }) {
  if (rating == null || rating < 0) return null
  const r = Number(rating.toFixed(1))
  let colorClass = ''
  if (r >= 5.0) colorClass = 'text-red-600 dark:text-red-400'
  else if (r >= 4.5) colorClass = 'text-orange-600 dark:text-orange-400'
  else if (r >= 4.0) colorClass = 'text-amber-600 dark:text-amber-400'
  else if (r >= 3.0) colorClass = 'text-emerald-600 dark:text-emerald-400'
  else if (r >= 2.5) colorClass = 'text-teal-600 dark:text-teal-400'
  else colorClass = 'text-slate-400 dark:text-slate-500'

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-semibold ${colorClass}`}>
      <Star className="h-3 w-3" />
      评分：{r}
    </span>
  )
}

function MatchBadgeCN({ score }: { score: number | undefined | null }) {
  const pct = Math.round(Math.max(0, score ?? 0) * 100)
  let colorClass = ''
  if (pct >= 100) colorClass = 'text-red-600 dark:text-red-400'
  else if (pct >= 80) colorClass = 'text-orange-600 dark:text-orange-400'
  else if (pct >= 60) colorClass = 'text-amber-600 dark:text-amber-400'
  else if (pct >= 50) colorClass = 'text-emerald-600 dark:text-emerald-400'
  else if (pct >= 40) colorClass = 'text-teal-600 dark:text-teal-400'
  else colorClass = 'text-slate-400 dark:text-slate-500'

  return (
    <span className={`inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-semibold ${colorClass}`}>
      <Sparkles className="h-3 w-3" />
      匹配度：{pct}%
    </span>
  )
}

function MatchScoreCard({ bookId, ms }: { bookId: number; ms: number | undefined | null }) {
  const [detail, setDetail] = useState<MatchScoreDetail | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!bookId) return
    setLoading(true)
    getMatchScoreDetail(bookId)
      .then((res: any) => {
        const data = res?.data ?? res
        if (data) setDetail(data)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [bookId])

  const overallPct = Math.round(Math.max(0, detail?.overallScore ?? ms ?? 0) * 100)

  if (loading) {
    return (
      <div className="mx-4 mb-4 rounded-2xl border border-border/50 bg-card p-4">
        <div className="flex items-center gap-3">
          <div className="h-14 w-14 animate-pulse rounded-full bg-muted" />
          <div className="flex-1 space-y-2">
            <div className="h-4 w-24 animate-pulse rounded bg-muted" />
            <div className="h-3 w-16 animate-pulse rounded bg-muted" />
          </div>
        </div>
      </div>
    )
  }

  if (!detail && overallPct <= 0) return null

  const dims = detail?.dimensions || []

  return (
    <div className="mx-4 mb-4 rounded-2xl border border-border/50 bg-card p-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-14 w-14 items-center justify-center rounded-full bg-primary/10">
            <span className="text-lg font-bold text-primary">{overallPct}%</span>
          </div>
          <div>
            <p className="text-sm font-bold">匹配度分析</p>
            <p className="text-xs text-muted-foreground">
              {detail ? `覆盖 ${detail.matchedDimensions} 个维度` : '基于你的阅读偏好'}
            </p>
          </div>
        </div>
        {dims.length > 0 && (
          <button
            onClick={() => setExpanded(!expanded)}
            className="flex h-8 w-8 items-center justify-center rounded-full hover:bg-muted transition-colors"
          >
            {expanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
          </button>
        )}
      </div>

      {expanded && dims.length > 0 && (
        <div className="mt-3 space-y-2.5 border-t border-border/50 pt-3">
          {dims.map((d: DimensionScore) => {
            const pct = Math.round(Math.max(0, d.score) * 100)
            return (
              <div key={d.dimension}>
                <div className="flex items-center justify-between text-xs mb-1">
                  <span className="text-muted-foreground">{d.label}</span>
                  <span className="font-medium">{pct}%</span>
                </div>
                <div className="h-1.5 w-full rounded-full bg-primary/10">
                  <div
                    className="h-full rounded-full bg-primary transition-all"
                    style={{ width: `${pct}%` }}
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

function SpeedReadCard({ bookId }: { bookId: number }) {
  const [data, setData] = useState<BookSpeedRead | null>(null)
  const [loading, setLoading] = useState(false)
  const [expanded, setExpanded] = useState(true)

  useEffect(() => {
    if (!bookId) return
    setLoading(true)
    getBookSpeedRead(bookId)
      .then((res: any) => {
        const d = res?.data ?? res
        if (d) setData(d)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [bookId])

  if (loading) {
    return (
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
    )
  }

  if (!data) return null

  return (
    <div className="mx-4 mb-4 rounded-2xl border border-border/50 bg-card p-4">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between"
      >
        <div className="flex items-center gap-2">
          <Clock className="h-4 w-4 text-primary" />
          <h3 className="text-sm font-bold">3分钟速读</h3>
          {data.difficulty && (
            <span className="rounded-md bg-primary/8 px-1.5 py-0.5 text-[10px] font-medium text-primary">
              {data.difficulty}
            </span>
          )}
        </div>
        {expanded ? <ChevronUp className="h-4 w-4 text-muted-foreground" /> : <ChevronDown className="h-4 w-4 text-muted-foreground" />}
      </button>

      {expanded && (
        <div className="mt-3 space-y-3">
          {data.corePoints && data.corePoints.length > 0 && (
            <div className="space-y-2">
              <div className="flex items-center gap-1.5 text-xs font-semibold text-foreground">
                <Target className="h-3.5 w-3.5 text-primary" />
                核心观点
              </div>
              <ul className="space-y-1.5">
                {data.corePoints.map((point, idx) => (
                  <li key={idx} className="flex items-start gap-2 text-sm text-muted-foreground">
                    <span className="mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[10px] font-bold text-primary">
                      {idx + 1}
                    </span>
                    <span>{point}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {data.suitableFor && data.suitableFor.length > 0 && (
            <div className="space-y-2">
              <div className="flex items-center gap-1.5 text-xs font-semibold text-emerald-600 dark:text-emerald-400">
                <Users className="h-3.5 w-3.5" />
                适合谁读
              </div>
              <div className="flex flex-wrap gap-1.5">
                {data.suitableFor.map((item, idx) => (
                  <span
                    key={idx}
                    className="rounded-full bg-emerald-50 px-2.5 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300"
                  >
                    {item}
                  </span>
                ))}
              </div>
            </div>
          )}

          {data.notSuitableFor && data.notSuitableFor.length > 0 && (
            <div className="space-y-2">
              <div className="flex items-center gap-1.5 text-xs font-semibold text-rose-600 dark:text-rose-400">
                <UserX className="h-3.5 w-3.5" />
                不适合谁读
              </div>
              <div className="flex flex-wrap gap-1.5">
                {data.notSuitableFor.map((item, idx) => (
                  <span
                    key={idx}
                    className="rounded-full bg-rose-50 px-2.5 py-0.5 text-xs font-medium text-rose-700 dark:bg-rose-950/40 dark:text-rose-300"
                  >
                    {item}
                  </span>
                ))}
              </div>
            </div>
          )}

          {data.takeaways && data.takeaways.length > 0 && (
            <div className="space-y-2">
              <div className="flex items-center gap-1.5 text-xs font-semibold text-amber-600 dark:text-amber-400">
                <Lightbulb className="h-3.5 w-3.5" />
                读完能收获什么
              </div>
              <ul className="space-y-1">
                {data.takeaways.map((item, idx) => (
                  <li key={idx} className="flex items-start gap-2 text-sm text-muted-foreground">
                    <Gauge className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function AiQaEntry({
  bookId,
  onOpenChat,
}: {
  bookId: number
  onOpenChat: (initialQuestion?: string) => void
}) {
  const [questions, setQuestions] = useState<string[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!bookId) return
    setLoading(true)
    getBookSuggestedQuestions(bookId)
      .then((res: any) => {
        const data = res?.data ?? res
        if (Array.isArray(data)) setQuestions(data)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [bookId])

  return (
    <div className="mx-4 mb-4 rounded-2xl border border-border/50 bg-card p-4">
      <div className="flex items-center gap-2 mb-3">
        <MessageCircle className="h-4 w-4 text-primary" />
        <h3 className="text-sm font-bold">AI 深度问答</h3>
      </div>
      <p className="text-xs text-muted-foreground mb-3">
        基于全书内容，AI 可以为你解答任何关于这本书的问题
      </p>

      {loading ? (
        <div className="flex flex-wrap gap-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-8 w-28 animate-pulse rounded-full bg-muted" />
          ))}
        </div>
      ) : questions.length > 0 ? (
        <div className="flex flex-wrap gap-2">
          {questions.map((q, idx) => (
            <button
              key={idx}
              onClick={() => onOpenChat(q)}
              className="rounded-full border border-primary/20 bg-primary/5 px-3 py-1.5 text-xs font-medium text-primary hover:bg-primary/10 transition-colors"
            >
              {q}
            </button>
          ))}
        </div>
      ) : (
        <button
          onClick={() => onOpenChat()}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-primary/5 py-2.5 text-sm font-medium text-primary hover:bg-primary/10 transition-colors"
        >
          <Sparkles className="h-4 w-4" />
          向 AI 提问
        </button>
      )}
    </div>
  )
}

function EditFieldDialog({
  open,
  onOpenChange,
  title,
  value,
  onSubmit,
  type = 'input',
  placeholder,
  required = false,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  value: string
  onSubmit: (value: string) => Promise<void>
  type?: 'input' | 'textarea'
  placeholder?: string
  required?: boolean
}) {
  const [editValue, setEditValue] = useState(value)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (open) setEditValue(value)
  }, [open, value])

  const handleSubmit = async () => {
    if (required && !editValue.trim()) {
      toast.error('内容不能为空')
      return
    }
    setSubmitting(true)
    try {
      await onSubmit(editValue)
      onOpenChange(false)
    } catch (err: any) {
      toast.error(err?.message || '修改失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>修改{title}</DialogTitle>
        </DialogHeader>
        {type === 'textarea' ? (
          <Textarea
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            placeholder={placeholder}
            className="min-h-32"
            autoFocus
          />
        ) : (
          <Input
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            placeholder={placeholder}
            autoFocus
            onKeyDown={(e) => { if (e.key === 'Enter') handleSubmit() }}
          />
        )}
        <DialogFooter>
          <button
            onClick={() => onOpenChange(false)}
            className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-muted transition-colors"
          >
            取消
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {submitting ? '保存中...' : '保存'}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function EditTagsDialog({
  open,
  onOpenChange,
  tags,
  onSubmit,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  tags: string[]
  onSubmit: (tags: string[]) => Promise<void>
}) {
  const [editTags, setEditTags] = useState<string[]>(tags)
  const [newTag, setNewTag] = useState('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (open) setEditTags(tags)
  }, [open, tags])

  const addTag = () => {
    const trimmed = newTag.trim()
    if (trimmed && !editTags.includes(trimmed)) {
      setEditTags([...editTags, trimmed])
      setNewTag('')
    }
  }

  const removeTag = (tag: string) => {
    setEditTags(editTags.filter(t => t !== tag))
  }

  const handleSubmit = async () => {
    setSubmitting(true)
    try {
      await onSubmit(editTags)
      onOpenChange(false)
    } catch (err: any) {
      toast.error(err?.message || '修改失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>修改标签</DialogTitle>
        </DialogHeader>
        <div className="flex flex-wrap gap-2 min-h-8">
          {editTags.map((tag) => (
            <span
              key={tag}
              className="inline-flex items-center gap-1 rounded-full bg-primary/8 px-3 py-1 text-xs font-semibold text-primary border border-primary/10"
            >
              {tag}
              <button onClick={() => removeTag(tag)} className="hover:text-destructive transition-colors">
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
        </div>
        <div className="flex gap-2">
          <Input
            value={newTag}
            onChange={(e) => setNewTag(e.target.value)}
            placeholder="输入新标签"
            onKeyDown={(e) => { if (e.key === 'Enter') addTag() }}
          />
          <button
            onClick={addTag}
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <Plus className="h-4 w-4" />
          </button>
        </div>
        <DialogFooter>
          <button
            onClick={() => onOpenChange(false)}
            className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-muted transition-colors"
          >
            取消
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {submitting ? '保存中...' : '保存'}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export default function BookDetailPage() {
  const { bookId } = useParams<{ bookId: string }>()
  const navigate = useNavigate()
  const [book, setBook] = useState<Book | null>(null)
  const [inShelf, setInShelf] = useState(false)
  const [progress, setProgress] = useState<number>(0)
  const [loading, setLoading] = useState(true)
  const [showRating, setShowRating] = useState(false)
  const [userRating, setUserRating] = useState(0)
  const [hoverStar, setHoverStar] = useState(0)
  const [comments, setComments] = useState<CommentVO[]>([])
  const [commentCount, setCommentCount] = useState(0)
  const [commentPage, setCommentPage] = useState(1)
  const [hasMoreComments, setHasMoreComments] = useState(true)
  const [showBookChat, setShowBookChat] = useState(false)
  const [chatInitialQuestion, setChatInitialQuestion] = useState<string | undefined>(undefined)
  const [descExpanded, setDescExpanded] = useState(false)
  const [showImageViewer, setShowImageViewer] = useState(false)
  const coverInputRef = useRef<HTMLInputElement>(null)

  const [editTitleOpen, setEditTitleOpen] = useState(false)
  const [editAuthorOpen, setEditAuthorOpen] = useState(false)
  const [editTagsOpen, setEditTagsOpen] = useState(false)
  const [editDescOpen, setEditDescOpen] = useState(false)
  const [inTrash, setInTrash] = useState(false)
  const [trashDialogOpen, setTrashDialogOpen] = useState(false)
  const [trashing, setTrashing] = useState(false)

  const { userInfo } = useAuthStore()
  const isAdmin = userInfo?.role === 'ADMIN'

  const id = Number(bookId)
  const matchScores = useMatchScores(book ? [book.id] : [])
  const ms = book ? matchScores?.[String(book.id)] : null

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
    Promise.all([
      getBook(id),
      checkInBookshelf(id).catch(() => ({ data: false })),
      getProgress(id).catch(() => ({ data: null })),
      checkInTrash(id).catch(() => ({ data: false })),
    ]).then(([bookRes, shelfRes, progressRes, trashRes]) => {
      setBook(bookRes as unknown as Book)
      setInShelf((shelfRes as any) || false)
      setInTrash((trashRes as any) || false)
      const progressData = (progressRes as any)
      setProgress(progressData?.progress || 0)
      if (progressData?.userRating) {
        setUserRating(progressData.userRating)
      }
      setLoading(false)
    })
    loadComments(1)
    countBookComments(id).then(res => setCommentCount((res as any)?.data || (res as any) || 0)).catch(() => {})
  }, [bookId, id, loadComments])

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
      <div className="min-h-screen bg-background page-enter pb-20">
        <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
          <div className="h-9 w-9 animate-pulse rounded-xl bg-muted" />
          <div className="h-5 flex-1 animate-pulse rounded bg-muted" />
          <div className="h-9 w-9 animate-pulse rounded-xl bg-muted" />
        </header>

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

        <div className="fixed bottom-0 left-0 right-0 border-t border-border/50 bg-background/80 backdrop-blur-xl p-3 pb-safe-bottom">
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
    <div className="min-h-screen bg-background page-enter pb-20">
      {/* 顶部导航 */}
      <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="flex-1 truncate text-base font-bold">{book.title}</h1>
        {!inTrash && (
          <button onClick={handleTrash} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors">
            <Trash2 className="h-5 w-5 text-muted-foreground" />
          </button>
        )}
        <button onClick={toggleShelf} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors">
          {inShelf ? (
            <BookmarkCheck className="h-5 w-5 text-primary" />
          ) : (
            <Bookmark className="h-5 w-5" />
          )}
        </button>
      </header>

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
                {book.author && <p className="text-sm text-muted-foreground">{book.author}</p>}
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
                <span className="text-[10px] text-primary ml-1">{userRating > 0 ? '已评' : '评'}</span>
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
                        ? 'fill-amber-400 text-amber-400'
                        : 'text-muted-foreground/30 hover:text-amber-400/50'
                    }`}
                  />
                </button>
              ))}
              <span className="ml-2 text-sm font-semibold text-foreground">
                {hoverStar || userRating || ''}{(hoverStar || userRating) ? ' 星' : ''}
              </span>
            </div>
            <p className="mt-2 text-[10px] text-muted-foreground">点击星星评分（1-5 星）</p>
          </div>
        )}
      </div>

      {/* 匹配度卡片 */}
      <MatchScoreCard bookId={id} ms={ms} />

      {/* 3分钟速读 */}
      <SpeedReadCard bookId={id} />

      {/* 简介 */}
      {(book.description || isAdmin) && (
        <div className="px-4 pb-4">
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
                className={`text-sm leading-relaxed text-muted-foreground text-justify ${!descExpanded ? 'line-clamp-3' : ''}`}
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
            <p className="text-sm text-muted-foreground/50 italic">暂无简介</p>
          )}
        </div>
      )}

      {/* AI 深度问答入口 */}
      <AiQaEntry bookId={id} onOpenChat={handleOpenChat} />

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

      {/* 底部操作栏 */}
      <div className="fixed bottom-0 left-0 right-0 border-t border-border/50 bg-background/80 backdrop-blur-xl p-3 pb-safe-bottom">
        <div className="flex gap-2">
          <button
            onClick={toggleShelf}
            className={`flex h-11 w-14 flex-col items-center justify-center rounded-2xl text-[10px] font-medium transition-all active:scale-[0.97] leading-none gap-1 ${inShelf ? 'bg-primary/10 text-primary border border-primary/20' : 'bg-muted text-foreground hover:bg-muted/80'}`}
          >
            {inShelf ? <BookmarkCheck className="h-4 w-4" /> : <Bookmark className="h-4 w-4" />}
            <span className="truncate">{inShelf ? '在书架' : '加书架'}</span>
          </button>

          <button
            onClick={() => handleOpenChat()}
            className="flex h-11 w-14 flex-col items-center justify-center rounded-2xl bg-accent text-[10px] font-medium text-accent-foreground transition-all active:scale-[0.97] leading-none gap-1"
          >
            <BlinkingBot className="h-4 w-4" />
            <span>AI 问答</span>
          </button>

          <button
            onClick={() => navigate(`/reader/${book.id}`)}
            className="flex h-11 flex-1 items-center justify-center gap-2 rounded-2xl bg-primary text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/25 active:scale-[0.97] transition-transform"
          >
            <BookOpen className="h-4 w-4" />
            {progress > 0 ? '继续阅读' : '开始阅读'}
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
