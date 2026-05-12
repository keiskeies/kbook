// 路由路径
export const ROUTES = {
  HOME: '/home',
  RANK: '/rank',
  AI: '/ai',
  BOOKSHELF: '/bookshelf',
  PROFILE: '/profile',
  LOGIN: '/login',
  REGISTER: '/register',
  RESET_PASSWORD: '/login/reset-password',
  CHANGE_PASSWORD: '/change-password',
  READER: '/reader/:bookId',
  BOOK_DETAIL: '/book/:bookId',
  SEARCH: '/search',
  HISTORY: '/profile/history',
  RECOMMEND: '/recommend',
  REVIEWS: '/reviews',
  NOTIFICATIONS: '/notifications',
  USER_PROFILE: '/user/:userId',
  ADMIN: '/admin',
  ADMIN_REVIEW: '/admin/review',
  ADMIN_BIND_EMAIL: '/admin/bind-email',
  ADMIN_BOOKS: '/admin/books',
  ADMIN_AI_CONFIG: '/admin/ai-config',
  FOLLOW_LIST: '/user/:userId/follow',
} as const

// 用户状态
export const USER_STATUS = {
  PENDING: 'PENDING',
  APPROVED: 'APPROVED',
  BANNED: 'BANNED',
} as const

// 图书格式
export const BOOK_FORMAT = {
  TXT: 'TXT',
  EPUB: 'EPUB',
  PDF: 'PDF',
} as const

// 存储键名
export const STORAGE_KEYS = {
  TOKEN: import.meta.env.VITE_TOKEN_KEY || 'kbook_token',
  REFRESH_TOKEN: import.meta.env.VITE_REFRESH_TOKEN_KEY || 'kbook_refresh_token',
  READER_SETTINGS: 'kbook_reader_settings',
  USER_INFO: 'kbook_user_info',
  LOCAL_PROGRESS: 'kbook_local_progress',
  PENDING_PROGRESS: 'kbook_pending_progress',
  TTS_SETTINGS: 'kbook_tts_settings',
} as const

// 阅读主题
export const READER_THEMES = {
  LIGHT: { name: '日间', bg: '#ffffff', fg: '#333333' },
  SEPIA: { name: '护眼', bg: '#f5efdc', fg: '#5b4636' },
  GREEN: { name: '绿意', bg: '#cce8cf', fg: '#2d4a2e' },
  DARK: { name: '夜间', bg: '#1a1a1a', fg: '#cccccc' },
} as const

// 验证码场景
export const CODE_SCENE = {
  REGISTER: 'register',
  LOGIN: 'login',
  RESET: 'reset',
  BIND: 'bind',
} as const
