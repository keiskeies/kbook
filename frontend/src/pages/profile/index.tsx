import { useEffect, useState, useRef } from 'react'
import { Settings, ChevronRight, LogOut, Lock, BookOpen, ShieldCheck, Mail, Library, BookMarked, UserCircle, Camera, Bell, Users, Palette, SlidersHorizontal, XCircle, Clock, BookHeart, Check, MessageCircle } from 'lucide-react'
import { useAuthStore } from '@/store/auth'
import { useUiStore } from '@/store/ui'
import { useNavigate } from 'react-router-dom'
import { ROUTES } from '@/constants'
import { toast } from 'sonner'
import { getReadingStats } from '@/api/progress'
import { getBookshelfCount } from '@/api/bookshelf'
import { updateTraits, updateMood } from '@/api/auth'
import { updateProfile, uploadAvatar } from '@/api/user'
import { getExcludePreferences, addExcludePreference, removeExcludePreference, getIncludePreferences, addIncludePreference, removeIncludePreference } from '@/api/preference'
import { getUnreadCount } from '@/api/chat'
import type { UserBookPreferenceItem } from '@/api/preference'
import type { ReadingStats } from '@/types/book'
import { useChatStore } from '@/store/chat'
import { ThemeToggle } from '@/components/ThemeToggle'

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

const MOOD_OPTIONS = [
  { value: 'HAPPY', label: '开心', emoji: '😊' },
  { value: 'CALM', label: '平静', emoji: '😌' },
  { value: 'ANXIOUS', label: '焦虑', emoji: '😰' },
  { value: 'SAD', label: '低落', emoji: '😢' },
  { value: 'MOTIVATED', label: '充满动力', emoji: '🔥' },
  { value: 'TIRED', label: '疲惫', emoji: '😴' },
  { value: 'CURIOUS', label: '好奇', emoji: '🤔' },
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
  const { userInfo, updateUserInfo, logout } = useAuthStore()
  const { setUnreadCount } = useChatStore()
  const setTabBarVisible = useUiStore((s) => s.setTabBarVisible)
  const navigate = useNavigate()
  const [stats, setStats] = useState<ReadingStats | null>(null)
  const [shelfCount, setShelfCount] = useState<number>(0)
  const [chatUnreadCount, setChatUnreadCount] = useState<number>(0)

  const [showTraitsModal, setShowTraitsModal] = useState(false)
  const [traitBirthday, setTraitBirthday] = useState(userInfo?.birthday ?? '')
  const [traitGender, setTraitGender] = useState(userInfo?.gender ?? '')
  const [traitMarried, setTraitMarried] = useState(userInfo?.married === true ? 'yes' : userInfo?.married === false ? 'no' : '')
  const [traitHasChildren, setTraitHasChildren] = useState(userInfo?.hasChildren === true ? 'yes' : userInfo?.hasChildren === false ? 'no' : '')
  const [traitMbti, setTraitMbti] = useState(userInfo?.mbti ?? '')
  const [traitOccupations, setTraitOccupations] = useState<string[]>(() => {
    const occ = userInfo?.occupation
    return occ ? occ.split(',').filter(Boolean) : []
  })
  const [traitEducation, setTraitEducation] = useState(userInfo?.education ?? '')
  const [traitEntrepreneurship, setTraitEntrepreneurship] = useState(userInfo?.entrepreneurship ?? '')
  const [traitAnnualIncome, setTraitAnnualIncome] = useState(userInfo?.annualIncome ?? '')
  const [savingTraits, setSavingTraits] = useState(false)

  const [showProfileModal, setShowProfileModal] = useState(false)
  const [editNickname, setEditNickname] = useState(userInfo?.nickname ?? '')
  const [editBio, setEditBio] = useState(userInfo?.bio ?? '')
  const [editMood, setEditMood] = useState(userInfo?.mood ?? '')
  const [savingProfile, setSavingProfile] = useState(false)
  const [uploadingAvatar, setUploadingAvatar] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const [showPreferenceModal, setShowPreferenceModal] = useState(false)
  const [excludePrefs, setExcludePrefs] = useState<UserBookPreferenceItem[]>([])
  const [includePrefs, setIncludePrefs] = useState<UserBookPreferenceItem[]>([])
  const [prefTab, setPrefTab] = useState<'exclude' | 'include'>('include')
  const [prefCategory, setPrefCategory] = useState<'TAG' | 'AUTHOR' | 'FORMAT'>('TAG')
  const [prefValue, setPrefValue] = useState('')
  const [prefLoading, setPrefLoading] = useState(false)
  const [prefSaving, setPrefSaving] = useState(false)

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
    if (userInfo?.occupation) {
      const occLabels = userInfo.occupation.split(',').filter(Boolean).map((v: string) => {
        const found = OCCUPATION_OPTIONS.find(o => o.value === v)
        return found ? found.label : v
      })
      if (occLabels.length > 0) parts.push(occLabels.join('/'))
    }
    if (userInfo?.education) {
      const edu = EDUCATION_OPTIONS.find(o => o.value === userInfo.education)
      if (edu) parts.push(edu.label)
    }
    if (userInfo?.entrepreneurship) {
      const val = userInfo.entrepreneurship
      if (val === 'ENTREPRENEUR' || val === 'WANT_ENTREPRENEUR') {
        parts.push('正在创业/想创业')
      } else {
        const ent = ENTREPRENEURSHIP_OPTIONS.find(o => o.value === val)
        if (ent) parts.push(ent.label)
      }
    }
    if (userInfo?.annualIncome && userInfo.annualIncome !== 'PREFER_NOT_TO_SAY') {
      const inc = ANNUAL_INCOME_OPTIONS.find(o => o.value === userInfo.annualIncome)
      if (inc) parts.push(inc.label)
    }
    return parts.length > 0 ? parts.join(' · ') : '未设置'
  }

  useEffect(() => {
    getReadingStats().then((res) => setStats((res as any) || null)).catch(() => {})
    getBookshelfCount().then((res) => setShelfCount((res as any) || 0)).catch(() => {})
    getUnreadCount().then((res) => {
      const count = (res as any)?.data || (res as any) || 0
      setChatUnreadCount(count)
      setUnreadCount(count)
    }).catch(() => {})
  }, [setUnreadCount])

  useEffect(() => {
    const anyModalOpen = showProfileModal || showTraitsModal || showPreferenceModal
    setTabBarVisible(!anyModalOpen)
    return () => setTabBarVisible(true)
  }, [showProfileModal, showTraitsModal, showPreferenceModal])

  useEffect(() => {
    if (showProfileModal) {
      setEditNickname(userInfo?.nickname ?? '')
      setEditBio(userInfo?.bio ?? '')
      setEditMood(userInfo?.mood ?? '')
    }
  }, [showProfileModal, userInfo?.nickname, userInfo?.bio, userInfo?.mood])

  useEffect(() => {
    if (showTraitsModal) {
      setTraitBirthday(userInfo?.birthday ?? '')
      setTraitGender(userInfo?.gender ?? '')
      setTraitMarried(userInfo?.married === true ? 'yes' : userInfo?.married === false ? 'no' : '')
      setTraitHasChildren(userInfo?.hasChildren === true ? 'yes' : userInfo?.hasChildren === false ? 'no' : '')
      setTraitMbti(userInfo?.mbti ?? '')
      const occ = userInfo?.occupation
      setTraitOccupations(occ ? occ.split(',').filter(Boolean) : [])
      setTraitEducation(userInfo?.education ?? '')
      setTraitEntrepreneurship(userInfo?.entrepreneurship ?? '')
      setTraitAnnualIncome(userInfo?.annualIncome ?? '')
    }
  }, [showTraitsModal, userInfo])

  const menuGroups = [
    {
      title: '阅读',
      titleIcon: BookOpen,
      items: [
        { label: '我的书架', icon: Library, path: ROUTES.BOOKSHELF, extra: `${shelfCount} 本` },
        { label: '阅读历史', icon: Clock, path: '/profile/history', extra: stats ? `${stats.completedBooks} 本已读完` : '' },
      ],
    },
    {
      title: '推荐',
      titleIcon: BookHeart,
      items: [
        { label: '我的画像', icon: UserCircle, path: '', extra: getTraitsSummary(), action: () => setShowTraitsModal(true) },
        { label: '阅读偏好', icon: SlidersHorizontal, path: '', extra: '', action: () => setShowPreferenceModal(true) },
      ],
    },
    {
      title: '互动',
      titleIcon: Users,
      items: [
        { label: '我的关注', icon: Users, path: '', extra: `${userInfo?.followingCount || 0} 关注`, action: () => navigate(`/user/${userInfo?.id}/follow/followings`) },
        { label: '私信', icon: MessageCircle, path: '/chat', extra: chatUnreadCount > 0 ? `${chatUnreadCount} 未读` : '' },
        { label: '通知', icon: Bell, path: '/notifications', extra: '' },
      ],
    },
    {
      title: '设置',
      titleIcon: Settings,
      items: [
        { label: '主题模式', icon: Palette, path: '', extra: '', custom: true },
        { label: '修改密码', icon: Lock, path: ROUTES.CHANGE_PASSWORD, extra: '' },
      ],
    },
  ]

  const adminMenuItems = [
    { label: '图书管理', icon: BookMarked, path: ROUTES.ADMIN_BOOKS, badge: '' },
    { label: '用户审核', icon: ShieldCheck, path: ROUTES.ADMIN_REVIEW, badge: '' },
    { label: 'AI 配置', icon: Settings, path: ROUTES.ADMIN_AI_CONFIG, badge: '' },
    ...(needBindEmail
      ? [{ label: '绑定邮箱', icon: Mail, path: ROUTES.ADMIN_BIND_EMAIL, badge: '待绑定' }]
      : []),
  ]

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

  const handleAvatarUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    if (!file.type.startsWith('image/')) {
      toast.error('选择一张图片吧')
      return
    }
    if (file.size > 2 * 1024 * 1024) {
      toast.error('图片大小不要超过2MB哦')
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
      toast.error(err.message || '头像暂时无法上传')
    } finally {
      setUploadingAvatar(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  const handleSaveTraits = async () => {
    setSavingTraits(true)
    try {
      const data: any = {
        birthday: traitBirthday || null,
        gender: traitGender || null,
        married: traitMarried ? traitMarried === 'yes' : null,
        hasChildren: traitHasChildren ? traitHasChildren === 'yes' : null,
        mbti: traitMbti || null,
        occupation: traitOccupations.length > 0 ? traitOccupations.join(',') : null,
        education: traitEducation || null,
        entrepreneurship: traitEntrepreneurship || null,
        annualIncome: traitAnnualIncome || null,
      }
      await updateTraits(data)
      updateUserInfo({
        birthday: data.birthday,
        gender: data.gender,
        married: data.married,
        hasChildren: data.hasChildren,
        mbti: data.mbti,
        occupation: data.occupation,
        education: data.education,
        entrepreneurship: data.entrepreneurship,
        annualIncome: data.annualIncome,
      })
      setShowTraitsModal(false)
      toast.success('画像已更新')
    } catch (err: any) {
      toast.error(err.message || '更新未完成，稍后再试试')
    } finally {
      setSavingTraits(false)
    }
  }

  const catLabel = (c: string) => c === 'TAG' ? '标签' : c === 'AUTHOR' ? '作者' : '格式'

  const loadPreferences = async () => {
    setPrefLoading(true)
    try {
      const [excludeData, includeData] = await Promise.all([getExcludePreferences(), getIncludePreferences()])
      setExcludePrefs((excludeData as any) || [])
      setIncludePrefs((includeData as any) || [])
    } catch { setExcludePrefs([]); setIncludePrefs([]) }
    finally { setPrefLoading(false) }
  }

  useEffect(() => { if (showPreferenceModal) loadPreferences() }, [showPreferenceModal])

  const handleAddPreference = async () => {
    if (!prefValue.trim()) { toast.error('先写点什么吧'); return }
    setPrefSaving(true)
    try {
      if (prefTab === 'exclude') {
        await addExcludePreference(prefCategory, prefValue.trim())
        toast.success(`已添加：不想看${catLabel(prefCategory)}为"${prefValue.trim()}"的书籍`)
      } else {
        await addIncludePreference(prefCategory, prefValue.trim())
        toast.success(`已添加：想看${catLabel(prefCategory)}为"${prefValue.trim()}"的书籍`)
      }
      setPrefValue('')
      loadPreferences()
    } catch (err: any) {
      toast.error(err.message || '添加失败')
    } finally {
      setPrefSaving(false)
    }
  }

  const handleRemovePreference = async (category: string, value: string, type: 'exclude' | 'include') => {
    try {
      if (type === 'exclude') {
        await removeExcludePreference(category, value)
        toast.success('已恢复推荐')
      } else {
        await removeIncludePreference(category, value)
        toast.success('已取消偏好')
      }
      loadPreferences()
    } catch (err: any) {
      toast.error(err.message || '操作未完成')
    }
  }

  const getCategoryLabel = (cat: string) => {
    switch (cat) {
      case 'TAG': return '标签'
      case 'AUTHOR': return '作者'
      case 'FORMAT': return '格式'
      default: return cat
    }
  }

  const getCategoryColor = (cat: string) => {
    switch (cat) {
      case 'TAG': return 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400'
      case 'AUTHOR': return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'
      case 'FORMAT': return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
      default: return 'bg-muted text-muted-foreground'
    }
  }

  const avatarFullUrl = userInfo?.avatar
    ? (userInfo.avatar.startsWith('http') ? userInfo.avatar : userInfo.avatar)
    : null

  return (
    <div className="px-4 pt-safe-top pb-6 page-enter">
      <div className="h-4" />
      
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
                绑定邮箱后即可重置密码
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

      <div className="space-y-4">
        {menuGroups.map((group) => {
          const GroupIcon = group.titleIcon
          return (
            <div key={group.title} className="rounded-2xl bg-card shadow-sm border border-border/50 overflow-hidden">
              <div className="flex items-center gap-2 px-4 pt-3 pb-1.5">
                <GroupIcon className="h-3.5 w-3.5 text-muted-foreground" />
                <h3 className="text-xs font-bold text-muted-foreground tracking-wider">{group.title}</h3>
              </div>
              {group.items.map((item, i) => {
                const Icon = item.icon
                const isCustom = !!(item as any).custom
                const Wrapper = isCustom ? 'div' : 'button'
                return (
                  <Wrapper
                    key={item.label}
                    {...(isCustom ? {} : {
                      onClick: () => {
                        if ((item as any).action) (item as any).action()
                        else if (item.path) navigate(item.path)
                      }
                    })}
                    className={`flex w-full items-center justify-between px-4 py-3 ${isCustom ? '' : 'active:bg-muted/50'} transition-colors ${
                      i < group.items.length - 1 ? 'border-b border-border/50' : ''
                    }`}
                  >
                    <div className="flex items-center gap-3 shrink-0 min-w-0">
                      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-muted">
                        <Icon className="h-3.5 w-3.5 text-muted-foreground" />
                      </div>
                      <span className="text-sm font-medium shrink-0">{item.label}</span>
                    </div>
                    {isCustom ? (
                      <ThemeToggle />
                    ) : (
                      <div className="flex items-center gap-2 min-w-0 ml-auto">
                        {item.extra && (
                          <span className="text-xs text-muted-foreground truncate max-w-[160px]">{item.extra}</span>
                        )}
                        <ChevronRight className="h-4 w-4 text-muted-foreground shrink-0" />
                      </div>
                    )}
                  </Wrapper>
                )
              })}
            </div>
          )
        })}
      </div>

      {userInfo && (
        <button
          onClick={handleLogout}
          className="mt-5 flex w-full items-center justify-center gap-2 rounded-2xl bg-card py-3.5 text-sm font-medium text-destructive shadow-sm border border-border/50 active:scale-[0.98] transition-transform"
        >
          <LogOut className="h-4 w-4" />
          退出登录
        </button>
      )}

      <div className="mt-6 text-center">
        <span className="text-[10px] text-muted-foreground/60">@VERSION - KEISKEIES 1.0.3.2</span>
      </div>

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
              <p className="mt-1 text-[10px] text-muted-foreground text-right">{editBio.length}/200</p>
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

      {showTraitsModal && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 backdrop-blur-sm" onClick={() => setShowTraitsModal(false)}>
          <div className="w-full max-w-lg max-h-[85vh] rounded-t-3xl bg-card shadow-2xl flex flex-col overflow-hidden" onClick={e => e.stopPropagation()}>
            <div className="flex justify-center pt-3 pb-1">
              <div className="h-1 w-10 rounded-full bg-muted-foreground/20" />
            </div>
            <div className="flex items-center justify-between px-5">
              <h3 className="text-base font-bold">编辑我的画像</h3>
              <button onClick={() => setShowTraitsModal(false)} className="text-muted-foreground text-sm font-medium">关闭</button>
            </div>
            <p className="px-5 pt-1 text-xs text-muted-foreground">完善画像可获得更精准的图书推荐</p>
            <div className="w-full min-w-0 overflow-y-auto overscroll-y-contain overflow-x-hidden px-5 py-4 space-y-4" style={{ paddingBottom: 'calc(1rem + 5rem)' }}>

            <div>
              <label className="text-xs font-medium text-muted-foreground mb-1 block">出生日期</label>
              <input
                type="date"
                value={traitBirthday}
                onChange={(e) => setTraitBirthday(e.target.value)}
                max={new Date().toISOString().split('T')[0]}
                className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
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
              className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">选择性别</option>
              <option value="MALE">男</option>
              <option value="FEMALE">女</option>
              <option value="OTHER">其他</option>
            </select>

            <select
              value={traitMarried}
              onChange={(e) => setTraitMarried(e.target.value)}
              className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">婚姻状况</option>
              <option value="yes">已婚</option>
              <option value="no">未婚</option>
            </select>

            <select
              value={traitHasChildren}
              onChange={(e) => setTraitHasChildren(e.target.value)}
              className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">是否有孩子</option>
              <option value="yes">有孩子</option>
              <option value="no">无孩子</option>
            </select>

            <select
              value={traitMbti}
              onChange={(e) => setTraitMbti(e.target.value)}
              className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
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
              className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">当前/目标学历</option>
              {EDUCATION_OPTIONS.map(e => (
                <option key={e.value} value={e.value}>{e.label}</option>
              ))}
            </select>

            <select
              value={traitEntrepreneurship}
              onChange={(e) => setTraitEntrepreneurship(e.target.value)}
              className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">创业意向</option>
              {ENTREPRENEURSHIP_OPTIONS.map(e => (
                <option key={e.value} value={e.value}>{e.label}</option>
              ))}
            </select>

            <select
              value={traitAnnualIncome}
              onChange={(e) => setTraitAnnualIncome(e.target.value)}
              className="w-full max-w-full min-w-0 box-border rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50"
            >
              <option value="">当前/期望年收入</option>
              {ANNUAL_INCOME_OPTIONS.map(e => (
                <option key={e.value} value={e.value}>{e.label}</option>
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
        </div>
      )}

      {showPreferenceModal && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 backdrop-blur-sm" onClick={() => setShowPreferenceModal(false)}>
          <div className="w-full max-w-lg rounded-t-3xl bg-card p-5 space-y-4 shadow-2xl" style={{ paddingBottom: 'calc(1.25rem + 5rem)' }} onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-bold">阅读偏好</h2>
              <button onClick={() => setShowPreferenceModal(false)} className="text-muted-foreground hover:text-foreground">
                <XCircle className="h-5 w-5" />
              </button>
            </div>
            <p className="text-xs text-muted-foreground">
              设置你的阅读偏好，让推荐更懂你。喜欢的类型会优先推荐，不想看的会自动排除。
            </p>

            <div className="flex rounded-lg bg-muted p-1">
              <button
                onClick={() => { setPrefTab('include'); setPrefValue('') }}
                className={`flex-1 rounded-md py-1.5 text-sm font-medium transition-colors ${prefTab === 'include' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground'}`}
              >
                ❤️ 想看
              </button>
              <button
                onClick={() => { setPrefTab('exclude'); setPrefValue('') }}
                className={`flex-1 rounded-md py-1.5 text-sm font-medium transition-colors ${prefTab === 'exclude' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground'}`}
              >
                🚫 不想看
              </button>
            </div>

            <div className="space-y-2">
              <div className="flex gap-2">
                <select
                  value={prefCategory}
                  onChange={(e) => setPrefCategory(e.target.value as 'TAG' | 'AUTHOR' | 'FORMAT')}
                  className="rounded-lg border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-primary/20"
                >
                  <option value="TAG">标签</option>
                  <option value="AUTHOR">作者</option>
                  <option value="FORMAT">格式</option>
                </select>
                <input
                  type="text"
                  value={prefValue}
                  onChange={(e) => setPrefValue(e.target.value)}
                  placeholder={prefTab === 'exclude'
                    ? `输入不想看的${catLabel(prefCategory)}${prefCategory === 'FORMAT' ? '(如PDF)' : ''}`
                    : `输入想看的${catLabel(prefCategory)}${prefCategory === 'FORMAT' ? '(如EPUB)' : ''}`
                  }
                  className="flex-1 rounded-lg border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-primary/20"
                  onKeyDown={(e) => e.key === 'Enter' && handleAddPreference()}
                />
                <button
                  onClick={handleAddPreference}
                  disabled={prefSaving || !prefValue.trim()}
                  className={`rounded-lg px-3 py-2 text-sm font-medium text-white disabled:opacity-50 ${prefTab === 'include' ? 'bg-rose-500 hover:bg-rose-600' : 'bg-primary hover:bg-primary/90'}`}
                >
                  {prefSaving ? '...' : '添加'}
                </button>
              </div>
            </div>

            {prefTab === 'include' ? (
              <div>
                <h4 className="text-xs font-semibold text-muted-foreground mb-2">
                  想看的内容（{includePrefs.length}）
                </h4>
                {prefLoading ? (
                  <div className="py-4 text-center text-xs text-muted-foreground">加载中...</div>
                ) : includePrefs.length === 0 ? (
                  <div className="py-4 text-center text-xs text-muted-foreground">暂无偏好，添加喜欢的类型获取更精准推荐</div>
                ) : (
                  <div className="max-h-60 overflow-y-auto overscroll-y-contain space-y-1.5">
                    {includePrefs.map((pref) => (
                      <div key={pref.id} className="flex items-center gap-2 rounded-lg bg-rose-50 dark:bg-rose-900/10 px-3 py-2">
                        <span className="text-rose-500 text-xs">❤️</span>
                        <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${getCategoryColor(pref.category)}`}>
                          {getCategoryLabel(pref.category)}
                        </span>
                        <span className="flex-1 text-sm truncate">{pref.value}</span>
                        <button
                          onClick={() => handleRemovePreference(pref.category, pref.value, 'include')}
                          className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full hover:bg-red-100 text-red-500 hover:text-red-600 transition-colors"
                          title="取消偏好"
                        >
                          <XCircle className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ) : (
              <div>
                <h4 className="text-xs font-semibold text-muted-foreground mb-2">
                  已排除的内容（{excludePrefs.length}）
                </h4>
                {prefLoading ? (
                  <div className="py-4 text-center text-xs text-muted-foreground">加载中...</div>
                ) : excludePrefs.length === 0 ? (
                  <div className="py-4 text-center text-xs text-muted-foreground">暂无排除偏好</div>
                ) : (
                  <div className="max-h-60 overflow-y-auto overscroll-y-contain space-y-1.5">
                    {excludePrefs.map((pref) => (
                      <div key={pref.id} className="flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2">
                        <span className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${getCategoryColor(pref.category)}`}>
                          {getCategoryLabel(pref.category)}
                        </span>
                        <span className="flex-1 text-sm truncate">{pref.value}</span>
                        <button
                          onClick={() => handleRemovePreference(pref.category, pref.value, 'exclude')}
                          className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full hover:bg-red-100 text-red-500 hover:text-red-600 transition-colors"
                          title="恢复推荐"
                        >
                          <XCircle className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            <p className="text-xs text-muted-foreground text-center">
              {prefTab === 'include' ? '取消后，该类书籍不再获得优先推荐' : '恢复后，该类书籍将重新出现在推荐中'}
            </p>
          </div>
        </div>
      )}

    </div>
  )
}