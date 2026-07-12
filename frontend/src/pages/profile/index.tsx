import { useEffect, useState, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Settings, ChevronRight, LogOut, Sparkles, Camera, Book, Users, Bot, Volume2, Mail, BarChart3 } from 'lucide-react'
import { useAuthStore } from '@/store/auth'
import { useUiStore } from '@/store/ui'
import { useNavigate } from 'react-router-dom'
import { ROUTES } from '@/constants'
import { toast } from 'sonner'
import { getHomeStats } from '@/api/home'
import type { ReadingStatsVO } from '@/api/home'
import { getTrashCount } from '@/api/bookTrash'
import FooterVersion from '@/components/common/FooterVersion'
import AvatarCropModal from '@/components/common/AvatarCropModal'
import { useIsMobile } from '@/hooks/use-mobile'
import { Card } from '@/components/ui/card'

import ProfileEditSheet from './ProfileEditSheet'
import ProfileTraitsSheet from './ProfileTraitsSheet'
import ProfilePreferenceSheet from './ProfilePreferenceSheet'
import ProfileStylePickerSheet from './ProfileStylePickerSheet'
import ProfileReadingGroup from './ProfileReadingGroup'
import ProfileAccountGroup from './ProfileAccountGroup'
import ProfileAppGroup from './ProfileAppGroup'

export default function ProfilePage() {
  const { userInfo, isAuthenticated, updateUserInfo, fetchUserInfo, logout } = useAuthStore()
  const setTabBarVisible = useUiStore((s) => s.setTabBarVisible)
  const navigate = useNavigate()

  useEffect(() => {
    fetchUserInfo()
  }, [fetchUserInfo])

  const { data: stats } = useQuery<ReadingStatsVO>({
    queryKey: ['profile', 'stats'],
    queryFn: getHomeStats,
    enabled: isAuthenticated,
  })
  const { data: trashCount = 0 } = useQuery<number>({
    queryKey: ['profile', 'trashCount'],
    queryFn: getTrashCount,
    enabled: isAuthenticated,
  })

  const [showTraitsModal, setShowTraitsModal] = useState(false)
  const [showProfileModal, setShowProfileModal] = useState(false)
  const [showStylePicker, setShowStylePicker] = useState(false)
  const [showPreferenceModal, setShowPreferenceModal] = useState(false)
  const [cropModalOpen, setCropModalOpen] = useState(false)
  const [avatarFile, setAvatarFile] = useState<File | null>(null)
  const [croppedAvatarUrl, setCroppedAvatarUrl] = useState<string | null>(null)
  const [croppedAvatarBlob, setCroppedAvatarBlob] = useState<Blob | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const isAdmin = userInfo?.role === 'ADMIN'
  const needBindEmail = isAdmin && !userInfo?.emailBound
  const isMobile = useIsMobile()
  const sheetSide = isMobile ? 'bottom' : 'right'

  const hasProfileTraits = !!(
    userInfo?.mbti ||
    userInfo?.occupation ||
    userInfo?.birthday ||
    userInfo?.married != null
  )

  useEffect(() => {
    const anyModalOpen = showProfileModal || showTraitsModal || showStylePicker || showPreferenceModal
    setTabBarVisible(!anyModalOpen)
    return () => setTabBarVisible(true)
  }, [showProfileModal, showTraitsModal, showStylePicker, showPreferenceModal, setTabBarVisible])

  useEffect(() => {
    if (showProfileModal) {
      setCroppedAvatarUrl(null)
      setCroppedAvatarBlob(null)
    }
  }, [showProfileModal])

  const handleLogout = () => {
    logout()
    toast.success('已退出登录')
    navigate(ROUTES.LOGIN, { replace: true })
  }

  const handleAvatarSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    if (!file.type.startsWith('image/')) {
      toast.error('选择一张图片吧')
      return
    }
    setAvatarFile(file)
    setCropModalOpen(true)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const handleCropComplete = (croppedBlob: Blob) => {
    if (croppedAvatarUrl) {
      URL.revokeObjectURL(croppedAvatarUrl)
    }
    setCroppedAvatarBlob(croppedBlob)
    setCroppedAvatarUrl(URL.createObjectURL(croppedBlob))
    setCropModalOpen(false)
    setAvatarFile(null)
  }

  const adminMenuItems = [
    { label: '数据看板', icon: BarChart3, path: ROUTES.ADMIN_DASHBOARD },
    { label: '图书管理', icon: Book, path: ROUTES.ADMIN_BOOKS },
    { label: '用户审核', icon: Users, path: ROUTES.ADMIN_REVIEW },
    { label: 'AI 配置', icon: Bot, path: ROUTES.ADMIN_AI_CONFIG },
    { label: 'TTS 配置', icon: Volume2, path: ROUTES.ADMIN_TTS_CONFIG },
    ...(needBindEmail
      ? [{ label: '绑定邮箱', icon: Mail, path: ROUTES.ADMIN_BIND_EMAIL }]
      : []),
  ]

  const avatarFullUrl = userInfo?.avatar
    ? (userInfo.avatar.startsWith('http') ? userInfo.avatar : userInfo.avatar)
    : null

  return (
    <div className="px-4 md:px-6 lg:px-8 pt-safe-top md:pb-6 page-enter">
      <div className="lg:grid lg:grid-cols-[320px_1fr] xl:grid-cols-[360px_1fr] lg:gap-6">
        {/* 左栏：用户卡片 + 管理员功能 */}
        <div>
          <div className="h-4" />

          {/* 完善画像 CTA */}
          {!hasProfileTraits && (
            <button
              onClick={() => setShowTraitsModal(true)}
              className="mb-4 w-full rounded-2xl bg-gradient-to-r from-primary/10 via-primary/5 to-transparent p-4 shadow-sm border border-primary/20 active:scale-[0.98] transition-transform"
            >
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary/15">
                  <Sparkles className="h-5 w-5 text-primary" />
                </div>
                <div className="flex-1 text-left">
                  <p className="text-sm font-semibold text-foreground">完善你的画像</p>
                  <p className="text-xs text-muted-foreground mt-0.5">填写 MBTI、职业等信息，获得更精准的图书推荐</p>
                </div>
                <ChevronRight className="h-5 w-5 text-primary/60 shrink-0" />
              </div>
            </button>
          )}

          {/* 用户卡片 */}
          <Card padding="md" className="md:p-6">
            <div className="flex items-center gap-4">
              <button
                onClick={() => fileInputRef.current?.click()}
                className="relative flex h-14 w-14 md:h-18 md:w-18 shrink-0 items-center justify-center rounded-full bg-primary/10 ring-2 ring-primary/20 overflow-hidden"
              >
                {avatarFullUrl ? (
                  <img src={avatarFullUrl} alt={userInfo?.nickname} className="h-full w-full object-cover" />
                ) : (
                  <span className="text-xl md:text-2xl font-bold text-primary">
                    {userInfo?.nickname?.[0] || 'U'}
                  </span>
                )}
                <div className="absolute inset-0 flex items-center justify-center bg-black/0 hover:bg-black/40 transition-colors">
                  <Camera className="h-5 w-5 text-white opacity-0 hover:opacity-100 transition-opacity" />
                </div>
              </button>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={handleAvatarSelect}
              />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <h2 className="text-base md:text-lg font-bold truncate">
                    {userInfo?.nickname || '未登录'}
                  </h2>
                  {isAdmin && (
                    <span className="rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-bold text-primary shrink-0">
                      管理员
                    </span>
                  )}
                </div>
                <p className="text-sm text-muted-foreground truncate">
                  {userInfo?.email || '未设置邮箱'}
                </p>
              </div>
              <button
                onClick={() => setShowProfileModal(true)}
                className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted transition-colors"
              >
                <Settings className="h-4.5 w-4.5 text-muted-foreground" />
              </button>
            </div>
          </Card>

          {/* 管理员功能 */}
          {isAdmin && (
            <Card padding="none" className="mt-4 overflow-hidden">
              <div className="px-4 pt-3 pb-1">
                <h3 className="text-xs font-bold text-muted-foreground uppercase tracking-wider">管理员功能</h3>
              </div>
              <div>
                {adminMenuItems.map((item, i) => {
                  const Icon = item.icon
                  return (
                    <button
                      key={item.label}
                      onClick={() => navigate(item.path)}
                      className={`flex w-full items-center justify-between px-4 py-3 active:bg-muted/50 md:hover:bg-muted/50 transition-colors ${
                        i < adminMenuItems.length - 1 ? 'border-b border-border/50' : ''
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-muted">
                          <Icon className="h-3.5 w-3.5 text-muted-foreground" />
                        </div>
                        <span className="text-sm font-medium">{item.label}</span>
                      </div>
                      <ChevronRight className="h-4 w-4 text-muted-foreground" />
                    </button>
                  )
                })}
              </div>
            </Card>
          )}
        </div>

        {/* 右栏：菜单分组 + 退出登录 */}
        <div>
          <div className="h-4" />

          {/* Group 1: 阅读记录 / 阅读偏好 / 对话风格 / 垃圾桶 */}
          <ProfileReadingGroup
            totalBooks={stats?.totalBooks ?? 0}
            trashCount={trashCount}
            bookChatStyle={userInfo?.bookChatStyle || 'DEEP'}
            onNavigate={navigate}
            onOpenPreference={() => setShowPreferenceModal(true)}
            onOpenStylePicker={() => setShowStylePicker(true)}
          />

          <div className="mt-4">
            {/* Group 2: 编辑画像 / 修改密码 */}
            <ProfileAccountGroup
              onNavigate={navigate}
              onOpenTraits={() => setShowTraitsModal(true)}
            />
          </div>

          <div className="mt-4">
            {/* Group 3: 主题模式 / 关于 / 隐私政策 */}
            <ProfileAppGroup onNavigate={navigate} />
          </div>

          {/* 退出登录 */}
          {userInfo && (
            <button
              onClick={handleLogout}
              className="mt-4 flex w-full items-center justify-center gap-2 rounded-2xl bg-card py-3.5 text-sm font-medium text-destructive shadow-sm border border-border/50 active:scale-[0.98] md:hover:bg-destructive/5 transition-all"
            >
              <LogOut className="h-4 w-4" />
              退出登录
            </button>
          )}
        </div>
      </div>

      {/* FooterVersion */}
      <div className="mt-8 flex justify-center">
        <FooterVersion />
      </div>

      {/* Sheet Components */}
      <ProfileEditSheet
        open={showProfileModal}
        onOpenChange={setShowProfileModal}
        userInfo={userInfo}
        updateUserInfo={updateUserInfo}
        isMobile={isMobile}
        sheetSide={sheetSide}
        fileInputRef={fileInputRef}
        croppedAvatarUrl={croppedAvatarUrl}
        croppedAvatarBlob={croppedAvatarBlob}
      />

      <ProfileTraitsSheet
        open={showTraitsModal}
        onOpenChange={setShowTraitsModal}
        userInfo={userInfo}
        updateUserInfo={updateUserInfo}
        isMobile={isMobile}
        sheetSide={sheetSide}
      />

      <ProfilePreferenceSheet
        open={showPreferenceModal}
        onOpenChange={setShowPreferenceModal}
        isMobile={isMobile}
        sheetSide={sheetSide}
      />

      <ProfileStylePickerSheet
        open={showStylePicker}
        onOpenChange={setShowStylePicker}
        userInfo={userInfo}
        updateUserInfo={updateUserInfo}
        isMobile={isMobile}
        sheetSide={sheetSide}
      />

      <AvatarCropModal
        open={cropModalOpen}
        onOpenChange={setCropModalOpen}
        imageFile={avatarFile}
        onCropComplete={handleCropComplete}
      />
    </div>
  )
}
