import type { TxtChapter, EpubChapter } from '@/types/reader'

interface TocPanelProps {
  chapters: TxtChapter[] | EpubChapter[]
  currentIndex: number
  onJump: (index: number) => void
  onClose: () => void
}

export default function TocPanel({ chapters, currentIndex, onJump, onClose }: TocPanelProps) {
  const getTitle = (ch: TxtChapter | EpubChapter): string => {
    if ('title' in ch) return ch.title
    return ''
  }

  return (
    <div className="fixed inset-0 z-50 flex">
      {/* 遮罩 */}
      <div className="flex-1 bg-black/40" onClick={onClose} />

      {/* 目录面板 */}
      <div className="w-72 overflow-y-auto overscroll-y-contain bg-card shadow-xl">
        <div className="sticky top-0 border-b bg-card px-4 py-3">
          <h3 className="text-sm font-semibold">目录</h3>
        </div>
        <div className="py-2">
          {chapters.map((ch, i) => (
            <button
              key={i}
              onClick={() => {
                onJump(i)
                onClose()
              }}
              className={`block w-full px-4 py-2.5 text-left text-sm transition-colors ${
                i === currentIndex
                  ? 'bg-primary/10 font-medium text-primary'
                  : 'text-foreground hover:bg-muted'
              }`}
              style={{ paddingLeft: `${16 + ('level' in ch ? (ch.level || 0) * 16 : 0)}px` }}
            >
              {getTitle(ch)}
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}
