import { Outlet, useLocation } from 'react-router-dom'
import { TabBar } from './TabBar'
import { useUiStore } from '@/store/ui'
import { useEffect, useRef } from 'react'

export function AppLayout() {
  const tabBarVisible = useUiStore((s) => s.tabBarVisible)
  const location = useLocation()
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = 0
    }
  }, [location.pathname])

  return (
    <>
      <style>{`
        .app-layout-container {
          height: 100vh;
          height: 100dvh;
        }
      `}</style>
      <div className="app-layout-container relative flex flex-col overflow-hidden bg-background">
        <main 
          ref={scrollRef}
          className={`flex-1 overflow-y-auto overscroll-contain ${tabBarVisible ? 'pb-20' : ''}`}
        >
          <Outlet />
        </main>
        {tabBarVisible && <TabBar />}
      </div>
    </>
  )
}

export function BlankLayout() {
  return (
    <div className="min-h-screen bg-background">
      <Outlet />
    </div>
  )
}
