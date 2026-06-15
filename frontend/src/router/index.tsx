import { createBrowserRouter } from 'react-router-dom'
import { ROUTES } from '@/constants'
import { AppLayout, BlankLayout } from '@/components/layout/AppLayout'
import { AuthGuard, AdminGuard, GuestGuard } from '@/components/auth/AuthGuard'
import { RouteErrorBoundary } from '@/components/RouteErrorBoundary'
import { LazyLoad } from '@/components/LazyLoad'

import { lazy } from 'react'

const LoginPage = lazy(() => import('@/pages/auth/login'))
const RegisterPage = lazy(() => import('@/pages/auth/register'))
const ResetPasswordPage = lazy(() => import('@/pages/auth/reset-password'))
const ChangePasswordPage = lazy(() => import('@/pages/auth/change-password'))
const AdminReviewPage = lazy(() => import('@/pages/admin/review'))
const AdminBindEmailPage = lazy(() => import('@/pages/admin/bind-email'))
const AdminBooksPage = lazy(() => import('@/pages/admin/books'))
const AdminAiConfigPage = lazy(() => import('@/pages/admin/ai-config'))
const AdminTtsConfigPage = lazy(() => import('@/pages/admin/tts-config'))

const BookDetailPage = lazy(() => import('@/pages/book/detail'))
const SearchPage = lazy(() => import('@/pages/search'))
const ReadingListPage = lazy(() => import('@/pages/profile/reading-list'))
const RecommendPage = lazy(() => import('@/pages/home/recommend'))
const ChatListPage = lazy(() => import('@/pages/chat'))
const ChatRoomPage = lazy(() => import('@/pages/chat/room'))
const RoundTablePage = lazy(() => import('@/pages/book/round-table'))
const RoundTableSessionPage = lazy(() => import('@/pages/book/round-table-session'))
const DebatePage = lazy(() => import('@/pages/book/debate'))
const DebateSessionPage = lazy(() => import('@/pages/book/debate-session'))
const NotFoundPage = lazy(() => import('@/pages/not-found'))
const OnboardingPage = lazy(() => import('@/pages/auth/onboarding'))
const TermsPage = lazy(() => import('@/pages/legal/terms'))
const PrivacyPage = lazy(() => import('@/pages/legal/privacy'))

export const router = createBrowserRouter([
  {
    path: '/',
    element: (
      <AuthGuard>
        <AppLayout />
      </AuthGuard>
    ),
    errorElement: <RouteErrorBoundary />,
    children: [
      { index: true, element: <></> },
      { path: ROUTES.HOME, element: <></> },
      { path: ROUTES.DISCOVER, element: <></> },
      { path: ROUTES.PROFILE, element: <></> },
      { path: ROUTES.BOOK_DETAIL, element: <LazyLoad><BookDetailPage /></LazyLoad> },
      { path: ROUTES.SEARCH, element: <LazyLoad><SearchPage /></LazyLoad> },
      { path: ROUTES.READING_LIST, element: <LazyLoad><ReadingListPage /></LazyLoad> },
      { path: ROUTES.RECOMMEND, element: <LazyLoad><RecommendPage /></LazyLoad> },
      { path: ROUTES.CHAT, element: <LazyLoad><ChatListPage /></LazyLoad> },
      { path: ROUTES.CHAT_ROOM, element: <LazyLoad><ChatRoomPage /></LazyLoad> },
      { path: ROUTES.ROUND_TABLE, element: <LazyLoad><RoundTablePage /></LazyLoad> },
      { path: ROUTES.ROUND_TABLE_SESSION, element: <LazyLoad><RoundTableSessionPage /></LazyLoad> },
      { path: ROUTES.DEBATE, element: <LazyLoad><DebatePage /></LazyLoad> },
      { path: ROUTES.DEBATE_SESSION, element: <LazyLoad><DebateSessionPage /></LazyLoad> },
      { path: ROUTES.CHANGE_PASSWORD, element: <LazyLoad><ChangePasswordPage /></LazyLoad> },
      { path: ROUTES.TERMS, element: <LazyLoad><TermsPage /></LazyLoad> },
      { path: ROUTES.PRIVACY, element: <LazyLoad><PrivacyPage /></LazyLoad> },
      { path: '/onboarding', element: <LazyLoad><OnboardingPage /></LazyLoad> },
      { path: ROUTES.ADMIN_REVIEW, element: <AdminGuard><LazyLoad><AdminReviewPage /></LazyLoad></AdminGuard> },
      { path: ROUTES.ADMIN_BIND_EMAIL, element: <AdminGuard><LazyLoad><AdminBindEmailPage /></LazyLoad></AdminGuard> },
      { path: ROUTES.ADMIN_BOOKS, element: <AdminGuard><LazyLoad><AdminBooksPage /></LazyLoad></AdminGuard> },
      { path: ROUTES.ADMIN_AI_CONFIG, element: <AdminGuard><LazyLoad><AdminAiConfigPage /></LazyLoad></AdminGuard> },
      { path: ROUTES.ADMIN_TTS_CONFIG, element: <AdminGuard><LazyLoad><AdminTtsConfigPage /></LazyLoad></AdminGuard> },
    ],
  },
  {
    element: (
      <GuestGuard>
        <BlankLayout />
      </GuestGuard>
    ),
    errorElement: <RouteErrorBoundary />,
    children: [
      {
        path: ROUTES.LOGIN,
        element: <LazyLoad><LoginPage /></LazyLoad>,
      },
      {
        path: ROUTES.REGISTER,
        element: <LazyLoad><RegisterPage /></LazyLoad>,
      },
      {
        path: ROUTES.RESET_PASSWORD,
        element: <LazyLoad><ResetPasswordPage /></LazyLoad>,
      },
    ],
  },
  {
    path: '*',
    element: <LazyLoad><NotFoundPage /></LazyLoad>,
  },
])
