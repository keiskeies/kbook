import { useMemo, useEffect, useRef } from 'react'
import { useReaderStore } from '@/store/reader'
import { READER_THEMES } from '@/constants'
import type { TxtChapter } from '@/types/reader'

interface TxtRendererProps {
  text: string
  chapters: TxtChapter[]
  currentChapterIndex: number
  containerRef: React.RefObject<HTMLDivElement>
  onScroll: () => void
  /** TTS 当前朗读段索引，-1 表示未朗读 */
  ttsSegmentIndex?: number
}

export default function TxtRenderer({
  text, chapters, currentChapterIndex, containerRef, onScroll, ttsSegmentIndex = -1,
}: TxtRendererProps) {
  const { settings, isSystemDark } = useReaderStore()
  const theme = READER_THEMES[isSystemDark ? 'DARK' : settings.themeKey]

  const paragraphs = useMemo(() => {
    return text.split(/\n+/).filter((p) => p.trim())
  }, [text])

  // TTS 高亮段落自动滚动
  const paragraphRefs = useRef<(HTMLParagraphElement | null)[]>([])
  useEffect(() => {
    if (ttsSegmentIndex < 0) return
    const el = paragraphRefs.current[ttsSegmentIndex]
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }, [ttsSegmentIndex])

  return (
    <div
      ref={containerRef}
      className="h-full overflow-y-auto overflow-x-hidden"
      onScroll={onScroll}
      style={{
        backgroundColor: theme.bg,
        color: theme.fg,
        filter: `brightness(${settings.brightness})`,
      }}
    >
      <div
        className="mx-auto pt-2 pb-6"
        style={{
          fontFamily: settings.fontFamily,
          fontSize: `${settings.fontSize}px`,
          lineHeight: settings.lineHeight,
          paddingLeft: `${settings.pageMargin}px`,
          paddingRight: `${settings.pageMargin}px`,
          WebkitUserSelect: 'none',
          userSelect: 'none',
        }}
        onContextMenu={(e) => e.preventDefault()}
      >
        {paragraphs.map((p, i) => {
          const isHighlight = i === ttsSegmentIndex
          return (
            <p
              key={i}
              ref={(el) => { paragraphRefs.current[i] = el }}
              className="text-justify transition-colors duration-300 rounded"
              style={{
                marginBottom: `${settings.paragraphSpacing}px`,
                backgroundColor: isHighlight
                  ? (isSystemDark ? 'rgba(59,130,246,0.15)' : 'rgba(59,130,246,0.1)')
                  : 'transparent',
                paddingLeft: isHighlight ? '8px' : undefined,
                paddingRight: isHighlight ? '8px' : undefined,
                borderLeft: isHighlight ? '3px solid #3b82f6' : undefined,
              }}
            >
              {p}
            </p>
          )
        })}
      </div>
    </div>
  )
}
