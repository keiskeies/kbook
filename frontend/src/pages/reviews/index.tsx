import { useEffect, useState, useCallback, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { ArrowLeft, MessageSquare, Heart, Bookmark } from 'lucide-react'
import { getTopRatedComments } from '@/api/comment'
import type { CommentVO } from '@/api/comment'
import { formatRelativeTime } from '@/utils/time'
import BookCover from '@/components/book/BookCover'

export default function ReviewsPage() {
  const navigate = useNavigate()
  const goBack = useGoBack()
  const [comments, setComments] = useState<CommentVO[]>([])
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(true)
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  const loadData = useCallback(async (p: number) => {
    try {
      const res = await getTopRatedComments(1, p, 20)
      const data = (res as any)?.data || (res as any)
      if (data?.list) {
        setComments(prev => p === 1 ? data.list : [...prev, ...data.list])
        setHasMore(data.list.length >= 20)
      }
    } catch { /* ignore */ } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadData(1) }, [loadData])

  const loadMore = () => {
    const next = page + 1
    setPage(next)
    loadData(next)
  }

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    )
  }

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background page-enter">
      {/* 顶部 */}
      <header className="shrink-0 z-10 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-base font-bold">高分书评</h1>
      </header>

      {/* 书评列表 - 论坛风格 */}
      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 py-2">
        {comments.length === 0 ? (
          <div className="py-16 text-center text-sm text-muted-foreground">
            <MessageSquare className="mx-auto h-10 w-10 text-muted-foreground/30" />
            <p className="mt-3">暂无高分书评</p>
          </div>
        ) : (
          <div className="divide-y divide-border/50">
            {comments.map(comment => (
              <div key={comment.id} className="py-4">
                {/* 用户信息行 */}
                <div className="flex items-center gap-2.5">
                  <div
                    className="h-9 w-9 shrink-0 rounded-full bg-primary/10 flex items-center justify-center overflow-hidden cursor-pointer"
                    onClick={() => navigate(`/user/${comment.userId}`)}
                  >
                    {comment.userAvatar ? (
                      <img src={comment.userAvatar} alt="" className="h-full w-full object-cover" />
                    ) : (
                      <span className="text-xs font-bold text-primary">{comment.userNickname?.[0] || 'U'}</span>
                    )}
                  </div>
                  <div className="flex-1">
                    <span
                      className="text-sm font-semibold cursor-pointer hover:text-primary"
                      onClick={() => navigate(`/user/${comment.userId}`)}
                    >
                      {comment.userNickname || '用户'}
                    </span>
                    <p className="text-[10px] text-muted-foreground">{formatRelativeTime(comment.createdAt)}</p>
                  </div>
                </div>

                {/* 评论内容 */}
                <div className="mt-2.5 ml-[46px]">
                  {/* 关联书籍 */}
                  <button
                    onClick={() => navigate(`/book/${comment.bookId}`)}
                    className="mb-2 flex items-center gap-2.5 rounded-lg bg-primary/5 px-2.5 py-2 hover:bg-primary/10 transition-colors w-full text-left"
                  >
                    <BookCover coverUrl={comment.bookCoverUrl ?? null} title={comment.bookTitle || '查看书籍'} size="xs" />
                    <span className="text-xs font-medium text-primary truncate">{comment.bookTitle || '查看书籍'}</span>
                  </button>

                  <p className="text-sm leading-relaxed whitespace-pre-wrap text-justify">{comment.content}</p>

                  {/* 互动数据 */}
                  <div className="mt-3 flex items-center gap-5 text-xs text-muted-foreground">
                    <span className="flex items-center gap-1">
                      <Heart className={`h-3.5 w-3.5 ${comment.likeCount > 0 ? 'text-danger' : ''}`} />
                      {comment.likeCount}
                    </span>
                    <span className="flex items-center gap-1">
                      <Bookmark className={`h-3.5 w-3.5 ${comment.favoriteCount > 0 ? 'text-warning' : ''}`} />
                      {comment.favoriteCount}
                    </span>
                    <span className="flex items-center gap-1">
                      <MessageSquare className="h-3.5 w-3.5" />
                      {comment.replyCount}
                    </span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {hasMore && comments.length > 0 && (
          <button
            onClick={loadMore}
            className="mt-4 mb-8 w-full rounded-xl bg-muted py-2.5 text-sm font-medium text-muted-foreground hover:bg-muted/80 active:scale-[0.98] transition-all"
          >
            加载更多
          </button>
        )}
      </div>
    </div>
  )
}
