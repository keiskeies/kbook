import { useEffect, useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { ArrowLeft, UserPlus, UserMinus } from 'lucide-react'
import { getFollowings, getFollowers, followUser, unfollowUser } from '@/api/follow'
import type { FollowUserVO } from '@/api/follow'
import { useAuthStore } from '@/store/auth'
import { toast } from 'sonner'

export default function FollowListPage() {
  const { userId, tab = 'followings' } = useParams<{ userId: string; tab: string }>()
  const navigate = useNavigate()
  const goBack = useGoBack()
  const { userInfo } = useAuthStore()
  const [activeTab, setActiveTab] = useState<'followings' | 'followers'>(tab as 'followings' | 'followers')
  const [followings, setFollowings] = useState<FollowUserVO[]>([])
  const [followers, setFollowers] = useState<FollowUserVO[]>([])
  const [loading, setLoading] = useState(true)
  const [followingSet, setFollowingSet] = useState<Set<number>>(new Set())

  const id = Number(userId)

  const loadData = useCallback(async () => {
    setLoading(true)
    try {
      const [followingsRes, followersRes] = await Promise.all([
        getFollowings(id),
        getFollowers(id),
      ])
      const followingsData = (followingsRes as any)?.data || (followingsRes as any) || []
      const followersData = (followersRes as any)?.data || (followersRes as any) || []
      setFollowings(Array.isArray(followingsData) ? followingsData : [])
      setFollowers(Array.isArray(followersData) ? followersData : [])
      // 记录当前用户已关注的人，用于粉丝列表中显示关注状态
      setFollowingSet(new Set(followingsData.map((u: FollowUserVO) => u.userId)))
    } catch { /* ignore */ }
    finally { setLoading(false) }
  }, [id])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handleFollowToggle = async (targetUserId: number, isFollowing: boolean) => {
    try {
      if (isFollowing) {
        await unfollowUser(targetUserId)
        setFollowingSet(prev => {
          const next = new Set(prev)
          next.delete(targetUserId)
          return next
        })
        // 如果在关注列表中，移除
        if (activeTab === 'followings') {
          setFollowings(prev => prev.filter(u => u.userId !== targetUserId))
        }
      } else {
        await followUser(targetUserId)
        setFollowingSet(prev => {
          const next = new Set(prev)
          next.add(targetUserId)
          return next
        })
        // 如果在关注列表，刷新
        if (activeTab === 'followings') {
          loadData()
        }
      }
    } catch (err: any) {
      toast.error(err.message || '操作未完成')
    }
  }

  const isSelf = userInfo?.id === id
  const currentList = activeTab === 'followings' ? followings : followers

  return (
    <div className="fixed inset-0 flex flex-col overflow-hidden bg-background page-enter overscroll-contain">
      {/* 顶部 */}
      <header className="shrink-0 flex items-center gap-3 border-b border-border/50 bg-background/80 px-4 py-3 backdrop-blur-xl z-20">
        <button onClick={() => goBack()} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-base font-bold">{isSelf ? '我的关注' : 'TA的关注'}</h1>
      </header>

      {/* Tab 切换 */}
      <div className="flex shrink-0 border-b border-border/50">
        <button
          onClick={() => setActiveTab('followings')}
          className={`flex-1 py-3 text-sm font-semibold transition-colors border-b-2 ${
            activeTab === 'followings' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground'
          }`}
        >
          关注 {followings.length > 0 ? `(${followings.length})` : ''}
        </button>
        <button
          onClick={() => setActiveTab('followers')}
          className={`flex-1 py-3 text-sm font-semibold transition-colors border-b-2 ${
            activeTab === 'followers' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground'
          }`}
        >
          粉丝 {followers.length > 0 ? `(${followers.length})` : ''}
        </button>
      </div>

      {/* 列表内容 */}
      <div className="flex-1 overflow-y-auto overscroll-contain px-4 py-3">
        {loading ? (
          <div className="flex justify-center py-12">
            <div className="h-6 w-6 animate-spin rounded-full border-3 border-primary border-t-transparent" />
          </div>
        ) : currentList.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
            <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-muted mb-3">
              <UserPlus className="h-6 w-6 text-muted-foreground/50" />
            </div>
            <p className="text-sm">
              {activeTab === 'followings' ? '还没有关注任何人' : '还没有粉丝'}
            </p>
          </div>
        ) : (
          <div className="space-y-1">
            {currentList.map(user => {
              const isFollowed = followingSet.has(user.userId)
              return (
                <div
                  key={user.userId}
                  className="flex items-center gap-3 rounded-2xl bg-card px-3.5 py-3 border border-border/30"
                >
                  {/* 头像 */}
                  <button
                    onClick={() => navigate(`/user/${user.userId}`)}
                    className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary/10 overflow-hidden"
                  >
                    {user.avatar ? (
                      <img src={user.avatar} alt="" className="h-full w-full object-cover" />
                    ) : (
                      <span className="text-base font-bold text-primary">{user.nickname?.[0] || 'U'}</span>
                    )}
                  </button>

                  {/* 昵称 + 简介 */}
                  <button
                    onClick={() => navigate(`/user/${user.userId}`)}
                    className="flex-1 min-w-0 text-left"
                  >
                    <p className="text-sm font-semibold truncate">{user.nickname}</p>
                    {user.bio && (
                      <p className="text-xs text-muted-foreground truncate mt-0.5">{user.bio}</p>
                    )}
                  </button>

                  {/* 关注按钮 - 只对自己以外的人显示 */}
                  {userInfo && user.userId !== userInfo.id && (
                    <button
                      onClick={() => handleFollowToggle(user.userId, isFollowed)}
                      className={`flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-semibold transition-all active:scale-[0.95] ${
                        isFollowed
                          ? 'bg-muted text-muted-foreground border border-border/50'
                          : 'bg-primary text-primary-foreground shadow-sm shadow-primary/20'
                      }`}
                    >
                      {isFollowed ? (
                        <>
                          <UserMinus className="h-3 w-3" />
                          已关注
                        </>
                      ) : (
                        <>
                          <UserPlus className="h-3 w-3" />
                          关注
                        </>
                      )}
                    </button>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
