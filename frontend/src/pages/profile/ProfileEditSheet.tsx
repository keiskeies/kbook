import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { UserCircle, Camera } from 'lucide-react'
import { updateProfile, uploadAvatar } from '@/api/user'
import { updateMood } from '@/api/auth'
import type { UserInfo } from '@/store/auth'
import { Sheet, SheetContent, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import MobileSheetHeader from '@/components/common/MobileSheetHeader'

const MOOD_OPTIONS = [
  { value: 'HAPPY', label: '开心', emoji: '😊' },
  { value: 'CALM', label: '平静', emoji: '😌' },
  { value: 'ANXIOUS', label: '焦虑', emoji: '😰' },
  { value: 'SAD', label: '低落', emoji: '😢' },
  { value: 'MOTIVATED', label: '充满动力', emoji: '🔥' },
  { value: 'TIRED', label: '疲惫', emoji: '😴' },
  { value: 'CURIOUS', label: '好奇', emoji: '🤔' },
]

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  userInfo: UserInfo | null
  updateUserInfo: (data: Partial<UserInfo>) => void
  isMobile: boolean
  sheetSide: 'bottom' | 'right'
  fileInputRef: React.RefObject<HTMLInputElement | null>
  croppedAvatarUrl: string | null
  croppedAvatarBlob: Blob | null
}

export default function ProfileEditSheet({ open, onOpenChange, userInfo, updateUserInfo, isMobile, sheetSide, fileInputRef, croppedAvatarUrl, croppedAvatarBlob }: Props) {
  const [editNickname, setEditNickname] = useState(userInfo?.nickname ?? '')
  const [editBio, setEditBio] = useState(userInfo?.bio ?? '')
  const [editMood, setEditMood] = useState(userInfo?.mood ?? '')
  const [savingProfile, setSavingProfile] = useState(false)

  const avatarFullUrl = userInfo?.avatar
    ? (userInfo.avatar.startsWith('http') ? userInfo.avatar : userInfo.avatar)
    : null

  useEffect(() => {
    if (open) {
      setEditNickname(userInfo?.nickname ?? '')
      setEditBio(userInfo?.bio ?? '')
      setEditMood(userInfo?.mood ?? '')
    }
  }, [open, userInfo?.nickname, userInfo?.bio, userInfo?.mood])

  const handleSave = async () => {
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
        if (avatarUrl) updateUserInfo({ avatar: avatarUrl })
      }

      await updateProfile({ nickname: editNickname.trim() })
      updateUserInfo({ nickname: editNickname.trim(), bio: editBio.trim() })
      const { updateBio } = await import('@/api/userProfile')
      await updateBio(editBio.trim())
      if (editMood !== (userInfo?.mood ?? '')) {
        await updateMood(editMood)
        updateUserInfo({ mood: editMood || null })
      }
      onOpenChange(false)
      toast.success('个人信息已更新')
    } catch (err: any) {
      toast.error(err.message || '更新未完成，稍后再试试')
    } finally {
      setSavingProfile(false)
    }
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side={sheetSide} className={`flex flex-col gap-0 p-5 [&>button]:hidden ${isMobile ? 'max-h-[85vh] rounded-t-2xl' : 'h-full sm:max-w-xl rounded-l-2xl'}`}>
        <SheetTitle className="sr-only">编辑个人信息</SheetTitle>
        <SheetDescription className="sr-only">编辑你的昵称、头像、简介和心情</SheetDescription>
        <MobileSheetHeader
          icon={<UserCircle className="h-5 w-5 text-primary" />}
          title="编辑个人信息"
          onClose={() => onOpenChange(false)}
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
              placeholder="输入昵称" maxLength={20}
              className="w-full rounded-xl border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/50 transition-shadow"
            />
          </div>

          <div>
            <label className="text-xs font-medium text-muted-foreground mb-1 block">个人简介</label>
            <textarea
              value={editBio}
              onChange={(e) => setEditBio(e.target.value)}
              placeholder="介绍一下自己吧..." maxLength={200} rows={3}
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
                    key={m.value} type="button"
                    onClick={() => setEditMood(isActive ? '' : m.value)}
                    className={`inline-flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors border ${
                      isActive ? 'bg-primary text-primary-foreground border-primary shadow-sm' : 'bg-background text-muted-foreground border-border hover:border-primary/40'
                    }`}
                  >
                    <span>{m.emoji}</span><span>{m.label}</span>
                  </button>
                )
              })}
            </div>
          </div>
        </div>

        <div className="shrink-0 pt-4">
          <button
            onClick={handleSave}
            disabled={savingProfile || !editNickname.trim()}
            className="w-full rounded-xl bg-primary py-2.5 text-sm font-semibold text-primary-foreground disabled:opacity-50 shadow-md shadow-primary/20 active:scale-[0.98] transition-transform"
          >
            {savingProfile ? '保存中...' : (croppedAvatarBlob ? '保存（含新头像）' : '保存')}
          </button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
