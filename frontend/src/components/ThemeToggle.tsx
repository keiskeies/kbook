import { useTheme } from 'next-themes'
import { Sun, Moon, Monitor } from 'lucide-react'
import { useEffect, useState } from 'react'

// theme-color = body 背景色，让 Safari 延伸色与页面底色无缝衔接
const LIGHT_BG = '#F0EEEA'
const DARK_BG = '#0A0A0A'

function applyThemeColor(theme: string | undefined, resolvedTheme: string | undefined) {
  if (typeof document === 'undefined') return
  // 用户明确选择主题 → 用单一 theme-color 覆盖媒体查询版本
  // 用户选 system → 移除 plain 版本，让 html 里的 media 版本生效
  let plainMeta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]:not([media])')
  if (theme === 'system') {
    plainMeta?.remove()
    return
  }
  const color = resolvedTheme === 'dark' ? DARK_BG : LIGHT_BG
  if (!plainMeta) {
    plainMeta = document.createElement('meta')
    plainMeta.name = 'theme-color'
    document.head.appendChild(plainMeta)
  }
  plainMeta.content = color
}

export function ThemeToggle() {
  const { theme, resolvedTheme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)

  useEffect(() => { setMounted(true) }, [])

  useEffect(() => {
    if (!mounted) return
    applyThemeColor(theme, resolvedTheme)
  }, [theme, resolvedTheme, mounted])

  if (!mounted) return null

  const options = [
    { value: 'light', label: '', icon: Sun },
    { value: 'dark', label: '', icon: Moon },
    { value: 'system', label: '', icon: Monitor },
  ] as const

  return (
    <div className="flex items-center gap-2">
      {options.map((opt) => {
        const Icon = opt.icon
        const active = theme === opt.value
        return (
          <button
            key={opt.value}
            onClick={() => setTheme(opt.value)}
            className={`flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
              active
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:bg-muted/80'
            }`}
          >
            <Icon className="h-3.5 w-3.5" />
            {opt.label}
          </button>
        )
      })}
    </div>
  )
}
