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
const ReaderPage = lazy(() => import('@/pages/reader'))
const ReadingHistoryPage = lazy(() => import('@/pages/profile/history'))
const BookTrashPage = lazy(() => import('@/pages/profile/trash'))
const ReviewsPage = lazy(() => import('@/pages/reviews'))
const RecommendPage = lazy(() => import('@/pages/home/recommend'))
const NotificationsPage = lazy(() => import('@/pages/notifications'))
const UserProfilePage = lazy(() => import('@/pages/user/profile'))
const FollowListPage = lazy(() => import('@/pages/follow/list'))
const ChatListPage = lazy(() => import('@/pages/chat'))
const ChatRoomPage = lazy(() => import('@/pages/chat/room'))
const NotFoundPage = lazy(() => import('@/pages/not-found'))
const OnboardingPage = lazy(() => import('@/pages/auth/onboarding'))

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
      { path: ROUTES.CHANGE_PASSWORD, element: <LazyLoad><ChangePasswordPage /></LazyLoad> },
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
    element: (
      <AuthGuard>
        <BlankLayout />
      </AuthGuard>
    ),
    errorElement: <RouteErrorBoundary />,
    children: [
      {
        path: '/onboarding',
        element: <LazyLoad><OnboardingPage /></LazyLoad>,
      },
      {
        path: ROUTES.BOOK_DETAIL,
        element: <LazyLoad><BookDetailPage /></LazyLoad>,
      },
      {
        path: ROUTES.SEARCH,
        element: <LazyLoad><SearchPage /></LazyLoad>,
      },
      {
        path: ROUTES.HISTORY,
        element: <LazyLoad><ReadingHistoryPage /></LazyLoad>,
      },
      {
        path: ROUTES.TRASH,
        element: <LazyLoad><BookTrashPage /></LazyLoad>,
      },
      {
        path: ROUTES.REVIEWS,
        element: <LazyLoad><ReviewsPage /></LazyLoad>,
      },
      {
        path: ROUTES.RECOMMEND,
        element: <LazyLoad><RecommendPage /></LazyLoad>,
      },
      {
        path: ROUTES.NOTIFICATIONS,
        element: <LazyLoad><NotificationsPage /></LazyLoad>,
      },
      {
        path: ROUTES.USER_PROFILE,
        element: <LazyLoad><UserProfilePage /></LazyLoad>,
      },
      {
        path: ROUTES.FOLLOW_LIST + '/:tab?',
        element: <LazyLoad><FollowListPage /></LazyLoad>,
      },
      {
        path: ROUTES.CHAT,
        element: <LazyLoad><ChatListPage /></LazyLoad>,
      },
      {
        path: ROUTES.CHAT_ROOM,
        element: <LazyLoad><ChatRoomPage /></LazyLoad>,
      },
      {
        path: ROUTES.READER,
        element: <LazyLoad><ReaderPage /></LazyLoad>,
      },
    ],
  },
  {
    element: (
      <AdminGuard>
        <BlankLayout />
      </AdminGuard>
    ),
    errorElement: <RouteErrorBoundary />,
    children: [
      {
        path: ROUTES.ADMIN_REVIEW,
        element: <LazyLoad><AdminReviewPage /></LazyLoad>,
      },
      {
        path: ROUTES.ADMIN_BIND_EMAIL,
        element: <LazyLoad><AdminBindEmailPage /></LazyLoad>,
      },
      {
        path: ROUTES.ADMIN_BOOKS,
        element: <LazyLoad><AdminBooksPage /></LazyLoad>,
      },
      {
        path: ROUTES.ADMIN_AI_CONFIG,
        element: <LazyLoad><AdminAiConfigPage /></LazyLoad>,
      },
      {
        path: ROUTES.ADMIN_TTS_CONFIG,
        element: <LazyLoad><AdminTtsConfigPage /></LazyLoad>,
      },
    ],
  },
  {
    path: '*',
    element: <LazyLoad><NotFoundPage /></LazyLoad>,
  },
])
