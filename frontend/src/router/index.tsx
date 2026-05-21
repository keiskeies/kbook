import { createBrowserRouter } from 'react-router-dom'
import { ROUTES } from '@/constants'
import { AppLayout, BlankLayout } from '@/components/layout/AppLayout'
import { AuthGuard, AdminGuard, GuestGuard } from '@/components/auth/AuthGuard'
import { RouteErrorBoundary } from '@/components/RouteErrorBoundary'

import { lazy, Suspense } from 'react'

const HomePage = lazy(() => import('@/pages/home'))
const RankPage = lazy(() => import('@/pages/rank'))
const AIPage = lazy(() => import('@/pages/ai'))
const BookshelfPage = lazy(() => import('@/pages/bookshelf'))
const ProfilePage = lazy(() => import('@/pages/profile'))
const LoginPage = lazy(() => import('@/pages/auth/login'))
const RegisterPage = lazy(() => import('@/pages/auth/register'))
const ResetPasswordPage = lazy(() => import('@/pages/auth/reset-password'))
const ChangePasswordPage = lazy(() => import('@/pages/auth/change-password'))
const AdminReviewPage = lazy(() => import('@/pages/admin/review'))
const AdminBindEmailPage = lazy(() => import('@/pages/admin/bind-email'))
const AdminBooksPage = lazy(() => import('@/pages/admin/books'))
const AdminAiConfigPage = lazy(() => import('@/pages/admin/ai-config'))

const BookDetailPage = lazy(() => import('@/pages/book/detail'))
const SearchPage = lazy(() => import('@/pages/search'))
const ReaderPage = lazy(() => import('@/pages/reader'))
const ReadingHistoryPage = lazy(() => import('@/pages/profile/history'))
const ReviewsPage = lazy(() => import('@/pages/reviews'))
const RecommendPage = lazy(() => import('@/pages/home/recommend'))
const NotificationsPage = lazy(() => import('@/pages/notifications'))
const UserProfilePage = lazy(() => import('@/pages/user/profile'))
const FollowListPage = lazy(() => import('@/pages/follow/list'))
const ChatListPage = lazy(() => import('@/pages/chat'))
const ChatRoomPage = lazy(() => import('@/pages/chat/room'))
const NotFoundPage = lazy(() => import('@/pages/not-found'))

function LazyLoad({ children }: { children: React.ReactNode }) {
  return (
    <Suspense
      fallback={
        <div className="flex h-screen items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
        </div>
      }
    >
      {children}
    </Suspense>
  )
}

export const router = createBrowserRouter([
  {
    path: '/',
    children: [
      {
        element: (
          <AuthGuard>
            <AppLayout />
          </AuthGuard>
        ),
        errorElement: <RouteErrorBoundary />,
        children: [
          { index: true, element: <LazyLoad><HomePage /></LazyLoad> },
          { path: ROUTES.HOME, element: <LazyLoad><HomePage /></LazyLoad> },
          { path: ROUTES.RANK, element: <LazyLoad><RankPage /></LazyLoad> },
          { path: ROUTES.AI, element: <LazyLoad><AIPage /></LazyLoad> },
          { path: ROUTES.BOOKSHELF, element: <LazyLoad><BookshelfPage /></LazyLoad> },
          { path: ROUTES.PROFILE, element: <LazyLoad><ProfilePage /></LazyLoad> },
          { path: ROUTES.CHANGE_PASSWORD, element: <LazyLoad><ChangePasswordPage /></LazyLoad> },
        ],
      },
      {
        element: <BlankLayout />,
        errorElement: <RouteErrorBoundary />,
        children: [
          {
            path: ROUTES.LOGIN,
            element: <GuestGuard><LazyLoad><LoginPage /></LazyLoad></GuestGuard>,
          },
          {
            path: ROUTES.REGISTER,
            element: <GuestGuard><LazyLoad><RegisterPage /></LazyLoad></GuestGuard>,
          },
          {
            path: ROUTES.RESET_PASSWORD,
            element: <LazyLoad><ResetPasswordPage /></LazyLoad>,
          },
          {
            path: ROUTES.BOOK_DETAIL,
            element: <AuthGuard><LazyLoad><BookDetailPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.SEARCH,
            element: <AuthGuard><LazyLoad><SearchPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.HISTORY,
            element: <AuthGuard><LazyLoad><ReadingHistoryPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.REVIEWS,
            element: <AuthGuard><LazyLoad><ReviewsPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.RECOMMEND,
            element: <AuthGuard><LazyLoad><RecommendPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.NOTIFICATIONS,
            element: <AuthGuard><LazyLoad><NotificationsPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.USER_PROFILE,
            element: <AuthGuard><LazyLoad><UserProfilePage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.FOLLOW_LIST + '/:tab?',
            element: <AuthGuard><LazyLoad><FollowListPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.CHAT,
            element: <AuthGuard><LazyLoad><ChatListPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.CHAT_ROOM,
            element: <AuthGuard><LazyLoad><ChatRoomPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.READER,
            element: <AuthGuard><LazyLoad><ReaderPage /></LazyLoad></AuthGuard>,
          },
          {
            path: ROUTES.ADMIN_REVIEW,
            element: <AdminGuard><LazyLoad><AdminReviewPage /></LazyLoad></AdminGuard>,
          },
          {
            path: ROUTES.ADMIN_BIND_EMAIL,
            element: <AdminGuard><LazyLoad><AdminBindEmailPage /></LazyLoad></AdminGuard>,
          },
          {
            path: ROUTES.ADMIN_BOOKS,
            element: <AdminGuard><LazyLoad><AdminBooksPage /></LazyLoad></AdminGuard>,
          },
          {
            path: ROUTES.ADMIN_AI_CONFIG,
            element: <AdminGuard><LazyLoad><AdminAiConfigPage /></LazyLoad></AdminGuard>,
          },
        ],
      },
      {
        path: '*',
        element: <LazyLoad><NotFoundPage /></LazyLoad>,
      },
    ],
  },
])
