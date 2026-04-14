/**
 * 编码检测工具
 * 支持检测 UTF-8 / GBK / GB2312 / Big5 等中文编码
 */

/** BOM 标记映射 */
const BOM_MAP: Array<{ bytes: number[]; encoding: string }> = [
  { bytes: [0xef, 0xbb, 0xbf], encoding: 'UTF-8' },
  { bytes: [0xff, 0xfe], encoding: 'UTF-16LE' },
  { bytes: [0xfe, 0xff], encoding: 'UTF-16BE' },
]

/**
 * 检测 ArrayBuffer 的文本编码
 */
export function detectEncoding(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer.slice(0, Math.min(buffer.byteLength, 1024)))

  // 检查 BOM
  for (const { bytes: bom, encoding } of BOM_MAP) {
    if (bytes.length >= bom.length && bom.every((b, i) => bytes[i] === b)) {
      return encoding
    }
  }

  // 尝试 UTF-8 解码
  try {
    const decoder = new TextDecoder('utf-8', { fatal: true })
    decoder.decode(buffer.slice(0, Math.min(buffer.byteLength, 10000)))
    return 'UTF-8'
  } catch {
    // 不是合法 UTF-8
  }

  // 简单启发式：检查是否包含 GBK 常见字节
  let gbkScore = 0
  for (let i = 0; i < bytes.length - 1; i++) {
    const b1 = bytes[i]
    const b2 = bytes[i + 1]
    // GBK 双字节范围：第一字节 0x81-0xFE，第二字节 0x40-0xFE
    if (b1 >= 0x81 && b1 <= 0xfe && b2 >= 0x40 && b2 <= 0xfe) {
      gbkScore++
    }
  }

  if (gbkScore > bytes.length * 0.02) {
    return 'GBK'
  }

  // 默认 UTF-8
  return 'UTF-8'
}

/**
 * 使用指定编码解码 ArrayBuffer
 */
export function decodeBuffer(buffer: ArrayBuffer, encoding: string): string {
  try {
    const decoder = new TextDecoder(encoding, { fatal: false })
    return decoder.decode(buffer)
  } catch {
    // 降级到 UTF-8
    return new TextDecoder('utf-8', { fatal: false }).decode(buffer)
  }
}

/**
 * 将文本按章节标题拆分
 * 识别常见章节格式：第X章、Chapter X、等
 */
export function splitChapters(text: string): Array<{ title: string; startOffset: number; endOffset: number }> {
  const chapterRegex = /^(第[零一二三四五六七八九十百千万\d]+[章节回卷集部篇]|[Cc]hapter\s+\d+|[Pp]art\s+\d+|卷[零一二三四五六七八九十百千万\d]+|序[章言幕]|楔子|尾声|番外)/gm

  const chapters: Array<{ title: string; startOffset: number; endOffset: number }> = []
  let match: RegExpExecArray | null

  while ((match = chapterRegex.exec(text)) !== null) {
    // 获取章节标题（取该行剩余内容）
    const lineEnd = text.indexOf('\n', match.index)
    const title = lineEnd > match.index
      ? text.slice(match.index, lineEnd).trim()
      : match[0]

    chapters.push({
      title,
      startOffset: match.index,
      endOffset: -1, // 后面填充
    })
  }

  // 无章节则按固定字符数分块
  if (chapters.length === 0) {
    const chunkSize = 50000
    const totalChunks = Math.ceil(text.length / chunkSize)
    for (let i = 0; i < totalChunks; i++) {
      chapters.push({
        title: `第 ${i + 1} 部分`,
        startOffset: i * chunkSize,
        endOffset: Math.min((i + 1) * chunkSize, text.length),
      })
    }
    return chapters
  }

  // 填充 endOffset
  for (let i = 0; i < chapters.length; i++) {
    chapters[i].endOffset = i < chapters.length - 1
      ? chapters[i + 1].startOffset
      : text.length
  }

  // 如果第一个章节之前有内容，添加"前言"
  if (chapters[0].startOffset > 0) {
    chapters.unshift({
      title: '前言',
      startOffset: 0,
      endOffset: chapters[0].startOffset,
    })
  }

  return chapters
}

/**
 * 获取指定偏移量所在的章节索引
 */
export function findChapterIndex(
  chapters: Array<{ startOffset: number; endOffset: number }>,
  offset: number
): number {
  for (let i = chapters.length - 1; i >= 0; i--) {
    if (offset >= chapters[i].startOffset) return i
  }
  return 0
}

/**
 * 计算文本进度
 */
export function calcTxtProgress(charOffset: number, totalChars: number): number {
  if (totalChars <= 0) return 0
  return Math.min(1, Math.max(0, charOffset / totalChars))
}
