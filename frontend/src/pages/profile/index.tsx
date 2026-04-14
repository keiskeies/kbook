import { useEffect, useState, useRef } from 'react'
import { Settings, ChevronRight, LogOut, Lock, BookOpen, ShieldCheck, Mail, Library, BookMarked, Bot, UserCircle, Camera, Bell, Users, Palette } from 'lucide-react'
import { useAuthStore } from '@/store/auth'
import { useNavigate } from 'react-router-dom'
import { ROUTES } from '@/constants'
import { toast } from 'sonner'
import { getReadingStats } from '@/api/progress'
import { getBookshelfCount } from '@/api/bookshelf'
import { updateTraits } from '@/api/auth'
import { updateProfile, uploadAvatar } from '@/api/user'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Avatar, AvatarImage, AvatarFallback } from '@/components/ui/avatar'
import type { ReadingStats } from '@/types/book'

const MBTI_OPTIONS = ['INTJ','INTP','ENTJ','ENTP','INFJ','INFP','ENFJ','ENFP','ISTJ','ISFJ','ESTJ','ESFJ','ISTP','ISFP','ESTP','ESFP']

/** 根据 birthday 计算年龄 */
function calcAge(birthday: string | null | undefined): number | null {
  if (!birthday) return null
  const birth = new Date(birthday)
  const today = new Date()
  let age = today.getFullYear() - birth.getFullYear()
  const monthDiff = today.getMonth() - birth.getMonth()
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) {
    age--
  }
  return age >= 0 ? age : null
}

export default function ProfilePage() {
  const { userInfo, updateUserInfo, logout } = useAuthStore()
  const navigate = useNavigate()
  const [stats, setStats] = useState<ReadingStats | null>(null)
  const [shelfCount, setShelfCount] = useState<number>(0)

  // 画像编辑弹窗
  const [showTraitsModal, setShowTraitsModal] = useState(false)
  const [traitBirthday, setTraitBirthday] = useState(userInfo?.birthday ?? '')
  const [traitGender, setTraitGender] = useState(userInfo?.gender ?? '')
  const [traitMarried, setTraitMarried] = useState(userInfo?.married === true ? 'yes' : userInfo?.married === false ? 'no' : '')
  const [traitHasChildren, setTraitHasChildren] = useState(userInfo?.hasChildren === true ? 'yes' : userInfo?.hasChildren === false ? 'no' : '')
  const [traitMbti, setTraitMbti] = useState(userInfo?.mbti ?? '')
  const [savingTraits, setSavingTraits] = useState(false)

  // 用户信息编辑弹窗（昵称+头像）
  const [showProfileModal, setShowProfileModal] = useState(false)
  const [editNickname, setEditNickname] = useState(userInfo?.nickname ?? '')
  const [editBio, setEditBio] = useState(userInfo?.bio ?? '')
  const [savingProfile, setSavingProfile] = useState(false)
  const [uploadingAvatar, setUploadingAvatar] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const isAdmin = userInfo?.role === 'ADMIN'
  const needBindEmail = isAdmin && !userInfo?.emailBound

  const getTraitsSummary = () => {
    const parts: string[] = []
    const age = calcAge(userInfo?.birthday)
    if (age !== null) parts.push(`${age}岁`)
    if (userInfo?.gender === 'MALE') parts.push('男')
    else if (userInfo?.gender === 'FEMALE') parts.push('女')
    if (userInfo?.married != null) parts.push(userInfo.married ? '已婚' : '未婚')
    if (userInfo?.hasChildren != null) parts.push(userInfo.hasChildren ? '有孩子' : '无孩子')
    if (userInfo?.mbti) parts.push(userInfo.mbti)
    return parts.length > 0 ? parts.join(' · ') : '未设置'
  }

  useEffect(() => {
    getReadingStats().then((res) => setStats((res as any) || null)).catch(() => {})
    getBookshelfCount().then((res) => setShelfCount((res as any) || 0)).catch(() => {})
  }, [])

  // 打开用户信息编辑弹窗时同步最新数据
  useEffect(() => {
    if (showProfileModal) {
      setEditNickname(userInfo?.nickname ?? '')
      setEditBio(userInfo?.bio ?? '')
    }
  }, [showProfileModal, userInfo?.nickname, userInfo?.bio])

  // 打开画像编辑弹窗时同步最新数据
  useEffect(() => {
    if (showTraitsModal) {
      setTraitBirthday(userInfo?.birthday ?? '')
      setTraitGender(userInfo?.gender ?? '')
      setTraitMarried(userInfo?.married === true ? 'yes' : userInfo?.married === false ? 'no' : '')
      setTraitHasChildren(userInfo?.hasChildren === true ? 'yes' : userInfo?.hasChildren === false ? 'no' : '')
      setTraitMbti(userInfo?.mbti ?? '')
    }
  }, [showTraitsModal, userInfo])

  const menuItems = [
    { label: '我的书架', icon: Library, path: ROUTES.BOOKSHELF, extra: `${shelfCount} 本` },
    { label: '阅读历史', icon: BookOpen, path: '/profile/history', extra: stats ? `${stats.completedBooks} 本已读完` : '' },
    { label: '我的画像', icon: UserCircle, path: '', extra: getTraitsSummary(), action: () => setShowTraitsModal(true) },
    { label: '通知', icon: Bell, path: '/notifications', extra: '' },
    { label: '我的关注', icon: Users, path: '', extra: `${userInfo?.followingCount || 0} 关注`, action: () => navigate(`/user/${userInfo?.id}/follow/followings`) },
    { label: '主题模式', icon: Palette, path: '', extra: '', custom: true },
    { label: '修改密码', icon: Lock, path: ROUTES.CHANGE_PASSWORD },
  ]

  const adminMenuItems = [
    { label: '图书管理', icon: BookMarked, path: ROUTES.ADMIN_BOOKS, badge: '' },
    { label: 'AI 模型配置', icon: Bot, path: ROUTES.ADMIN_AI_CONFIG, badge: '' },
    { label: '用户审核', icon: ShieldCheck, path: ROUTES.ADMIN_REVIEW, badge: '' },
    ...(needBindEmail
      ? [{ label: '绑定邮箱', icon: Mail, path: ROUTES.ADMIN_BIND_EMAIL, badge: '待绑定' }]
      : []),
  ]

  const handleLogout = () => {
    logout()
    toast.success('已退出登录')
    navigate(ROUTES.LOGIN, { replace: true })
  }

  // 保存用户信息（昵称）
  const handleSaveProfile = async () => {
    if (!editNickname.trim()) {
      toast.error('昵称不能为空')
      return
    }
    setSavingProfile(true)
    try {
      await updateProfile({ nickname: editNickname.trim() })
      updateUserInfo({ nickname: editNickname.trim(), bio: editBio.trim() })
      // 同步 bio 到后端
      const { updateBio } = await import('@/api/userProfile')
      await updateBio(editBio.trim())
      setShowProfileModal(false)
      toast.success('个人信息已更新')
    } catch (err: any) {
      toast.error(err.message || '更新失败')
    } finally {
      setSavingProfile(false)
    }
  }

  // 上传头像
  const handleAvatarUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    // 校验文件类型
    if (!file.type.startsWith('image/')) {
      toast.error('请选择图片文件')
      return
    }
    // 校验大小（2MB）
    if (file.size > 2 * 1024 * 1024) {
      toast.error('图片不能超过2MB')
      return
    }

    setUploadingAvatar(true)
    try {
      const res = await uploadAvatar(file)
      const avatarUrl = (res as any)?.avatar
      if (avatarUrl) {
        updateUserInfo({ avatar: avatarUrl })
        toast.success('头像已更新')
      }
    } catch (err: any) {
      toast.error(err.message || '头像上传失败')
    } finally {
      setUploadingAvatar(false)
      // 重置 file input
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  // 保存画像
  const handleSaveTraits = async () => {
    setSavingTraits(true)
    try {
      const data: any = {
        birthday: traitBirthday || null,
        gender: traitGender || null,
        married: traitMarried ? traitMarried === 'yes' : null,
        hasChildren: traitHasChildren ? traitHasChildren === 'yes' : null,
        mbti: traitMbti || null,
      }
      await updateTraits(data)
      updateUserInfo({
        birthday: data.birthday,
        gender: data.gender,
        married: data.married,
        hasChildren: data.hasChildren,
        mbti: data.mbti,
      })
      setShowTraitsModal(false)
      toast.success('画像已更新')
    } catch (err: any) {
      toast.error(err.message || '更新失败')
    } finally {
      setSavingTraits(false)
    }
  }

  // 头像完整 URL
  const avatarFullUrl = userInfo?.avatar
    ? (userInfo.avatar.startsWith('http') ? userInfo.avatar : userInfo.avatar)
    : null

  return (
    <div className="px-4 pt-safe-top pb-6 page-enter">
      {/* 顶部间距 */}
      <div className="h-4" />
      
      {/* 用户信息卡片 — 渐变背景 */}
      <div className="mb-5 rounded-2xl bg-gradient-to-br from-primary/8 via-card to-card p-4 shadow-sm border border-primary/10">
        <div className="flex items-center gap-4">
          <button
            onClick={() => navigate(`/user/${userInfo?.id}`)}
            className="relative flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-primary/10 ring-1 ring-primary/20 overflow-hidden"
          >
            {avatarFullUrl ? (
              <img src={avatarFullUrl} alt={userInfo?.nickname} className="h-full w-full object-cover" />
            ) : (
              <span className="text-xl font-bold text-primary">
                {userInfo?.nickname?.[0] || 'U'}
              </span>
            )}
            {/* 编辑头像遮罩 */}
            <div className="absolute inset-0 flex items-center justify-center bg-black/0 hover:bg-black/40 transition-colors">
              <Camera className="h-5 w-5 text-white opacity-0 hover:opacity-100 transition-opacity" />
            </div>
          </button>
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <h2 className="text-base font-bold">
                {userInfo?.nickname || '未登录'}
              </h2>
              {isAdmin && (
                <span className="rounded-full bg-primary/10 px-2.5 py-0.5 text-[10px] font-bold text-primary">
                  管理员
                </span>
              )}
            </div>
            <p className="text-sm text-muted-foreground">
              {userInfo?.email || '未设置邮箱'}
            </p>
            {needBindEmail && (
              <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                请先绑定邮箱以启用密码重置
              </p>
            )}
          </div>
          <button
            onClick={() => setShowProfileModal(true)}
            className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors"
          >
            <Settings className="h-4.5 w-4.5 text-muted-foreground" />
          </button>
        </div>

        {/* 阅读统计 */}
        {stats && (
          <div className="mt-4 grid grid-cols-3 gap-3 border-t border-primary/10 pt-4">
            <div className="text-center">
              <p className="text-xl font-bold text-primary">{stats.totalBooks}</p>
              <p className="text-[10px] text-muted-foreground font-medium">在读/读过</p>
            </div>
            <div className="text-center">
              <p className="text-xl font-bold text-emerald-500">{stats.readingBooks}</p>
              <p className="text-[10px] text-muted-foreground font-medium">在读</p>
            </div>
            <div className="text-center">
              <p className="text-xl font-bold text-amber-500">{stats.completedBooks}</p>
              <p className="text-[10px] text-muted-foreground font-medium">已读完</p>
            </div>
          </div>
        )}
      </div>

      {/* 管理员功能 */}
      {isAdmin && (
        <div className="mb-4 rounded-2xl bg-card shadow-sm border border-border/50 overflow-hidden">
          <div className="px-4 pt-3 pb-1">
            <h3 className="text-xs font-bold text-muted-foreground uppercase tracking-wider">管理员功能</h3>
          </div>
          {adminMenuItems.map((item, i) => {
            const Icon = item.icon
            return (
              <button
                key={item.label}
                onClick={() => item.path && navigate(item.path)}
                className={`flex w-full items-center justify-between px-4 py-3 active:bg-muted/50 transition-colors ${
                  i < adminMenuItems.length - 1 ? 'border-b border-border/50' : ''
                }`}
              >
                <div className="flex items-center gap-3">
                  <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/8">
                    <Icon className="h-3.5 w-3.5 text-primary" />
                  </div>
                  <span className="text-sm font-medium">{item.label}</span>
                </div>
                <div className="flex items-center gap-2">
                  {item.badge && (
                    <span className="rounded-full bg-amber-500/10 px-2 py-0.5 text-[10px] font-bold text-amber-600 dark:text-amber-400">
                      {item.badge}
                    </span>
                  )}
                  <ChevronRight className="h-4 w-4 text-muted-foreground" />
                </div>
              </button>
            )
          })}
        </div>
      )}

      {/* 通用功能 */}
      <div className="rounded-2xl bg-card shadow-sm border border-border/50 overflow-hidden">
        {menuItems.map((item, i) => {
          const Icon = item.icon
          return (
            <button
              key={item.label}
              onClick={() => {
                if ((item as any).custom) return
                if ((item as any).action) (item as any).action()
                else if (item.path) navigate(item.path)
              }}
              className={`flex w-full items-center justify-between px-4 py-3.5 active:bg-muted/50 transition-colors ${
                i < menuItems.length - 1 ? 'border-b border-border/50' : ''
              }`}
            >
              <div className="flex items-center gap-3">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-muted">
                  <Icon className="h-3.5 w-3.5 text-muted-foreground" />
                </div>
                <span className="text-sm font-medium">{item.label}</span>
              </div>
              {(item as any).custom ? (
                <ThemeToggle />
              ) : (
                <div className="flex items-center gap-2">
                  {item.extra && (
                    <span className="text-xs text-muted-foreground">{item.extra}</span>
                  )}
                  <ChevronRight className="h-4 w-4 text-muted-foreground" />
                </div>
              )}
            </button>
          )
        })}
      </div>

      {/* 退出登录 */}
      {userInfo && (
        <button
          onClick={handleLogout}
          className="mt-5 flex w-full items-center justify-center gap-2 rounded-2xl bg-card py-3.5 text-sm font-medium text-destructive shadow-sm border border-border/50 active:scale-[0.98] transition-transform"
        >
          <LogOut className="h-4 w-4" />
          退出登录
        </button>
      )}

      {/* 用户信息编辑弹窗（昵称 + 头像） */}
      {showProfileModal && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 backdrop-blur-sm" onClick={() => setShowProfileModal(false)}>
          <div className="w-full max-w-lg rounded-t-3xl bg-card p-5 space-y-4 shadow-2xl" style={{ paddingBottom: 'calc(1.25rem + 5rem)' }} onClick={e => e.stopPropagation()}>
            <div className="flex justify-center pb-1">
              <div className="h-1 w-10 rounded-full bg-muted-foreground/20" />
            </div>
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold">编辑个人信息</h3>
              <button onClick={() => setShowProfileModal(false)} className="text-muted-foreground text-sm font-medium">关闭</button>
            </div>

            {/* 头像上传 */}
            <div className="flex flex-col items-center gap-2">
              <button
                onClick={() => fileInputRef.current?.click()}
                disabled={uploadingAvatar}
                className="relative flex h-20 w-20 items-center justify-center rounded-full bg-primary/10 ring-2 ring-primary/20 overflow-hidden disabled:opacity-50"
              >
                {avatarFullUrl ? (
                  <img src={avatarFullUrl} alt={userInfo?.nickname} className="h-full w-full object-cover" />
                ) : (
                  <span className="text-2xl font-bold text-primary">
                    {userInfo?.nickname?.[0] || 'U'}
                  </span>
                )}
                <div className="absolute inset-0 flex items-center justify-center bg-black/30">
                  {uploadingAvatar ? (
                    <div className="h-5 w-5 animate-spin rounded-full border-2 border-white border-t-transparent" />
                  ) : (
                    <Camera className="h-5 w-5 text-white" />
                  )}
                </div>
              </button>
              <span className="text-xs text-muted-foreground">点击更换头像</span>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={handleAvatarUpload}
              />
            </div>

            {/* 昵称编辑 */}
            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1 block">昵称</label>
              <input
                type="text"
                value={editNickname}
                onChange={(e) => setEditNickname(e.target.value)}
                placeholder="输入昵称"
                maxLength={20}
                className="w-full rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
              />
            </div>

            {/* 简介编辑 */}
            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1 block">个人简介</label>
              <textarea
                value={editBio}
                onChange={(e) => setEditBio(e.target.value)}
                placeholder="介绍一下自己吧..."
                maxLength={200}
                rows={3}
                className="w-full rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow resize-none"
              />
              <p className="mt-1 text-[10px] text-muted-foreground text-right">{editBio.length}/200</p>
            </div>

            <button
              onClick={handleSaveProfile}
              disabled={savingProfile || !editNickname.trim()}
              className="w-full rounded-xl bg-primary py-2.5 text-sm font-semibold text-primary-foreground disabled:opacity-50 shadow-md shadow-primary/20 active:scale-[0.98] transition-transform"
            >
              {savingProfile ? '保存中...' : '保存'}
            </button>
          </div>
        </div>
      )}

      {/* 画像编辑弹窗 */}
      {showTraitsModal && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 backdrop-blur-sm" onClick={() => setShowTraitsModal(false)}>
          <div className="w-full max-w-lg rounded-t-3xl bg-card p-5 space-y-4 shadow-2xl" style={{ paddingBottom: 'calc(1.25rem + 5rem)' }} onClick={e => e.stopPropagation()}>
            <div className="flex justify-center pb-1">
              <div className="h-1 w-10 rounded-full bg-muted-foreground/20" />
            </div>
            <div className="flex items-center justify-between">
              <h3 className="text-base font-bold">编辑我的画像</h3>
              <button onClick={() => setShowTraitsModal(false)} className="text-muted-foreground text-sm font-medium">关闭</button>
            </div>
            <p className="text-xs text-muted-foreground">完善画像可获得更精准的图书推荐</p>

            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1 block">出生日期</label>
              <input
                type="date"
                value={traitBirthday}
                onChange={(e) => setTraitBirthday(e.target.value)}
                max={new Date().toISOString().split('T')[0]}
                className="w-full rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
              />
              {traitBirthday && (
                <p className="mt-1 text-xs text-muted-foreground">
                  当前年龄：{calcAge(traitBirthday)}岁
                </p>
              )}
            </div>

            <select
              value={traitGender}
              onChange={(e) => setTraitGender(e.target.value)}
              className="w-full rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">选择性别</option>
              <option value="MALE">男</option>
              <option value="FEMALE">女</option>
              <option value="OTHER">其他</option>
            </select>

            <select
              value={traitMarried}
              onChange={(e) => setTraitMarried(e.target.value)}
              className="w-full rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">婚姻状况</option>
              <option value="yes">已婚</option>
              <option value="no">未婚</option>
            </select>

            <select
              value={traitHasChildren}
              onChange={(e) => setTraitHasChildren(e.target.value)}
              className="w-full rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">是否有孩子</option>
              <option value="yes">有孩子</option>
              <option value="no">无孩子</option>
            </select>

            <select
              value={traitMbti}
              onChange={(e) => setTraitMbti(e.target.value)}
              className="w-full rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">MBTI 人格</option>
              {MBTI_OPTIONS.map(m => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>

            <button
              onClick={handleSaveTraits}
              disabled={savingTraits}
              className="w-full rounded-xl bg-primary py-2.5 text-sm font-semibold text-primary-foreground disabled:opacity-50 shadow-md shadow-primary/20 active:scale-[0.98] transition-transform"
            >
              {savingTraits ? '保存中...' : '保存'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
