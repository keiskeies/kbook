import { useEffect, useState, useRef } from 'react'
import { Settings, ChevronRight, LogOut, Lock, BookOpen, UserCircle, Camera, Palette, Check, Sparkles, Info, Shield, Book, Users, Bot, Volume2, Mail } from 'lucide-react'
import { useAuthStore } from '@/store/auth'
import { useUiStore } from '@/store/ui'
import { useNavigate } from 'react-router-dom'
import { ROUTES } from '@/constants'
import { toast } from 'sonner'
import { getHomeStats } from '@/api/home'
import type { ReadingStatsVO } from '@/api/home'
import { updateTraits, updateMood } from '@/api/auth'
import { updateProfile, uploadAvatar } from '@/api/user'
import { ThemeToggle } from '@/components/ThemeToggle'
import FooterVersion from '@/components/common/FooterVersion'
import AvatarCropModal from '@/components/common/AvatarCropModal'
import { Sheet, SheetContent, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import MobileSheetHeader from '@/components/common/MobileSheetHeader'
import { useIsMobile } from '@/hooks/use-mobile'

const MBTI_OPTIONS = ['INTJ','INTP','ENTJ','ENTP','INFJ','INFP','ENFJ','ENFP','ISTJ','ISFJ','ESTJ','ESFJ','ISTP','ISFP','ESTP','ESFP']

const OCCUPATION_OPTIONS = [
  { value: 'STUDENT', label: '学生' },
  { value: 'TECH', label: '技术/IT' },
  { value: 'FINANCE', label: '金融/商业' },
  { value: 'EDUCATION', label: '教育/科研' },
  { value: 'MEDICAL', label: '医疗/健康' },
  { value: 'ARTS', label: '文艺/传媒' },
  { value: 'MANAGEMENT', label: '管理/行政' },
  { value: 'FREELANCE', label: '自由职业' },
  { value: 'RETIRED', label: '退休' },
  { value: 'OTHER', label: '其他' },
]

const EDUCATION_OPTIONS = [
  { value: 'HIGH_SCHOOL', label: '高中及以下' },
  { value: 'COLLEGE', label: '大专' },
  { value: 'BACHELOR', label: '本科' },
  { value: 'MASTER', label: '硕士' },
  { value: 'DOCTORATE', label: '博士' },
  { value: 'OTHER', label: '其他' },
]

const ENTREPRENEURSHIP_OPTIONS = [
  { value: 'ENTREPRENEUR_OR_WANT', label: '正在创业/想创业' },
  { value: 'NOT_INTERESTED', label: '暂不考虑' },
]

const ANNUAL_INCOME_OPTIONS = [
  { value: 'UNDER_50K', label: '5万以内' },
  { value: '50K_150K', label: '5~15万' },
  { value: '150K_300K', label: '15~30万' },
  { value: '300K_500K', label: '30~50万' },
  { value: '500K_1M', label: '50~100万' },
  { value: 'OVER_1M', label: '100万+' },
  { value: 'PREFER_NOT_TO_SAY', label: '不方便说' },
]

const CHILDREN_AGE_RANGE_OPTIONS = [
  { value: 'children_0_2', label: '0-2岁' },
  { value: 'children_3_6', label: '3-6岁' },
  { value: 'children_7_12', label: '7-12岁' },
  { value: 'children_13_17', label: '13-17岁' },
  { value: 'children_18_plus', label: '18岁以上' },
]

const MOOD_OPTIONS = [
  { value: 'HAPPY', label: '开心', emoji: '😊' },
  { value: 'CALM', label: '平静', emoji: '😌' },
  { value: 'ANXIOUS', label: '焦虑', emoji: '😰' },
  { value: 'SAD', label: '低落', emoji: '😢' },
  { value: 'MOTIVATED', label: '充满动力', emoji: '🔥' },
  { value: 'TIRED', label: '疲惫', emoji: '😴' },
  { value: 'CURIOUS', label: '好奇', emoji: '🤔' },
]

const BOOK_CHAT_STYLES = [
  { value: 'CASUAL', label: '随和聊天', desc: '口语化，像朋友在聊书' },
  { value: 'DEEP', label: '深度分析', desc: '结构化解读，认真钻研' },
  { value: 'CONCISE', label: '简洁直接', desc: '要言不烦，直击重点' },
  { value: 'WITTY', label: '幽默风趣', desc: '轻松调侃，边读边乐' },
]

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
  const { userInfo, updateUserInfo, fetchUserInfo, logout } = useAuthStore()
  const currentStyle = userInfo?.bookChatStyle || 'DEEP'
  const setTabBarVisible = useUiStore((s) => s.setTabBarVisible)
  const navigate = useNavigate()
  const [stats, setStats] = useState<ReadingStatsVO | null>(null)

  useEffect(() => {
    fetchUserInfo()
  }, [fetchUserInfo])

  const [showTraitsModal, setShowTraitsModal] = useState(false)
  const [traitBirthday, setTraitBirthday] = useState(userInfo?.birthday ?? '')
  const [traitGender, setTraitGender] = useState(userInfo?.gender ?? '')
  const [traitMarried, setTraitMarried] = useState(userInfo?.married === true ? 'yes' : userInfo?.married === false ? 'no' : '')
  const [traitHasChildren, setTraitHasChildren] = useState(userInfo?.hasChildren === true ? 'yes' : userInfo?.hasChildren === false ? 'no' : '')
  const [traitChildrenAgeRanges, setTraitChildrenAgeRanges] = useState<string[]>(() => {
    const ranges = userInfo?.childrenAgeRanges
    if (!ranges) return []
    return ranges.split(',').filter(Boolean).map((v: string) => {
      if (v.startsWith('children_')) return v
      return 'children_' + v
    })
  })
  const [traitMbti, setTraitMbti] = useState(userInfo?.mbti ?? '')
  const [traitOccupations, setTraitOccupations] = useState<string[]>(() => {
    const occ = userInfo?.occupation
    return occ ? occ.split(',').filter(Boolean) : []
  })
  const [traitEducation, setTraitEducation] = useState(userInfo?.aspirationEducation ?? '')
  const [traitEntrepreneurship, setTraitEntrepreneurship] = useState(userInfo?.entrepreneurship ?? '')
  const [traitAnnualIncome, setTraitAnnualIncome] = useState(userInfo?.aspirationIncome ?? '')
  const [savingTraits, setSavingTraits] = useState(false)

  const [showProfileModal, setShowProfileModal] = useState(false)
  const [showStylePicker, setShowStylePicker] = useState(false)
  const [editNickname, setEditNickname] = useState(userInfo?.nickname ?? '')
  const [editBio, setEditBio] = useState(userInfo?.bio ?? '')
  const [editMood, setEditMood] = useState(userInfo?.mood ?? '')
  const [savingProfile, setSavingProfile] = useState(false)
  const [cropModalOpen, setCropModalOpen] = useState(false)
  const [avatarFile, setAvatarFile] = useState<File | null>(null)
  const [croppedAvatarUrl, setCroppedAvatarUrl] = useState<string | null>(null)
  const [croppedAvatarBlob, setCroppedAvatarBlob] = useState<Blob | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const isAdmin = userInfo?.role === 'ADMIN'
  const needBindEmail = isAdmin && !userInfo?.emailBound
  const isMobile = useIsMobile()
  const sheetSide = isMobile ? 'bottom' : 'right'

  // Check if user has filled in profile traits
  const hasProfileTraits = !!(
    userInfo?.mbti ||
    userInfo?.occupation ||
    userInfo?.birthday ||
    userInfo?.married != null
  )

  useEffect(() => {
    getHomeStats().then((res) => {
      const data = (res as any)?.data || (res as any)
      if (data) setStats(data as ReadingStatsVO)
    }).catch(() => {})
  }, [])

  useEffect(() => {
    const anyModalOpen = showProfileModal || showTraitsModal || showStylePicker
    setTabBarVisible(!anyModalOpen)
    return () => setTabBarVisible(true)
  }, [showProfileModal, showTraitsModal, showStylePicker, setTabBarVisible])

  useEffect(() => {
    if (showProfileModal) {
      setEditNickname(userInfo?.nickname ?? '')
      setEditBio(userInfo?.bio ?? '')
      setEditMood(userInfo?.mood ?? '')
      setCroppedAvatarUrl(null)
      setCroppedAvatarBlob(null)
    }
  }, [showProfileModal, userInfo?.nickname, userInfo?.bio, userInfo?.mood])

  useEffect(() => {
    if (showTraitsModal) {
      setTraitBirthday(userInfo?.birthday ?? '')
      setTraitGender(userInfo?.gender ?? '')
      setTraitMarried(userInfo?.married === true ? 'yes' : userInfo?.married === false ? 'no' : '')
      setTraitHasChildren(userInfo?.hasChildren === true ? 'yes' : userInfo?.hasChildren === false ? 'no' : '')
      const ranges = userInfo?.childrenAgeRanges
      setTraitChildrenAgeRanges(ranges ? ranges.split(',').filter(Boolean).map((v: string) => v.startsWith('children_') ? v : 'children_' + v) : [])
      setTraitMbti(userInfo?.mbti ?? '')
      const occ = userInfo?.occupation
      setTraitOccupations(occ ? occ.split(',').filter(Boolean) : [])
      setTraitEducation(userInfo?.aspirationEducation ?? '')
      setTraitEntrepreneurship(userInfo?.entrepreneurship ?? '')
      setTraitAnnualIncome(userInfo?.aspirationIncome ?? '')
    }
  }, [showTraitsModal, userInfo])

  const handleLogout = () => {
    logout()
    toast.success('已退出登录')
    navigate(ROUTES.LOGIN, { replace: true })
  }

  const handleSaveProfile = async () => {
    if (!editNickname.trim()) {
      toast.error('给自己取个昵称吧')
      return
    }
    setSavingProfile(true)
    try {
      if (croppedAvatarBlob) {
        const croppedFile = new File([croppedAvatarBlob], 'avatar.jpg', { type: 'image/jpeg' })
        const res = await uploadAvatar(croppedFile)
        const avatarUrl = (res as any)?.avatar
        if (avatarUrl) {
          updateUserInfo({ avatar: avatarUrl })
        }
      }

      await updateProfile({ nickname: editNickname.trim() })
      updateUserInfo({ nickname: editNickname.trim(), bio: editBio.trim() })
      const { updateBio } = await import('@/api/userProfile')
      await updateBio(editBio.trim())
      if (editMood !== (userInfo?.mood ?? '')) {
        await updateMood(editMood)
        updateUserInfo({ mood: editMood || null })
      }
      setShowProfileModal(false)
      toast.success('个人信息已更新')
    } catch (err: any) {
      toast.error(err.message || '更新未完成，稍后再试试')
    } finally {
      setSavingProfile(false)
    }
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

  const handleSaveTraits = async () => {
    setSavingTraits(true)
    try {
      const data: any = {
        birthday: traitBirthday || null,
        gender: traitGender || null,
        married: traitMarried ? traitMarried === 'yes' : null,
        hasChildren: traitHasChildren ? traitHasChildren === 'yes' : null,
        childrenAgeRanges: traitHasChildren === 'yes' && traitChildrenAgeRanges.length > 0
          ? traitChildrenAgeRanges.join(',')
          : (traitHasChildren === 'no' ? 'no_children' : null),
        mbti: traitMbti || null,
        occupation: traitOccupations.length > 0 ? traitOccupations.join(',') : null,
        aspirationEducation: traitEducation || null,
        entrepreneurship: traitEntrepreneurship || null,
        aspirationIncome: traitAnnualIncome || null,
      }
      await updateTraits(data)
      updateUserInfo({
        birthday: data.birthday,
        gender: data.gender,
        married: data.married,
        hasChildren: data.hasChildren,
        childrenAgeRanges: data.childrenAgeRanges,
        mbti: data.mbti,
        occupation: data.occupation,
        aspirationEducation: data.aspirationEducation,
        entrepreneurship: data.entrepreneurship,
        aspirationIncome: data.aspirationIncome,
      })
      setShowTraitsModal(false)
      toast.success('画像已更新')
    } catch (err: any) {
      toast.error(err.message || '更新未完成，稍后再试试')
    } finally {
      setSavingTraits(false)
    }
  }

  const avatarFullUrl = userInfo?.avatar
    ? (userInfo.avatar.startsWith('http') ? userInfo.avatar : userInfo.avatar)
    : null

  const menuItems = [
    { label: '阅读记录', icon: BookOpen, path: ROUTES.READING_LIST, extra: `${stats?.totalBooks ?? 0}本` },
    { label: '编辑画像', icon: UserCircle, path: '', action: () => setShowTraitsModal(true) },
    { label: '对话风格', icon: Sparkles, path: '', extra: `${BOOK_CHAT_STYLES.find(s => s.value === (userInfo?.bookChatStyle || 'DEEP'))?.label || '深度'}`, action: () => setShowStylePicker(true) },
    { label: '修改密码', icon: Lock, path: ROUTES.CHANGE_PASSWORD },
    { label: '关于', icon: Info, path: ROUTES.TERMS },
    { label: '隐私政策', icon: Shield, path: ROUTES.PRIVACY },
  ]

  const adminMenuItems = [
    { label: '图书管理', icon: Book, path: ROUTES.ADMIN_BOOKS },
    { label: '用户审核', icon: Users, path: ROUTES.ADMIN_REVIEW },
    { label: 'AI 配置', icon: Bot, path: ROUTES.ADMIN_AI_CONFIG },
    { label: 'TTS 配置', icon: Volume2, path: ROUTES.ADMIN_TTS_CONFIG },
    ...(needBindEmail
      ? [{ label: '绑定邮箱', icon: Mail, path: ROUTES.ADMIN_BIND_EMAIL }]
      : []),
  ]

  return (
    <div className="px-4 md:px-6 lg:px-8 pt-safe-top pb-20 md:pb-6 page-enter">
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
        <div className="rounded-2xl bg-card p-4 md:p-6 shadow-sm border border-border/50">
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
        </div>

        {/* 管理员功能 */}
        {isAdmin && (
          <div className="mt-4 rounded-2xl bg-card shadow-sm border border-border/50 overflow-hidden">
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
          </div>
        )}
        </div>{/* 左栏结束 */}

        {/* 右栏：菜单分组 + 退出登录 */}
        <div>
        <div className="h-4" />

        {/* Menu Items */}
        <div className="rounded-2xl bg-card shadow-sm border border-border/50 overflow-hidden">
          {menuItems.map((item, i) => {
            const Icon = item.icon
            return (
              <button
                key={item.label}
                onClick={() => {
                  if (item.action) item.action()
                  else if (item.path) navigate(item.path)
                }}
                className={`flex w-full items-center justify-between px-4 py-3.5 active:bg-muted/50 md:hover:bg-muted/50 transition-colors ${
                  i < menuItems.length - 1 ? 'border-b border-border/50' : ''
                }`}
              >
                <div className="flex items-center gap-3">
                  <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-muted">
                    <Icon className="h-3.5 w-3.5 text-muted-foreground" />
                  </div>
                  <span className="text-sm font-medium">{item.label}</span>
                </div>
                <div className="flex items-center gap-2">
                  {'extra' in item && item.extra && (
                    <span className="text-xs text-muted-foreground">{item.extra}</span>
                  )}
                  <ChevronRight className="h-4 w-4 text-muted-foreground" />
                </div>
              </button>
            )
          })}

          {/* Theme toggle row */}
          <div className="flex items-center justify-between px-4 py-3.5 border-b border-border/50">
            <div className="flex items-center gap-3">
              <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-muted">
                <Palette className="h-3.5 w-3.5 text-muted-foreground" />
              </div>
              <span className="text-sm font-medium">主题模式</span>
            </div>
            <ThemeToggle />
          </div>
        </div>

        {/* Logout */}
        {userInfo && (
          <button
            onClick={handleLogout}
            className="mt-4 flex w-full items-center justify-center gap-2 rounded-2xl bg-card py-3.5 text-sm font-medium text-destructive shadow-sm border border-border/50 active:scale-[0.98] md:hover:bg-destructive/5 transition-all"
          >
            <LogOut className="h-4 w-4" />
            退出登录
          </button>
        )}
        </div>{/* 右栏结束 */}
      </div>{/* grid结束 */}

      {/* FooterVersion - 页面底部居中 */}
      <div className="mt-8 flex justify-center">
        <FooterVersion />
      </div>

      {/* Edit Profile Sheet */}
      <Sheet open={showProfileModal} onOpenChange={setShowProfileModal}>
        <SheetContent side={sheetSide} className={`flex flex-col gap-0 p-5 [&>button]:hidden ${isMobile ? 'max-h-[85vh] rounded-t-2xl' : 'h-full sm:max-w-xl rounded-l-2xl'}`}>
        <SheetTitle className="sr-only">编辑个人信息</SheetTitle>
        <SheetDescription className="sr-only">编辑你的昵称、头像、简介和心情</SheetDescription>
        <MobileSheetHeader
          icon={<UserCircle className="h-5 w-5 text-primary" />}
          title="编辑个人信息"
          onClose={() => setShowProfileModal(false)}
        />

          <div className="flex-1 overflow-y-auto overscroll-y-contain space-y-4 -mx-5 px-5">

          <div className="flex flex-col items-center gap-2">
            <button
              onClick={() => fileInputRef.current?.click()}
              className="relative flex h-20 w-20 items-center justify-center rounded-full bg-primary/10 ring-2 ring-primary/20 overflow-hidden"
            >
              {croppedAvatarUrl ? (
                <img src={croppedAvatarUrl} alt="预览" className="h-full w-full object-cover" />
              ) : avatarFullUrl ? (
                <img src={avatarFullUrl} alt={userInfo?.nickname} className="h-full w-full object-cover" />
              ) : (
                <span className="text-2xl font-bold text-primary">
                  {userInfo?.nickname?.[0] || 'U'}
                </span>
              )}
              <div className="absolute inset-0 flex items-center justify-center bg-black/0 hover:bg-black/40 transition-colors">
                <Camera className="h-5 w-5 text-white opacity-0 hover:opacity-100 transition-opacity" />
              </div>
            </button>
            <span className="text-xs text-muted-foreground">{croppedAvatarUrl ? '已裁剪，点击可重新选择' : '点击更换头像'}</span>
          </div>

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
            <p className="mt-1 text-xs text-muted-foreground text-right">{editBio.length}/200</p>
          </div>

          <div>
            <label className="text-xs font-medium text-muted-foreground mb-1.5 block">当前心情</label>
            <div className="flex flex-wrap gap-2">
              {MOOD_OPTIONS.map(m => {
                const isActive = editMood === m.value
                return (
                  <button
                    key={m.value}
                    type="button"
                    onClick={() => setEditMood(isActive ? '' : m.value)}
                    className={`inline-flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors border ${
                      isActive
                        ? 'bg-primary text-primary-foreground border-primary shadow-sm'
                        : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                    }`}
                  >
                    <span>{m.emoji}</span>
                    <span>{m.label}</span>
                  </button>
                )
              })}
            </div>
          </div>

          </div>

          <div className="shrink-0 pt-4">
            <button
              onClick={handleSaveProfile}
              disabled={savingProfile || !editNickname.trim()}
              className="w-full rounded-xl bg-primary py-2.5 text-sm font-semibold text-primary-foreground disabled:opacity-50 shadow-md shadow-primary/20 active:scale-[0.98] transition-transform"
            >
              {savingProfile ? '保存中...' : (croppedAvatarBlob ? '保存（含新头像）' : '保存')}
            </button>
          </div>
        </SheetContent>
      </Sheet>

      {/* Edit Traits Sheet */}
      <Sheet open={showTraitsModal} onOpenChange={setShowTraitsModal}>
        <SheetContent side={sheetSide} className={`flex flex-col gap-0 p-5 [&>button]:hidden ${isMobile ? 'max-h-[85vh] rounded-t-2xl' : 'h-full sm:max-w-xl rounded-l-2xl'}`}>
        <SheetTitle className="sr-only">编辑我的画像</SheetTitle>
        <SheetDescription className="sr-only">完善画像可获得更精准的图书推荐</SheetDescription>
        <MobileSheetHeader
          icon={<UserCircle className="h-5 w-5 text-primary" />}
          title="编辑我的画像"
          description="完善画像可获得更精准的图书推荐"
          onClose={() => setShowTraitsModal(false)}
        />

          <div className="flex-1 overflow-y-auto overscroll-y-contain space-y-4 -mx-5 px-5">

          <div>
            <label className="text-xs font-medium text-muted-foreground mb-1 block">出生日期</label>
            <div className="grid grid-cols-3 gap-2">
              <select
                value={traitBirthday ? traitBirthday.split('-')[0] : ''}
                onChange={(e) => {
                  const y = e.target.value
                  const m = traitBirthday ? traitBirthday.split('-')[1] : ''
                  const d = traitBirthday ? traitBirthday.split('-')[2] : ''
                  setTraitBirthday(y && m && d ? `${y}-${m}-${d}` : y ? `${y}-01-01` : '')
                }}
                className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-2.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance-none"
              >
                <option value="">年</option>
                {Array.from({ length: 100 }, (_, i) => new Date().getFullYear() - i).map(y => (
                  <option key={y} value={String(y)}>{y}</option>
                ))}
              </select>
              <select
                value={traitBirthday ? traitBirthday.split('-')[1] : ''}
                onChange={(e) => {
                  const y = traitBirthday ? traitBirthday.split('-')[0] : ''
                  const m = e.target.value
                  const d = traitBirthday ? traitBirthday.split('-')[2] : ''
                  setTraitBirthday(y && m && d ? `${y}-${m}-${d}` : m ? `2000-${m}-01` : '')
                }}
                className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-2.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance:none"
              >
                <option value="">月</option>
                {Array.from({ length: 12 }, (_, i) => i + 1).map(m => (
                  <option key={m} value={String(m).padStart(2, '0')}>{m}月</option>
                ))}
              </select>
              <select
                value={traitBirthday ? traitBirthday.split('-')[2] : ''}
                onChange={(e) => {
                  const y = traitBirthday ? traitBirthday.split('-')[0] : ''
                  const m = traitBirthday ? traitBirthday.split('-')[1] : ''
                  const d = e.target.value
                  setTraitBirthday(y && m && d ? `${y}-${m}-${d}` : d ? `2000-01-${d}` : '')
                }}
                className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-2.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance:none"
              >
                <option value="">日</option>
                {Array.from({ length: 31 }, (_, i) => i + 1).map(d => (
                  <option key={d} value={String(d).padStart(2, '0')}>{d}日</option>
                ))}
              </select>
            </div>
            {traitBirthday && (
              <p className="mt-1 text-xs text-muted-foreground">
                当前年龄：{calcAge(traitBirthday)}岁
              </p>
            )}
          </div>

          <select
            value={traitGender}
            onChange={(e) => setTraitGender(e.target.value)}
            className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance:none"
          >
            <option value="">选择性别</option>
            <option value="MALE">男</option>
            <option value="FEMALE">女</option>
            <option value="OTHER">其他</option>
          </select>

          <select
            value={traitMarried}
            onChange={(e) => setTraitMarried(e.target.value)}
            className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance:none"
          >
            <option value="">婚姻状况</option>
            <option value="yes">已婚</option>
            <option value="no">未婚</option>
          </select>

          <select
            value={traitHasChildren}
            onChange={(e) => {
              setTraitHasChildren(e.target.value)
              if (e.target.value !== 'yes') setTraitChildrenAgeRanges([])
            }}
            className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance-none"
          >
            <option value="">是否有孩子</option>
            <option value="yes">有孩子</option>
            <option value="no">无孩子</option>
          </select>

          {traitHasChildren === 'yes' && (
            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1.5 block">孩子年龄（可多选）</label>
              <div className="flex flex-wrap gap-2">
                {CHILDREN_AGE_RANGE_OPTIONS.map(r => {
                  const selected = traitChildrenAgeRanges.includes(r.value)
                  return (
                    <button
                      key={r.value}
                      type="button"
                      onClick={() => {
                        setTraitChildrenAgeRanges(prev =>
                          selected ? prev.filter(v => v !== r.value) : [...prev, r.value]
                        )
                      }}
                      className={`inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors border ${
                        selected
                          ? 'bg-primary text-primary-foreground border-primary shadow-sm'
                          : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                      }`}
                    >
                      {selected && <Check className="h-3 w-3" />}
                      {r.label}
                    </button>
                  )
                })}
              </div>
            </div>
          )}

          <select
            value={traitMbti}
            onChange={(e) => setTraitMbti(e.target.value)}
            className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance:none"
          >
            <option value="">MBTI 人格</option>
            {MBTI_OPTIONS.map(m => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>

          <div>
            <label className="text-xs font-medium text-muted-foreground mb-1.5 block">职业方向（可多选）</label>
            <div className="flex flex-wrap gap-2">
              {OCCUPATION_OPTIONS.map(o => {
                const selected = traitOccupations.includes(o.value)
                return (
                  <button
                    key={o.value}
                    type="button"
                    onClick={() => {
                      setTraitOccupations(prev =>
                        selected ? prev.filter(v => v !== o.value) : [...prev, o.value]
                      )
                    }}
                    className={`inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors border ${
                      selected
                        ? 'bg-primary text-primary-foreground border-primary shadow-sm'
                        : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                    }`}
                  >
                    {selected && <Check className="h-3 w-3" />}
                    {o.label}
                  </button>
                )
              })}
            </div>
          </div>

          <select
            value={traitEducation}
            onChange={(e) => setTraitEducation(e.target.value)}
            className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance:none"
          >
            <option value="">当前/目标学历</option>
            {EDUCATION_OPTIONS.map(e => (
              <option key={e.value} value={e.value}>{e.label}</option>
            ))}
          </select>

          <select
            value={traitEntrepreneurship}
            onChange={(e) => setTraitEntrepreneurship(e.target.value)}
            className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance:none"
          >
            <option value="">创业意向</option>
            {ENTREPRENEURSHIP_OPTIONS.map(e => (
              <option key={e.value} value={e.value}>{e.label}</option>
            ))}
          </select>

          <select
            value={traitAnnualIncome}
            onChange={(e) => setTraitAnnualIncome(e.target.value)}
            className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 -webkit-appearance:none appearance:none"
          >
            <option value="">当前/期望年收入</option>
            {ANNUAL_INCOME_OPTIONS.map(e => (
              <option key={e.value} value={e.value}>{e.label}</option>
            ))}
          </select>

          </div>

          <div className="shrink-0 pt-4">
            <button
              onClick={handleSaveTraits}
              disabled={savingTraits}
              className="w-full rounded-xl bg-primary py-2.5 text-sm font-semibold text-primary-foreground disabled:opacity-50 shadow-md shadow-primary/20 active:scale-[0.98] transition-transform"
            >
              {savingTraits ? '保存中...' : '保存'}
            </button>
          </div>
        </SheetContent>
      </Sheet>

      <AvatarCropModal
        open={cropModalOpen}
        onOpenChange={setCropModalOpen}
        imageFile={avatarFile}
        onCropComplete={handleCropComplete}
      />

      {/* Style Picker Sheet */}
      <Sheet open={showStylePicker} onOpenChange={setShowStylePicker}>
        <SheetContent side={sheetSide} className={`p-5 [&>button]:hidden ${isMobile ? 'max-h-[85vh] rounded-t-2xl' : 'h-full sm:max-w-xl rounded-l-2xl'}`}>
        <SheetTitle className="sr-only">AI 对话风格</SheetTitle>
        <SheetDescription className="sr-only">选择 AI 图书问答的语气风格</SheetDescription>
        <MobileSheetHeader
          icon={<Sparkles className="h-5 w-5 text-primary" />}
          title="AI 对话风格"
          description="选择 AI 图书问答的语气风格"
          onClose={() => setShowStylePicker(false)}
        />
          <div className="grid grid-cols-2 gap-2">
            {BOOK_CHAT_STYLES.map(s => {
              const isActive = currentStyle === s.value
              return (
                <button
                  key={s.value}
                  onClick={async () => {
                    try {
                      const { updateBookChatStyle } = await import('@/api/auth')
                      await updateBookChatStyle(s.value)
                      updateUserInfo({ bookChatStyle: s.value })
                      setShowStylePicker(false)
                      toast.success(`已切换为「${s.label}」风格`)
                    } catch { toast.error('切换失败') }
                  }}
                  className={`text-left rounded-xl px-3.5 py-3 text-sm transition-colors border border-border ${
                    isActive
                      ? 'bg-primary text-primary-foreground'
                      : 'bg-background text-foreground hover:bg-muted'
                  }`}
                >
                  <div className="font-medium">{s.label}</div>
                  <div className={`text-xs mt-0.5 ${isActive ? 'opacity-70' : 'text-muted-foreground'}`}>{s.desc}</div>
                </button>
              )
            })}
          </div>
        </SheetContent>
      </Sheet>
    </div>
  )
}
