import { useLocation, useNavigate } from 'react-router-dom'
import { Home, Trophy, BookOpen, User } from 'lucide-react'
import { ROUTES } from '@/constants'
import { cn } from '@/lib/utils'
import { useUiStore } from '@/store/ui'

/** 会眨眼的 AI 机器人图标 */
export function BlinkingBot({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      {/* 头部轮廓 — 整体下移 */}
      <rect x="3" y="6" width="18" height="14" rx="4" />
      {/* 天线 */}
      <line x1="12" y1="6" x2="12" y2="3" />
      <circle cx="12" cy="3" r="1" fill="currentColor" />
      {/* 左眼 */}
      <g>
        <animateTransform
          attributeName="transform"
          type="translate"
          values="0,0; 1,0; -1,0; 0,0; 0,1; 0,-1; 0,0"
          dur="5s"
          repeatCount="indefinite"
        />
        <ellipse cx="9" cy="12" rx="1.5" ry="2">
          <animate
            attributeName="ry"
            values="2;0.01;2;0.01;2;2;2;2;2;2"
            dur="5s"
            repeatCount="indefinite"
          />
        </ellipse>
      </g>
      {/* 右眼 */}
      <g>
        <animateTransform
          attributeName="transform"
          type="translate"
          values="0,0; 1,0; -1,0; 0,0; 0,1; 0,-1; 0,0"
          dur="5s"
          repeatCount="indefinite"
        />
        <ellipse cx="15" cy="12" rx="1.5" ry="2">
          <animate
            attributeName="ry"
            values="2;0.01;2;0.01;2;2;2;2;2;2"
            dur="5s"
            repeatCount="indefinite"
          />
        </ellipse>
      </g>

    </svg>
  )
}

const tabs = [
  { path: ROUTES.HOME, label: '首页', icon: Home },
  { path: ROUTES.RANK, label: '榜单', icon: Trophy },
  { path: ROUTES.AI, label: 'AI', icon: BlinkingBot, center: true },
  { path: ROUTES.BOOKSHELF, label: '书架', icon: BookOpen },
  { path: ROUTES.PROFILE, label: '我的', icon: User },
]

export function TabBar() {
  const location = useLocation()
  const navigate = useNavigate()
  const tabBarVisible = useUiStore((s) => s.tabBarVisible)

  const isActive = (path: string) => location.pathname.startsWith(path)

  if (!tabBarVisible) return null

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-50 border-t bg-gradient-to-t from-primary/8 via-primary/3 to-transparent backdrop-blur-xl safe-area-bottom">
      <div className="mx-auto flex h-16 max-w-lg items-center justify-around px-2">
        {tabs.map((tab) => {
          const active = isActive(tab.path)
          const Icon = tab.icon

          if (tab.center) {
            return (
              <button
                key={tab.path}
                onClick={() => navigate(tab.path)}
                className="relative -mt-6 flex flex-col items-center"
              >
                <div
                  className={cn(
                    'flex h-14 w-14 items-center justify-center rounded-full shadow-lg shadow-primary/30 transition-all duration-300',
                    active
                      ? 'bg-gradient-to-br from-primary to-primary/80 text-primary-foreground scale-110'
                      : 'bg-gradient-to-br from-primary to-primary/85 text-primary-foreground hover:scale-105'
                  )}
                >
                  <Icon className="h-7 w-7" strokeWidth={2.2} />
                </div>
                <span
                  className={cn(
                    'mt-1 text-[10px] font-semibold transition-colors',
                    active ? 'text-primary' : 'text-muted-foreground'
                  )}
                >
                  {tab.label}
                </span>
              </button>
            )
          }

          return (
            <button
              key={tab.path}
              onClick={() => navigate(tab.path)}
              className="flex flex-col items-center gap-0.5 py-1 min-w-[48px]"
            >
              <div className={cn(
                'flex h-8 w-8 items-center justify-center rounded-lg transition-all duration-200',
                active ? 'bg-primary/10' : ''
              )}>
                <Icon
                  className={cn(
                    'h-[18px] w-[18px] transition-all duration-200',
                    active ? 'text-primary' : 'text-muted-foreground'
                  )}
                  strokeWidth={active ? 2.5 : 1.8}
                />
              </div>
              <span
                className={cn(
                  'text-[10px] transition-colors duration-200',
                  active ? 'font-semibold text-primary' : 'text-muted-foreground'
                )}
              >
                {tab.label}
              </span>
            </button>
          )
        })}
      </div>
    </nav>
  )
}
