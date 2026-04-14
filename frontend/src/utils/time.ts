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

/** 格式化文件类型标签 */
export function formatTag(fmt: string): string {
  const map: Record<string, string> = { TXT: '文本', EPUB: '电子书', PDF: '文档' }
  return map[fmt] || fmt
}
