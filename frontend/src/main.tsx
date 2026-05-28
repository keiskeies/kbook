import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { ThemeProvider } from 'next-themes'
import { Toaster } from '@/components/ui/sonner'
import TtsFloatPlayer from '@/components/reader/TtsFloatPlayer'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { useAuthStore } from '@/store/auth'
import { useTtsStore } from '@/store/tts'
import { initTokenSyncListener } from '@/utils/token-sync'
import { getActiveTtsConfig } from '@/api/adminTts'
import { router } from '@/router'
import './index.css'
import './App.css'

// 启动时恢复认证状态（try-catch 兜底，部分移动端浏览器隐私模式下 localStorage 会抛异常）
try {
  useAuthStore.getState().hydrate()
} catch { /* ignore */ }

if (useAuthStore.getState().isAuthenticated) {
  try {
    getActiveTtsConfig().then((config) => {
      if (config) {
        useTtsStore.getState().setBackendConfig(config)
        useTtsStore.getState().setBackendMode(true)
      }
    }).catch(() => { /* no backend TTS configured */ })
  } catch { /* ignore */ }
}

// 初始化多标签页 token 同步监听（try-catch 兜底，部分移动端 BroadcastChannel 会抛异常）
try {
  initTokenSyncListener(
    (token, refreshToken) => {
      try {
        localStorage.setItem('kbook_token', token)
        localStorage.setItem('kbook_refresh_token', refreshToken)
      } catch { /* ignore */ }
      useAuthStore.getState().hydrate()
    },
    () => {
      try {
        localStorage.removeItem('kbook_token')
        localStorage.removeItem('kbook_refresh_token')
        localStorage.removeItem('kbook_user_info')
      } catch { /* ignore */ }
      useAuthStore.setState({ token: null, userInfo: null, isAuthenticated: false })
    },
  )
} catch { /* ignore */ }

createRoot(document.getElementById('root')!).render(
  <ErrorBoundary>
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
      <RouterProvider router={router} />
      <Toaster position="top-center" richColors closeButton duration={4000} style={{ marginTop: '60px' }} />
      <TtsFloatPlayer />
    </ThemeProvider>
  </ErrorBoundary>
)
