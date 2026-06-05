import { Outlet, useLocation } from 'react-router-dom'
import { TabBar } from './TabBar'
import { DesktopSidebar } from './DesktopSidebar'
import { useUiStore } from '@/store/ui'
import { useKeepAliveStore } from '@/store/keepAlive'
import { ROUTES } from '@/constants'
import { lazy, Suspense, useRef, useEffect, useCallback } from 'react'

const HomePage = lazy(() => import('@/pages/home'))
const RankPage = lazy(() => import('@/pages/rank'))
const AIPage = lazy(() => import('@/pages/ai'))
const BookshelfPage = lazy(() => import('@/pages/bookshelf'))
const ProfilePage = lazy(() => import('@/pages/profile'))

const TAB_ROUTES = [
  { path: ROUTES.HOME, component: HomePage },
  { path: ROUTES.RANK, component: RankPage },
  { path: ROUTES.AI, component: AIPage },
  { path: ROUTES.BOOKSHELF, component: BookshelfPage },
  { path: ROUTES.PROFILE, component: ProfilePage },
]

function isTabPath(pathname: string): string | null {
  if (pathname === '/' || pathname === ROUTES.HOME) return ROUTES.HOME
  for (const tab of TAB_ROUTES) {
    if (tab.path === ROUTES.HOME) continue
    if (pathname === tab.path) return tab.path
  }
  return null
}

function TabPageShell({
  tabPath,
  isActive,
  children,
}: {
  tabPath: string
  isActive: boolean
  children: React.ReactNode
}) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const saveScroll = useKeepAliveStore((s) => s.saveScroll)
  const getScroll = useKeepAliveStore((s) => s.getScroll)
  const hasRestoredRef = useRef(false)

  const handleScroll = useCallback(() => {
    const el = scrollRef.current
    if (el) {
      saveScroll(tabPath, el.scrollTop)
    }
  }, [tabPath, saveScroll])

  useEffect(() => {
    if (!isActive) return
    if (hasRestoredRef.current) return

    const saved = getScroll(tabPath)
    if (saved !== undefined && saved > 0 && scrollRef.current) {
      requestAnimationFrame(() => {
        if (scrollRef.current) {
          scrollRef.current.scrollTop = saved
        }
      })
    }
    hasRestoredRef.current = true
  }, [isActive, tabPath, getScroll])

  useEffect(() => {
    if (isActive) {
      hasRestoredRef.current = true
    }
  }, [isActive])

  return (
    <div
      ref={scrollRef}
      onScroll={handleScroll}
      className="absolute inset-0 overflow-y-auto overscroll-contain"
      style={{ display: isActive ? 'block' : 'none' }}
    >
      {children}
    </div>
  )
}

function LazyFallback() {
  return (
    <div className="flex h-full items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
    </div>
  )
}

export function AppLayout() {
  const tabBarVisible = useUiStore((s) => s.tabBarVisible)
  const location = useLocation()
  const setActiveTab = useKeepAliveStore((s) => s.setActiveTab)

  const currentTabPath = isTabPath(location.pathname)

  useEffect(() => {
    if (currentTabPath) {
      setActiveTab(currentTabPath)
    }
  }, [currentTabPath, setActiveTab])

  const isOnTabPage = currentTabPath !== null

  return (
    <>
      <style>{`
        .app-layout-container {
          height: 100vh;
          height: 100dvh;
        }
      `}</style>
      <div className="app-layout-container flex overflow-hidden bg-background">
        {/* PC 侧边栏 - 移动端隐藏 */}
        <DesktopSidebar />

        {/* 主内容区 */}
        <div className="relative flex-1 flex flex-col overflow-hidden min-w-0">
          <div className="relative flex-1 overflow-hidden">
            {TAB_ROUTES.map(({ path, component: Page }) => {
              const isActive = isOnTabPage && path === currentTabPath
              return (
                <TabPageShell key={path} tabPath={path} isActive={isActive}>
                  <Suspense fallback={<LazyFallback />}>
                    <Page />
                  </Suspense>
                </TabPageShell>
              )
            })}

            {!isOnTabPage && (
              <div className="absolute inset-0 overflow-hidden">
                <Outlet />
              </div>
            )}
          </div>
          {/* 移动端底部 TabBar - PC隐藏 */}
          {isOnTabPage && tabBarVisible && <TabBar />}
        </div>
      </div>
    </>
  )
}

export function BlankLayout() {
  return (
    <div className="h-screen h-[100dvh] bg-background overflow-hidden">
      <Outlet />
    </div>
  )
}
