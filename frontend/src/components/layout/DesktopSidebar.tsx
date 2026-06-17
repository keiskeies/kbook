import { useLocation, useNavigate } from 'react-router-dom'
import { Home, Compass, User, BookOpenCheck, Sun, Moon } from 'lucide-react'
import { ROUTES } from '@/constants'
import { cn } from '@/utils/cn'
import { useAuthStore } from '@/store/auth'
import { useTheme } from 'next-themes'
import { useEffect, useState } from 'react'

const navItems = [
  { path: ROUTES.HOME, label: '首页', icon: Home },
  { path: ROUTES.DISCOVER, label: '发现', icon: Compass },
  { path: ROUTES.PROFILE, label: '我的', icon: User },
]

export function DesktopSidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const userInfo = useAuthStore((s) => s.userInfo)
  const { theme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)
  useEffect(() => { setMounted(true) }, [])

  // 屏幕宽度 < 1024px 时自动收缩为图标模式
  const [autoCollapsed, setAutoCollapsed] = useState(false)
  useEffect(() => {
    const mql = window.matchMedia('(max-width: 1299px)')
    const handler = (e: MediaQueryListEvent) => setAutoCollapsed(e.matches)
    setAutoCollapsed(mql.matches)
    mql.addEventListener('change', handler)
    return () => mql.removeEventListener('change', handler)
  }, [])

  const effectiveCollapsed = autoCollapsed

  const isActive = (path: string) => {
    if (path === ROUTES.HOME) return location.pathname === '/' || location.pathname === ROUTES.HOME
    return location.pathname.startsWith(path)
  }

  return (
    <aside className={cn(
      'hidden md:flex md:flex-col md:border-r md:border-border/50 bg-navbar text-navbar-foreground h-full shrink-0 transition-all duration-300 ease-out',
      effectiveCollapsed ? 'md:w-[68px]' : 'md:w-60 lg:w-64'
    )}>
      {/* Logo */}
      <div className={cn(
        'flex items-center border-b border-sidebar-border transition-all duration-300',
        effectiveCollapsed ? 'justify-center px-0 py-4' : 'gap-2.5 px-5 py-5'
      )}>
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary shadow-sm">
          <BookOpenCheck className="h-5 w-5 text-primary-foreground" strokeWidth={2.5} />
        </div>
        {!effectiveCollapsed && (
          <div className="overflow-hidden whitespace-nowrap">
            <h1 className="text-lg font-bold bg-gradient-to-r from-primary to-primary/70 bg-clip-text text-transparent">KBook</h1>
            <p className="text-xs text-sidebar-foreground/50 -mt-0.5">你的 AI 读书智囊团</p>
          </div>
        )}
      </div>

      {/* Navigation */}
      <nav className={cn('flex-1 space-y-1 transition-all duration-300', effectiveCollapsed ? 'px-2 py-3' : 'px-3 py-4')}>
        {navItems.map((item) => {
          const active = isActive(item.path)
          const Icon = item.icon
          return (
            <button
              key={item.path}
              onClick={() => navigate(item.path)}
              title={effectiveCollapsed ? item.label : undefined}
              className={cn(
                'flex items-center rounded-xl text-sm font-medium transition-all duration-200',
                effectiveCollapsed
                  ? 'justify-center w-11 h-11 mx-auto'
                  : 'w-full gap-3 px-3 py-2.5',
                active
                  ? 'bg-sidebar-primary/10 text-sidebar-primary'
                  : 'text-sidebar-foreground/60 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'
              )}
            >
              <div className={cn(
                'flex items-center justify-center rounded-lg transition-all duration-200',
                effectiveCollapsed ? 'h-9 w-9' : 'h-8 w-8',
                active
                  ? 'bg-sidebar-primary text-sidebar-primary-foreground shadow-sm'
                  : 'bg-transparent'
              )}>
                <Icon className="h-[18px] w-[18px]" strokeWidth={active ? 2.2 : 1.8} />
              </div>
              {!effectiveCollapsed && <span className="overflow-hidden whitespace-nowrap">{item.label}</span>}
              {!effectiveCollapsed && active && (
                <div className="ml-auto h-1.5 w-1.5 rounded-full bg-sidebar-primary" />
              )}
            </button>
          )
        })}
      </nav>

      {/* Bottom area */}
      <div className="border-t border-sidebar-border">
        {effectiveCollapsed ? (
          <div className="flex flex-col items-center gap-2 py-3 px-2">
            {/* Avatar */}
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-sidebar-primary/10 text-sidebar-primary font-bold text-sm">
              {userInfo?.nickname?.[0] || 'U'}
            </div>
            {/* Theme toggle */}
            {mounted && (
              <button
                onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-sidebar-foreground/50 hover:text-sidebar-foreground hover:bg-sidebar-accent transition-colors"
                title={theme === 'dark' ? '切换到日间模式' : '切换到夜间模式'}
              >
                {theme === 'dark' ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />}
              </button>
            )}

          </div>
        ) : (
          <div className="px-4 py-4">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-sidebar-primary/10 text-sidebar-primary font-bold text-sm">
                {userInfo?.nickname?.[0] || 'U'}
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium truncate">{userInfo?.nickname || '未登录'}</p>
                <p className="text-xs text-sidebar-foreground/40 truncate">{userInfo?.email || ''}</p>
              </div>
              {mounted && (
                <button
                  onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
                  className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-sidebar-foreground/50 hover:text-sidebar-foreground hover:bg-sidebar-accent transition-colors"
                  title={theme === 'dark' ? '切换到日间模式' : '切换到夜间模式'}
                >
                  {theme === 'dark' ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />}
                </button>
              )}

            </div>
          </div>
        )}
      </div>
    </aside>
  )
}
