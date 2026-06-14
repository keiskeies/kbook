import { useState, useEffect, useCallback } from 'react'
import { useAuthStore } from '@/store/auth'
import { updateMood } from '@/api/auth'
import { toast } from 'sonner'
import { Zap, Heart, Coffee, Compass, Lightbulb } from 'lucide-react'

// ---- 阅读意图 ----
const INTENTS = [
  { key: 'GROWTH', icon: Zap, label: '充电' },
  { key: 'COMFORT', icon: Heart, label: '共鸣' },
  { key: 'ESCAPE', icon: Coffee, label: '放松' },
  { key: 'EXCITE', icon: Compass, label: '新鲜' },
  { key: 'INSIGHT', icon: Lightbulb, label: '解惑' },
]

// ---- 情绪底色 ----
const MOODS = [
  { key: 'HAPPY', emoji: '😊', label: '开心' },
  { key: 'CALM', emoji: '😌', label: '平静' },
  { key: 'ANXIOUS', emoji: '😰', label: '焦虑' },
  { key: 'SAD', emoji: '😢', label: '低落' },
  { key: 'FRUSTRATED', emoji: '😤', label: '烦躁' },
  { key: 'TIRED', emoji: '😴', label: '疲惫' },
]

/**
 * 阅读意图 + 情绪底色 快捷选择器
 *
 * 编码规则：mood 字段存 "INTENT|MOOD"，如 "GROWTH|CALM"
 */
export default function MoodQuickSwitch() {
  const userInfo = useAuthStore((s) => s.userInfo)
  const updateUserInfo = useAuthStore((s) => s.updateUserInfo)
  const [intent, setIntent] = useState('GROWTH')
  const [mood, setMood] = useState('CALM')
  const [switching, setSwitching] = useState(false)
  const [visible, setVisible] = useState(true)

  // 从 store 解析当前值
  useEffect(() => {
    const raw = userInfo?.mood || ''
    const pipeIdx = raw.indexOf('|')
    if (pipeIdx > 0) {
      setIntent(raw.substring(0, pipeIdx))
      setMood(raw.substring(pipeIdx + 1))
    } else if (raw && MOODS.some((m) => m.key === raw.toUpperCase())) {
      setMood(raw.toUpperCase())
    }
  }, [userInfo?.mood])

  const sync = useCallback(
    async (newIntent: string, newMood: string) => {
      if (switching) return
      setSwitching(true)
      const combined = `${newIntent}|${newMood}`
      try {
        await updateMood(combined)
        updateUserInfo({ mood: combined })
      } catch (err: any) {
        toast.error(err?.message || '切换失败')
      } finally {
        setSwitching(false)
      }
    },
    [switching, updateUserInfo],
  )

  const handleIntent = (key: string) => {
    if (key === intent) return
    setIntent(key)
    sync(key, mood)
  }

  const handleMood = (key: string) => {
    if (key === mood) return
    setMood(key)
    sync(intent, key)
  }

  if (!visible) return null

  return (
    <div className="rounded-2xl bg-card border border-border/50 shadow-sm p-4">
      {/* 顶栏：标题 + 关闭 */}
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-detail font-semibold text-foreground">今天想读什么？</span>
          <span className="hidden sm:inline text-xs text-muted-foreground/60">切换后推荐实时刷新</span>
        </div>
        <button
          onClick={() => setVisible(false)}
          className="flex h-5 w-5 items-center justify-center rounded-full text-muted-foreground/40 hover:text-muted-foreground hover:bg-muted/50 transition-colors shrink-0"
        >
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
            <line x1="2" y1="2" x2="8" y2="8" />
            <line x1="8" y1="2" x2="2" y2="8" />
          </svg>
        </button>
      </div>

      {/* 意图行 — 等宽胶囊按钮，不缩放 */}
      <div className="flex gap-2">
        {INTENTS.map((item) => {
          const active = item.key === intent
          const Icon = item.icon
          return (
            <button
              key={item.key}
              onClick={() => handleIntent(item.key)}
              disabled={switching}
              className={`flex-1 flex flex-col items-center justify-center gap-1 rounded-xl py-2.5 transition-colors duration-200 ${
                active
                  ? 'bg-brand-400 text-white shadow-sm'
                  : 'bg-muted/40 text-muted-foreground hover:bg-brand-50 hover:text-brand-600 dark:hover:bg-brand-950/40 dark:hover:text-brand-300'
              } ${switching ? 'opacity-50' : 'active:bg-brand-500'}`}
            >
              <Icon
                className={`h-4 w-4 transition-transform duration-200 ${active ? 'scale-110' : ''}`}
                strokeWidth={active ? 2.5 : 1.8}
              />
              <span className="text-xs font-medium leading-none">{item.label}</span>
            </button>
          )
        })}
      </div>

      {/* 分隔 */}
      <div className="my-3 h-px bg-border/40" />

      {/* 情绪行 */}
      <div className="flex items-center gap-2">
        <span className="text-xs text-muted-foreground/60 font-medium shrink-0">此刻</span>
        <div className="flex items-center gap-1">
          {MOODS.map((item) => {
            const active = item.key === mood
            return (
              <button
                key={item.key}
                onClick={() => handleMood(item.key)}
                disabled={switching}
                title={item.label}
                className={`flex h-8 w-8 items-center justify-center rounded-full text-base transition-all duration-200 ${
                  active
                    ? 'bg-brand-100 dark:bg-brand-900/40 ring-1 ring-brand-400/40'
                    : 'hover:bg-muted/60'
                } ${switching ? 'opacity-50' : 'active:scale-90'}`}
              >
                {item.emoji}
              </button>
            )
          })}
        </div>
        <span className="ml-1 text-xs text-muted-foreground/70 font-medium">
          {MOODS.find((m) => m.key === mood)?.label}
        </span>
      </div>
    </div>
  )
}
