/** 中文按字、英文按词粗略计数，用于展示笔记篇幅 */
export function countWords(text: string | null | undefined): number {
  if (!text) return 0
  const cjk = text.match(/[\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]/g)?.length ?? 0
  const words = text
    .replace(/[\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]/g, ' ')
    .match(/[A-Za-z0-9]+/g)?.length ?? 0
  return cjk + words
}

/** 转义正则特殊字符，供关键词高亮使用 */
export function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
