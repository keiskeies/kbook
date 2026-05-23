/** 格式化相对时间 */
export function formatRelativeTime(dateStr: string | null): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin}分钟前`
  const diffHour = Math.floor(diffMin / 60)
  if (diffHour < 24) return `${diffHour}小时前`
  const diffDay = Math.floor(diffHour / 24)
  if (diffDay < 7) return `${diffDay}天前`
  if (diffDay < 30) return `${Math.floor(diffDay / 7)}周前`
  return date.toLocaleDateString('zh-CN')
}

/** 格式化聊天消息时间 */
export function formatChatTime(dateStr: string | null): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const time = date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  const isToday = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate()
  if (isToday) return time
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  const isYesterday = date.getFullYear() === yesterday.getFullYear()
    && date.getMonth() === yesterday.getMonth()
    && date.getDate() === yesterday.getDate()
  if (isYesterday) return `昨天 ${time}`
  const dayBefore = new Date(now)
  dayBefore.setDate(now.getDate() - 2)
  const isDayBefore = date.getFullYear() === dayBefore.getFullYear()
    && date.getMonth() === dayBefore.getMonth()
    && date.getDate() === dayBefore.getDate()
  if (isDayBefore) return `前天 ${time}`
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d} ${time}`
}

/** 格式化文件类型标签 */
export function formatTag(fmt: string): string {
  const map: Record<string, string> = { TXT: '文本', EPUB: '电子书', PDF: '文档' }
  return map[fmt] || fmt
}
