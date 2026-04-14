import { useReaderStore } from '@/store/reader'
import { READER_THEMES } from '@/constants'
import type { EpubChapter } from '@/types/reader'

interface EpubRendererProps {
  chapters: EpubChapter[]
  currentChapterIndex: number
  containerRef: React.RefObject<HTMLDivElement>
}

/**
 * EPUB 渲染器
 * 使用 epubjs 在 iframe 中渲染 EPUB 内容
 * 触摸/点击事件由 useEpubReader 在 iframe 内部绑定
 */
export default function EpubRenderer({
  currentChapterIndex, containerRef,
}: EpubRendererProps) {
  const { settings, isSystemDark } = useReaderStore()
  const theme = READER_THEMES[isSystemDark ? 'DARK' : settings.themeKey]

  return (
    <div
      className="h-full relative"
      style={{
        backgroundColor: theme.bg,
        filter: `brightness(${settings.brightness})`,
      }}
    >
      <div
        ref={containerRef}
        className="h-full w-full"
        style={{
          overflow: 'auto',
          position: 'relative',
          touchAction: 'pan-y', // 允许垂直滚动，但保留点击事件
          WebkitOverflowScrolling: 'touch', // iOS 平滑滚动
        }}
      />
    </div>
  )
}
