import { useReaderStore } from '@/store/reader'
import { READER_THEMES } from '@/constants'
import type { EpubChapter } from '@/types/reader'
import React from "react";

interface EpubRendererProps {
  chapters: EpubChapter[]
  currentChapterIndex: number
  containerRef: React.RefObject<HTMLDivElement | null>
}

/**
 * EPUB 渲染器
 * 使用 epubjs 在 iframe 中渲染 EPUB 内容
 * 边距通过 iframe 内部 CSS 控制（epubjs continuous manager 不尊重外层 padding）
 */
export default function EpubRenderer({containerRef,
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
          touchAction: 'pan-y',
          WebkitOverflowScrolling: 'touch',
        }}
      />
    </div>
  )
}
