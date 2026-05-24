import { useEffect, useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {ArrowLeft, UserPlus, UserMinus, CheckCircle2, MessageSquare, Heart, BookOpen, MessageCircle} from 'lucide-react'
import { getUserProfile, getUserBooks, getUserComments } from '@/api/userProfile'
import { followUser, unfollowUser } from '@/api/follow'
import { startConversation } from '@/api/chat'
import type { UserProfileVO, UserBookItem } from '@/api/userProfile'
import type { CommentVO } from '@/api/comment'
import { useAuthStore } from '@/store/auth'
import { formatRelativeTime } from '@/utils/time'
import { toast } from 'sonner'
import BookCover from '@/components/book/BookCover'

const MOOD_EMOJI: Record<string, string> = {
  HAPPY: '😊',
  CALM: '😌',
  ANXIOUS: '😰',
  SAD: '😢',
  MOTIVATED: '🔥',
  TIRED: '😴',
  CURIOUS: '🤔',
}

export default function UserProfilePage() {
  const { userId } = useParams<{ userId: string }>()
  const navigate = useNavigate()
  const { userInfo } = useAuthStore()
  const [profile, setProfile] = useState<UserProfileVO | null>(null)
  const [readingBooks, setReadingBooks] = useState<UserBookItem[]>([])
  const [completedBooks, setCompletedBooks] = useState<UserBookItem[]>([])
  const [comments, setComments] = useState<CommentVO[]>([])
  const [tab, setTab] = useState<'reading' | 'completed' | 'comments'>('reading')
  const [loading, setLoading] = useState(true)

  const id = Number(userId)

  const loadProfile = useCallback(async () => {
    try {
      const res = await getUserProfile(id)
      setProfile((res as any)?.data || (res as any))
    } catch { /* ignore */ }
  }, [id])

  const loadBooks = useCallback(async () => {
    try {
      const res = await getUserBooks(id)
      const data = (res as any)?.data || (res as any)
      setReadingBooks(data?.readingBooks || [])
      setCompletedBooks(data?.completedBooks || [])
    } catch { /* ignore */ }
  }, [id])

  const loadComments = useCallback(async () => {
    try {
      const res = await getUserComments(id, 1, 20)
      const data = (res as any)?.data || (res as any)
      setComments(data?.list || [])
    } catch { /* ignore */ }
  }, [id])

  useEffect(() => {
    Promise.all([loadProfile(), loadBooks(), loadComments()]).finally(() => setLoading(false))
  }, [loadProfile, loadBooks, loadComments])

  const handleFollowToggle = async () => {
    if (!profile) return
    try {
      if (profile.isFollowing) {
        await unfollowUser(id)
        setProfile({ ...profile, isFollowing: false, followerCount: profile.followerCount - 1 })
      } else {
        await followUser(id)
        setProfile({ ...profile, isFollowing: true, followerCount: profile.followerCount + 1 })
      }
    } catch (err: any) {
      toast.error(err.message || '操作未完成')
    }
  }

  const handleSendMessage = async () => {
    try {
      const res = await startConversation(id)
      const data = (res as any)?.data || res
      if (data) {
        navigate(`/chat/${data.id}`)
      }
    } catch (err: any) {
      toast.error(err.message || '无法发起私信')
    }
  }

  const isSelf = userInfo?.id === id

  if (loading || !profile) {
    return (
      <div className="flex h-screen items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background page-enter pb-20">
      <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl">
        <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-base font-bold">{profile.nickname}</h1>
      </header>

      <div className="bg-gradient-to-b from-primary/5 to-transparent px-4 py-5">
        <div className="flex items-center gap-4">
          <div className="h-16 w-16 shrink-0 rounded-full bg-primary/10 flex items-center justify-center overflow-hidden ring-2 ring-primary/20">
            {profile.avatar ? (
              <img src={profile.avatar} alt="" className="h-full w-full object-cover" />
            ) : (
              <span className="text-2xl font-bold text-primary">{profile.nickname?.[0] || 'U'}</span>
            )}
          </div>
          <div className="flex-1">
            <h2 className="text-lg font-bold">{profile.nickname}</h2>
            {profile.bio && <p className="mt-0.5 text-sm text-muted-foreground line-clamp-2 text-justify">{profile.bio}</p>}
            <div className="mt-2 flex items-center gap-3 text-xs text-muted-foreground">
              {profile.age && <span>{profile.age}岁</span>}
              {profile.gender === 'MALE' && <span>男</span>}
              {profile.gender === 'FEMALE' && <span>女</span>}
              {profile.mbti && <span className="rounded bg-primary/10 px-1.5 py-0.5 text-primary font-medium">{profile.mbti}</span>}
              {profile.mood && <span className="text-xs">{MOOD_EMOJI[profile.mood] || ''}</span>}
            </div>
          </div>
        </div>

        <div className="mt-4 flex items-center gap-6">
          <button onClick={() => navigate(`/user/${id}/follow/followers`)} className="text-center">
            <p className="text-lg font-bold">{profile.followerCount}</p>
            <p className="text-[10px] text-muted-foreground">粉丝</p>
          </button>
          <button onClick={() => navigate(`/user/${id}/follow/followings`)} className="text-center">
            <p className="text-lg font-bold">{profile.followingCount}</p>
            <p className="text-[10px] text-muted-foreground">关注</p>
          </button>
          <div className="text-center">
            <p className="text-lg font-bold">{profile.completedBooks}</p>
            <p className="text-[10px] text-muted-foreground">已读完</p>
          </div>
          <div className="text-center">
            <p className="text-lg font-bold">{profile.readingBooks}</p>
            <p className="text-[10px] text-muted-foreground">在读</p>
          </div>
        </div>

        {!isSelf && (
          <div className="mt-4 flex gap-2">
            <button
              onClick={handleFollowToggle}
              className={`flex-1 flex items-center justify-center gap-2 rounded-xl py-2.5 text-sm font-semibold transition-all active:scale-[0.97] ${
                profile.isFollowing
                  ? 'bg-muted text-foreground border border-border/50'
                  : 'bg-primary text-primary-foreground shadow-md shadow-primary/20'
              }`}
            >
              {profile.isFollowing ? <UserMinus className="h-4 w-4" /> : <UserPlus className="h-4 w-4" />}
              {profile.isFollowing ? '已关注' : '关注'}
            </button>
            <button
              onClick={handleSendMessage}
              className="flex items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-semibold bg-card border border-border/50 text-foreground hover:bg-muted transition-all active:scale-[0.97]"
            >
              <MessageCircle className="h-4 w-4" />
              私信
            </button>
          </div>
        )}
      </div>

      <div className="flex border-b border-border/50 px-4">
        {[
          { key: 'reading' as const, label: '在读', icon: BookOpen },
          { key: 'completed' as const, label: '已读', icon: CheckCircle2 },
          { key: 'comments' as const, label: '书评', icon: MessageSquare },
        ].map(t => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`flex flex-1 items-center justify-center gap-1.5 py-3 text-sm font-medium transition-colors border-b-2 ${
              tab === t.key ? 'border-primary text-primary' : 'border-transparent text-muted-foreground'
            }`}
          >
            <t.icon className="h-4 w-4" />
            {t.label}
          </button>
        ))}
      </div>

      <div className="px-4 pt-3">
        {tab === 'reading' && (
          readingBooks.length === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">暂无在读书籍</p>
          ) : (
            <div className="space-y-2.5">
              {readingBooks.map(book => (
                <button
                  key={book.bookId}
                  onClick={() => navigate(`/book/${book.bookId}`)}
                  className="flex w-full items-center gap-3 rounded-2xl bg-card p-3.5 shadow-sm border border-border/50 active:scale-[0.98] transition-transform"
                >
                  <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} size="sm" className="shrink-0" />
                  <div className="flex-1 min-w-0 text-left">
                    <p className="truncate text-sm font-semibold">{book.title}</p>
                    <p className="text-xs text-muted-foreground">{book.author || '未知作者'}</p>
                    <div className="mt-1.5 h-1.5 rounded-full bg-primary/10">
                      <div className="h-full rounded-full bg-primary transition-all" style={{ width: `${Math.round(book.progress * 100)}%` }} />
                    </div>
                  </div>
                  <span className="text-xs font-bold text-primary">{Math.round(book.progress * 100)}%</span>
                </button>
              ))}
            </div>
          )
        )}

        {tab === 'completed' && (
          completedBooks.length === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">暂无已读书籍</p>
          ) : (
            <div className="grid grid-cols-3 gap-3">
              {completedBooks.map(book => (
                <button
                  key={book.bookId}
                  onClick={() => navigate(`/book/${book.bookId}`)}
                  className="flex flex-col items-center cursor-pointer active:scale-[0.96] transition-transform"
                >
                  <BookCover coverUrl={book.coverUrl} title={book.title} author={book.author} />
                  <p className="mt-1.5 w-full truncate text-xs font-semibold">{book.title}</p>
                </button>
              ))}
            </div>
          )
        )}

        {tab === 'comments' && (
          comments.length === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">暂无书评</p>
          ) : (
            <div className="divide-y divide-border/50">
              {comments.map(c => (
                <div key={c.id} className="py-3">
                  <button
                    onClick={() => navigate(`/book/${c.bookId}`)}
                    className="mb-1.5 flex items-center gap-2.5 rounded-lg bg-primary/5 px-2.5 py-2 hover:bg-primary/10 transition-colors w-full text-left"
                  >
                    <BookCover coverUrl={c.bookCoverUrl ?? null} title={c.bookTitle || '查看书籍'} size="xs" />
                    <span className="text-xs font-medium text-primary truncate">{c.bookTitle || '查看书籍'}</span>
                  </button>
                  <p className="text-sm leading-relaxed line-clamp-3">{c.content}</p>
                  <div className="mt-2 flex items-center gap-4 text-xs text-muted-foreground">
                    <span className="flex items-center gap-1"><Heart className="h-3 w-3" />{c.likeCount}</span>
                    <span>{formatRelativeTime(c.createdAt)}</span>
                  </div>
                </div>
              ))}
            </div>
          ))}
      </div>
    </div>
  )
}