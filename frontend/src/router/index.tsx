import { createBrowserRouter } from 'react-router-dom'
import { ROUTES } from '@/constants'
import { AppLayout, BlankLayout } from '@/components/layout/AppLayout'
import { AuthGuard, AdminGuard, GuestGuard } from '@/components/auth/AuthGuard'

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
  // 主应用布局（带 TabBar）
  {
    path: '/',
    element: (
      <AuthGuard>
        <AppLayout />
      </AuthGuard>
    ),
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
  // 全屏布局（无 TabBar）
  {
    path: '/',
    element: <BlankLayout />,
    children: [
      // 认证页面
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
      // 图书详情
      {
        path: ROUTES.BOOK_DETAIL,
        element: <AuthGuard><LazyLoad><BookDetailPage /></LazyLoad></AuthGuard>,
      },
      // 搜索
      {
        path: ROUTES.SEARCH,
        element: <AuthGuard><LazyLoad><SearchPage /></LazyLoad></AuthGuard>,
      },
      // 阅读历史
      {
        path: ROUTES.HISTORY,
        element: <AuthGuard><LazyLoad><ReadingHistoryPage /></LazyLoad></AuthGuard>,
      },
      // 高分书评
      {
        path: ROUTES.REVIEWS,
        element: <AuthGuard><LazyLoad><ReviewsPage /></LazyLoad></AuthGuard>,
      },
      // 为你推荐
      {
        path: ROUTES.RECOMMEND,
        element: <AuthGuard><LazyLoad><RecommendPage /></LazyLoad></AuthGuard>,
      },
      // 通知
      {
        path: ROUTES.NOTIFICATIONS,
        element: <AuthGuard><LazyLoad><NotificationsPage /></LazyLoad></AuthGuard>,
      },
      // 用户主页
      {
        path: ROUTES.USER_PROFILE,
        element: <AuthGuard><LazyLoad><UserProfilePage /></LazyLoad></AuthGuard>,
      },
      // 关注/粉丝列表
      {
        path: ROUTES.FOLLOW_LIST + '/:tab?',
        element: <AuthGuard><LazyLoad><FollowListPage /></LazyLoad></AuthGuard>,
      },
      // 阅读器
      {
        path: ROUTES.READER,
        element: <AuthGuard><LazyLoad><ReaderPage /></LazyLoad></AuthGuard>,
      },
      // 管理员页面
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
  // 404 - 兜底路由
  {
    path: '*',
    element: <LazyLoad><NotFoundPage /></LazyLoad>,
  },
], {
  scrollRestoration: false,
} as any)
