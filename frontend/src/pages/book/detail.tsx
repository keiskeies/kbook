import { useEffect, useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { ArrowLeft, Bookmark, BookmarkCheck, BookOpen, Star, Eye, MessageSquare, Sparkles } from 'lucide-react'
import { getBook, rateBook } from '@/api/book'
import { checkInBookshelf, addToBookshelf, removeFromBookshelf } from '@/api/bookshelf'
import { getProgress } from '@/api/progress'
import { getBookComments, countBookComments } from '@/api/comment'
import type { Book } from '@/types/book'
import type { CommentVO } from '@/api/comment'
import { formatProgress, formatFileSize, parseFormatTags } from '@/types/book'
import CommentList from '@/components/comment/CommentList'
import BookChatSheet from '@/components/book/BookChatSheet'
import BookCover from '@/components/book/BookCover'
import { toast } from 'sonner'

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
  const [descExpanded, setDescExpanded] = useState(false)

  const id = Number(bookId)

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
    ]).then(([bookRes, shelfRes, progressRes]) => {
      setBook(bookRes as unknown as Book)
      setInShelf((shelfRes as any) || false)
      setProgress((progressRes as any)?.progress || 0)
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

  const handleRate = async (rating: number) => {
    if (!book) return
    try {
      await rateBook(book.id, rating)
      setBook({ ...book, rating })
      setUserRating(rating)
      setShowRating(false)
      toast.success(`评分 ${rating} 星已保存`)
    } catch {
      toast.error('评分暂时无法提交')
    }
  }

  const refreshComments = () => {
    setCommentPage(1)
    loadComments(1)
    countBookComments(id).then(res => setCommentCount((res as any)?.data || (res as any) || 0)).catch(() => {})
  }

  if (loading || !book) {
    return (
      <div className="min-h-screen bg-background">
        <div className="h-14 animate-pulse bg-muted" />
        <div className="p-4 space-y-4">
          <div className="h-40 rounded-2xl bg-muted animate-pulse" />
          <div className="h-6 w-3/4 rounded bg-muted animate-pulse" />
          <div className="h-4 w-1/2 rounded bg-muted animate-pulse" />
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
          <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} size="xl" className="flex-shrink-0 shadow-lg" />
          <div className="flex flex-1 flex-col justify-between">
            <div>
              <h2 className="text-lg font-bold leading-tight">{book.title}</h2>
              {book.author && <p className="mt-1 text-sm text-muted-foreground">{book.author}</p>}
            </div>
            <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
              <button onClick={() => setShowRating(!showRating)} className="flex items-center gap-1 active:scale-95 transition-transform">
                <Star className="h-3 w-3 fill-amber-400 text-amber-400" />
                <span className="font-semibold text-foreground">{book.rating > 0 ? book.rating.toFixed(1) : '暂无'}</span>
                <span className="text-[10px] text-primary">评</span>
              </button>
              <span className="flex items-center gap-1"><Eye className="h-3 w-3" />{book.readCount} 阅读</span>
              <span className="rounded-md bg-primary/8 px-1.5 py-0.5 font-medium text-primary">{book.format === 'EPUB' ? '电子书' : book.format}</span>
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

        {tags.length > 0 && (
          <div className="mt-4 flex flex-wrap gap-2">
            {tags.map((tag) => (
              <span key={tag} className="rounded-full bg-primary/8 px-3 py-1 text-xs font-semibold text-primary border border-primary/10">{tag}</span>
            ))}
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

      {/* 简介 */}
      {book.description && (
        <div className="px-4 pb-4">
          <h3 className="mb-2 text-sm font-bold">简介</h3>
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
        </div>
      )}

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
      <div className="fixed bottom-0 left-0 right-0 border-t border-border/50 bg-background/80 backdrop-blur-xl p-4 pb-safe-bottom">
        <div className="flex gap-3">
          <button onClick={toggleShelf} className={`flex h-11 flex-1 items-center justify-center gap-2 rounded-2xl text-sm font-semibold transition-all active:scale-[0.97] ${inShelf ? 'bg-primary/10 text-primary border border-primary/20' : 'bg-muted text-foreground hover:bg-muted/80'}`}>
            {inShelf ? <BookmarkCheck className="h-4 w-4" /> : <Bookmark className="h-4 w-4" />}
            {inShelf ? '已在书架' : '加入书架'}
          </button>
          {book.contentEmbedded && (
            <button onClick={() => setShowBookChat(true)} className="flex h-11 flex-1 items-center justify-center gap-2 rounded-2xl bg-accent text-sm font-semibold text-accent-foreground transition-all active:scale-[0.97]">
              <Sparkles className="h-4 w-4" />
              AI 问答
            </button>
          )}
          <button onClick={() => navigate(`/reader/${book.id}`)} className="flex h-11 flex-[2] items-center justify-center gap-2 rounded-2xl bg-primary text-sm font-semibold text-primary-foreground shadow-lg shadow-primary/25 active:scale-[0.97] transition-transform">
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
          onOpenChange={setShowBookChat}
        />
      )}
    </div>
  )
}
