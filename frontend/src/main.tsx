import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { ThemeProvider } from 'next-themes'
import { Toaster } from '@/components/ui/sonner'
import TtsFloatPlayer from '@/components/reader/TtsFloatPlayer'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import { useAuthStore } from '@/store/auth'
import { router } from '@/router'
import './index.css'
import './App.css'

// 启动时恢复认证状态
useAuthStore.getState().hydrate()

createRoot(document.getElementById('root')!).render(
  <ErrorBoundary>
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
      <RouterProvider router={router} />
      <Toaster position="top-center" richColors closeButton duration={4000} style={{ marginTop: '60px' }} />
      <TtsFloatPlayer />
    </ThemeProvider>
  </ErrorBoundary>
)
