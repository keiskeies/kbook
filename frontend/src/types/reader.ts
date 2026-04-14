/** TXT 章节（按字符数分块） */
export interface TxtChapter {
  index: number
  title: string
  startOffset: number
  endOffset: number
}

/** TXT 渲染器状态 */
export interface TxtReaderState {
  /** 原始文本 */
  rawText: string
  /** 当前字符偏移 */
  charOffset: number
  /** 总字符数 */
  totalChars: number
  /** 分块章节列表 */
  chapters: TxtChapter[]
  /** 当前章节索引 */
  currentChapterIndex: number
  /** 编码 */
  encoding: string
}

/** EPUB 章节信息 */
export interface EpubChapter {
  id: string
  href: string
  title: string
  level: number
  parent: string | null
  order: number
}

/** EPUB 渲染器状态 */
export interface EpubReaderState {
  /** 当前章节 ID */
  currentChapterId: string
  /** 章节列表 */
  chapters: EpubChapter[]
  /** 当前章节内的滚动比例 (0~1) */
  scrollRatio: number
  /** 总进度 (0~1) */
  progress: number
}

/** PDF 渲染器状态 */
export interface PdfReaderState {
  /** 当前页码（1-based） */
  currentPage: number
  /** 总页数 */
  totalPages: number
  /** 渲染缩放 */
  scale: number
  /** 是否正在渲染 */
  rendering: boolean
}

/** 翻页方向 */
export type PageDirection = 'next' | 'prev'

/** 编码检测结果 */
export interface EncodingDetectResult {
  encoding: string
  confidence: number
}
