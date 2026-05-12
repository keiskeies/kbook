import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Heart, Bookmark, MessageCircle, Send, ChevronDown, ChevronUp, Trash2 } from 'lucide-react'
import { createComment, likeComment, unlikeComment, favoriteComment, unfavoriteComment, getCommentReplies, deleteComment } from '@/api/comment'
import type { CommentVO } from '@/api/comment'
import { useAuthStore } from '@/store/auth'
import { toast } from 'sonner'
import { formatRelativeTime } from '@/utils/time'

/** 评论项组件 */
function CommentItem({
  comment,
  onReply,
  onDelete,
  onLikeToggle,
  onFavoriteToggle,
}: {
  comment: CommentVO
  onReply: (comment: CommentVO) => void
  onDelete: (id: number) => void
  onLikeToggle: (id: number, liked: boolean) => void
  onFavoriteToggle: (id: number, favorited: boolean) => void
}) {
  const { userInfo } = useAuthStore()
  const navigate = useNavigate()
  const [showReplies, setShowReplies] = useState(false)
  const [replies, setReplies] = useState<CommentVO[]>([])
  const [loadingReplies, setLoadingReplies] = useState(false)

  const isOwner = userInfo?.id === comment.userId

  const loadReplies = async () => {
    if (showReplies) {
      setShowReplies(false)
      return
    }
    setLoadingReplies(true)
    try {
      const res = await getCommentReplies(comment.id)
      setReplies((res as any)?.data || (res as any) || [])
      setShowReplies(true)
    } catch {
      toast.error('加载回复失败')
    } finally {
      setLoadingReplies(false)
    }
  }

  return (
    <div className="py-3">
      <div className="flex gap-3">
        {/* 头像 */}
        <div
          className="h-8 w-8 shrink-0 rounded-full bg-primary/10 flex items-center justify-center overflow-hidden cursor-pointer"
          onClick={() => navigate(`/user/${comment.userId}`)}
        >
          {comment.userAvatar ? (
            <img src={comment.userAvatar} alt="" className="h-full w-full object-cover" />
          ) : (
            <span className="text-xs font-bold text-primary">{comment.userNickname?.[0] || 'U'}</span>
          )}
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span
              className="text-xs font-semibold cursor-pointer hover:text-primary"
              onClick={() => navigate(`/user/${comment.userId}`)}
            >
              {comment.userNickname || '用户'}
            </span>
            <span className="text-[10px] text-muted-foreground">{formatRelativeTime(comment.createdAt)}</span>
          </div>
          <p className="mt-1 text-sm leading-relaxed whitespace-pre-wrap text-justify">{comment.content}</p>

          {/* 操作栏 */}
          <div className="mt-2 flex items-center gap-4">
            <button
              onClick={() => onLikeToggle(comment.id, comment.liked)}
              className={`flex items-center gap-1 text-xs transition-colors ${comment.liked ? 'text-rose-500' : 'text-muted-foreground hover:text-rose-500'}`}
            >
              <Heart className={`h-3.5 w-3.5 ${comment.liked ? 'fill-rose-500' : ''}`} />
              {comment.likeCount > 0 && comment.likeCount}
            </button>
            <button
              onClick={() => onFavoriteToggle(comment.id, comment.favorited)}
              className={`flex items-center gap-1 text-xs transition-colors ${comment.favorited ? 'text-amber-500' : 'text-muted-foreground hover:text-amber-500'}`}
            >
              <Bookmark className={`h-3.5 w-3.5 ${comment.favorited ? 'fill-amber-500' : ''}`} />
              {comment.favoriteCount > 0 && comment.favoriteCount}
            </button>
            <button
              onClick={() => onReply(comment)}
              className="flex items-center gap-1 text-xs text-muted-foreground hover:text-primary transition-colors"
            >
              <MessageCircle className="h-3.5 w-3.5" />
              回复
            </button>
            {isOwner && (
              <button
                onClick={() => onDelete(comment.id)}
                className="flex items-center gap-1 text-xs text-muted-foreground hover:text-destructive transition-colors"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            )}
          </div>

          {/* 回复展开 */}
          {comment.replyCount > 0 && (
            <button
              onClick={loadReplies}
              className="mt-2 flex items-center gap-1 text-xs text-primary font-medium"
            >
              {loadingReplies ? '加载中...' : (
                <>
                  {showReplies ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
                  {comment.replyCount} 条回复
                </>
              )}
            </button>
          )}

          {/* 回复列表 */}
          {showReplies && replies.length > 0 && (
            <div className="mt-2 space-y-2 pl-2 border-l-2 border-primary/10">
              {replies.map(reply => (
                <CommentItem
                  key={reply.id}
                  comment={reply}
                  onReply={onReply}
                  onDelete={onDelete}
                  onLikeToggle={onLikeToggle}
                  onFavoriteToggle={onFavoriteToggle}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/** 评论列表组件 */
export default function CommentList({
  comments,
  bookId,
  chapterId,
  onRefresh,
}: {
  comments: CommentVO[]
  bookId: number
  chapterId?: string | null
  onRefresh: () => void
}) {
  const { userInfo } = useAuthStore()
  const [replyTo, setReplyTo] = useState<CommentVO | null>(null)
  const [content, setContent] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async () => {
    if (!content.trim()) {
      toast.error('先写点什么吧')
      return
    }
    setSubmitting(true)
    try {
      await createComment({
        bookId,
        chapterId: chapterId || null,
        parentId: replyTo?.id || null,
        content: content.trim(),
      })
      setContent('')
      setReplyTo(null)
      toast.success('评论已发布')
      onRefresh()
    } catch (err: any) {
      toast.error(err.message || '评论失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleLikeToggle = async (id: number, liked: boolean) => {
    try {
      if (liked) await unlikeComment(id)
      else await likeComment(id)
      onRefresh()
    } catch {
      toast.error('操作失败')
    }
  }

  const handleFavoriteToggle = async (id: number, favorited: boolean) => {
    try {
      if (favorited) await unfavoriteComment(id)
      else await favoriteComment(id)
      onRefresh()
    } catch {
      toast.error('操作失败')
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteComment(id)
      toast.success('已删除')
      onRefresh()
    } catch {
      toast.error('删除失败')
    }
  }

  return (
    <div>
      {/* 评论输入框 */}
      <div className="mb-4">
        {replyTo && (
          <div className="mb-2 flex items-center gap-2 text-xs text-primary">
            <span>回复 @{replyTo.userNickname}</span>
            <button onClick={() => setReplyTo(null)} className="text-muted-foreground hover:text-destructive">取消</button>
          </div>
        )}
        <div className="flex gap-2">
          <input
            type="text"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder={replyTo ? `回复 @${replyTo.userNickname}...` : '写下你的评论...'}
            maxLength={2000}
            className="flex-1 rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) handleSubmit() }}
          />
          <button
            onClick={handleSubmit}
            disabled={submitting || !content.trim()}
            className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary text-primary-foreground disabled:opacity-50 active:scale-95 transition-transform"
          >
            <Send className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* 评论列表 */}
      {comments.length === 0 ? (
        <div className="py-8 text-center text-sm text-muted-foreground">
          暂无评论，来说两句吧~
        </div>
      ) : (
        <div className="divide-y divide-border/50">
          {comments.map(comment => (
            <CommentItem
              key={comment.id}
              comment={comment}
              onReply={(c) => setReplyTo(c)}
              onDelete={handleDelete}
              onLikeToggle={handleLikeToggle}
              onFavoriteToggle={handleFavoriteToggle}
            />
          ))}
        </div>
      )}
    </div>
  )
}
