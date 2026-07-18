export const ROUTES = {
  HOME: '/home',
  PROFILE: '/profile',
  LOGIN: '/login',
  REGISTER: '/register',
  RESET_PASSWORD: '/login/reset-password',
  CHANGE_PASSWORD: '/change-password',
  BOOK_DETAIL: '/book/:bookId',
  SEARCH: '/search',
  DISCOVER: '/discover',
  READING_LIST: '/profile/reading-list',
  TRASH: '/profile/trash',
  RECOMMEND: '/recommend',
  ADMIN_DASHBOARD: '/admin/dashboard',
  ADMIN_REVIEW: '/admin/review',
  ADMIN_BIND_EMAIL: '/admin/bind-email',
  ADMIN_BOOKS: '/admin/books',
  ADMIN_AI_CONFIG: '/admin/ai-config',
  ADMIN_AI_SCENE: '/admin/ai-scene',
  ADMIN_TTS_CONFIG: '/admin/tts-config',
  ROUND_TABLE: '/book/:bookId/round-table',
  ROUND_TABLE_SESSION: '/book/:bookId/round-table/sessions/:sessionId',
  DEBATE: '/book/:bookId/debate',
  DEBATE_SESSION: '/book/:bookId/debate/sessions/:sessionId',
  TERMS: '/terms',
  PRIVACY: '/privacy',
} as const

export const USER_STATUS = {
  PENDING: 'PENDING',
  APPROVED: 'APPROVED',
  BANNED: 'BANNED',
} as const

export const BOOK_FORMAT = {
  TXT: 'TXT',
  EPUB: 'EPUB',
  PDF: 'PDF',
} as const

export const STORAGE_KEYS = {
  TOKEN: import.meta.env.VITE_TOKEN_KEY || 'kbook_token',
  REFRESH_TOKEN: import.meta.env.VITE_REFRESH_TOKEN_KEY || 'kbook_refresh_token',
  READER_SETTINGS: 'kbook_reader_settings',
  USER_INFO: 'kbook_user_info',
  LOCAL_PROGRESS: 'kbook_local_progress',
  PENDING_PROGRESS: 'kbook_pending_progress',
  TTS_SETTINGS: 'kbook_tts_settings',
} as const

export const READER_THEMES = {
  LIGHT: { name: '日间', bg: '#ffffff', fg: '#333333' },
  SEPIA: { name: '护眼', bg: '#f5efdc', fg: '#5b4636' },
  GREEN: { name: '绿意', bg: '#cce8cf', fg: '#2d4a2e' },
  DARK: { name: '夜间', bg: '#1a1a1a', fg: '#cccccc' },
} as const

export const CODE_SCENE = {
  REGISTER: 'register',
  LOGIN: 'login',
  RESET: 'reset',
  BIND: 'bind',
} as const