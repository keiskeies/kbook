import { useState } from 'react'
import { Minus, Plus, Sun, Moon, RotateCcw } from 'lucide-react'
import { useReaderStore } from '@/store/reader'
import { useTtsStore } from '@/store/tts'
import { READER_THEMES } from '@/constants'
import TtsSettingsPanel from './TtsSettingsPanel'

const FONT_OPTIONS = [
  { label: '系统', value: 'system-ui, -apple-system, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "WenQuanYi Micro Hei", sans-serif' },
  { label: '宋体', value: '"Songti SC", "STSong", "SimSun", "Noto Serif CJK SC", serif' },
  { label: '黑体', value: '"Heiti SC", "STHeiti", "SimHei", "PingFang SC", "Noto Sans CJK SC", sans-serif' },
  { label: '楷体', value: '"Kaiti SC", "STKaiti", "KaiTi", "Noto Serif CJK SC", serif' },
  { label: '仿宋', value: '"Fangsong SC", "STFangsong", "FangSong", "Noto Serif CJK SC", serif' },
]

const LINE_HEIGHT_OPTIONS = [
  { label: '紧凑', value: 1.4 },
  { label: '适中', value: 1.8 },
  { label: '宽松', value: 2.2 },
  { label: '舒朗', value: 2.6 },
  { label: '超宽', value: 3.0 },
]

const PARAGRAPH_SPACING_OPTIONS = [
  { label: '紧凑', value: 8 },
  { label: '标准', value: 16 },
  { label: '宽松', value: 24 },
  { label: '疏朗', value: 32 },
  { label: '超宽', value: 40 },
]

const PAGE_MARGIN_OPTIONS = [
  { label: '无', value: 0 },
  { label: '窄', value: 12 },
  { label: '中', value: 24 },
  { label: '宽', value: 40 },
  { label: '超宽', value: 60 },
]

const ANIMATION_OPTIONS = [
  { label: '无', value: 'none' },
  { label: '滑动', value: 'slide' },
  { label: '淡入', value: 'fade' },
]

const FONT_SIZE_MIN = 12
const FONT_SIZE_MAX = 32
const FONT_SIZE_STEP = 1

/** 行高可视化图标：3 条线 + 不同的线间距 */
function LineHeightIcon({ ratio }: { ratio: number }) {
  const gap = Math.round(ratio * 3)
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" className="shrink-0">
      <line x1="3" y1={10 - gap} x2="17" y2={10 - gap} stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      <line x1="3" y1="10" x2="17" y2="10" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      <line x1="3" y1={10 + gap} x2="17" y2={10 + gap} stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

/** 段落间距可视化图标：3 个短条 + 不同的条间距 */
function ParagraphSpacingIcon({ ratio }: { ratio: number }) {
  const gap = Math.min(Math.round(ratio * 1.2), 6)
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" className="shrink-0">
      <rect x="3" y="2" width="14" height="3" rx="1" fill="currentColor" opacity="0.85" />
      <rect x="3" y={5 + gap} width="14" height="3" rx="1" fill="currentColor" opacity="0.85" />
      <rect x="3" y={8 + gap * 2} width="14" height="3" rx="1" fill="currentColor" opacity="0.85" />
    </svg>
  )
}

/** 页面边距可视化图标：矩形内的内边距 */
function MarginIcon({ padding }: { padding: number }) {
  const p = Math.min(Math.round(padding * 0.25), 5)
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" className="shrink-0">
      <rect x="1" y="1" width="18" height="18" rx="2" stroke="currentColor" strokeWidth="1.2" opacity="0.4" />
      <rect x={1 + p} y={1 + p} width={18 - p * 2} height={18 - p * 2} rx="1" fill="currentColor" opacity="0.6" />
    </svg>
  )
}

export default function SettingsPanel({ isSystemDark }: { isSystemDark?: boolean }) {
  const { settings, updateSettings, resetSettings, toggleSettings } = useReaderStore()
  const [tab, setTab] = useState<'style' | 'tts' | 'advanced'>('style')

  useTtsStore()

  const themeEntries = Object.entries(READER_THEMES) as [keyof typeof READER_THEMES, typeof READER_THEMES[keyof typeof READER_THEMES]][]

  const handleFontSizeChange = (delta: number) => {
    const next = Math.min(FONT_SIZE_MAX, Math.max(FONT_SIZE_MIN, settings.fontSize + delta))
    if (next !== settings.fontSize) updateSettings({ fontSize: next })
  }

  /** 通用选项按钮组 */
  const OptionButtons = <T extends number>({
    options,
    current,
    onChange,
    iconFn,
  }: {
    options: { label: string; value: T }[]
    current: T
    onChange: (v: T) => void
    iconFn: (opt: { label: string; value: T }) => React.ReactNode
  }) => (
    <div className="flex gap-1.5">
      {options.map((opt) => {
        const active = current === opt.value
        return (
          <button
            key={String(opt.value)}
            onClick={() => onChange(opt.value)}
            className={`flex flex-1 flex-col items-center gap-1 rounded-xl py-2 px-1 transition-all ${
              active
                ? 'bg-primary text-primary-foreground shadow-sm'
                : 'bg-muted text-foreground hover:bg-muted/80'
            }`}
          >
            {iconFn(opt)}
            <span className="text-[10px] leading-tight font-medium">{opt.label}</span>
          </button>
        )
      })}
    </div>
  )

  return (
    <div className="fixed inset-0 z-50" onClick={toggleSettings}>
      <div className="absolute inset-0 bg-black/30" />
      <div
        className="absolute inset-x-0 bottom-0 flex max-h-[75vh] flex-col rounded-t-2xl bg-card shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* 顶部拖动条 */}
        <div className="flex shrink-0 justify-center py-2">
          <div className="h-1 w-10 rounded-full bg-muted-foreground/30" />
        </div>

        {/* Tab 栏 — 固定不随内容滚动 */}
        <div className="flex shrink-0 border-b px-4 bg-card">
          {(['style', 'tts', 'advanced'] as const).map((t) => (
            <button
              key={t}
              onClick={() => setTab(t)}
              className={`flex-1 py-2.5 text-center text-sm font-medium transition-colors ${
                tab === t ? 'border-b-2 border-primary text-primary' : 'text-muted-foreground'
              }`}
            >
              {t === 'style' ? '排版' : t === 'tts' ? '朗读' : '高级'}
            </button>
          ))}
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto overscroll-y-contain">
        <div className="space-y-5 p-4">
          {/* ===== 排版 Tab ===== */}
          {tab === 'style' && (
            <>
              {/* 主题选择 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">阅读主题</label>
                {isSystemDark ? (
                  <div className="flex items-center gap-2 rounded-lg bg-muted px-3 py-2.5 text-xs text-muted-foreground">
                    <Moon className="h-3.5 w-3.5" />
                    已跟随系统夜间模式
                  </div>
                ) : (
                  <div className="grid grid-cols-4 gap-2.5">
                    {themeEntries.map(([key, theme]) => (
                      <button
                        key={key}
                        onClick={() => updateSettings({ themeKey: key })}
                        className={`flex flex-col items-center gap-1 rounded-xl py-2.5 px-1 transition-all ${
                          settings.themeKey === key ? 'ring-2 ring-primary ring-offset-1' : 'hover:opacity-80'
                        }`}
                        style={{ backgroundColor: theme.bg }}
                      >
                        <span className="text-sm font-medium" style={{ color: theme.fg }}>Aa</span>
                        <span className="text-[10px] leading-tight" style={{ color: theme.fg }}>{theme.name}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {/* 亮度 */}
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <label className="text-xs font-medium text-muted-foreground flex items-center gap-1.5">
                    <Sun className="h-3.5 w-3.5" />
                    亮度
                  </label>
                  <span className="text-xs font-medium tabular-nums">{Math.round(settings.brightness * 100)}%</span>
                </div>
                <input
                  type="range"
                  min={0.3}
                  max={1}
                  step={0.05}
                  value={settings.brightness}
                  onChange={(e) => updateSettings({ brightness: Number(e.target.value) })}
                  className="w-full accent-primary h-1.5"
                />
              </div>

              {/* 字体选择 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">字体</label>
                <div className="flex flex-wrap gap-1.5">
                  {FONT_OPTIONS.map((opt) => (
                    <button
                      key={opt.value}
                      onClick={() => updateSettings({ fontFamily: opt.value })}
                      className={`rounded-lg px-3 py-1.5 text-xs transition-colors ${
                        settings.fontFamily === opt.value
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted text-foreground hover:bg-muted/80'
                      }`}
                      style={{ fontFamily: opt.value }}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* 字号 — 加减按钮 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">字号</label>
                <div className="flex items-center gap-3">
                  <button
                    onClick={() => handleFontSizeChange(-FONT_SIZE_STEP)}
                    disabled={settings.fontSize <= FONT_SIZE_MIN}
                    className="flex h-9 w-9 items-center justify-center rounded-xl bg-muted text-foreground hover:bg-muted/80 disabled:opacity-30 transition-colors"
                  >
                    <Minus className="h-4 w-4" />
                  </button>
                  <span className="w-12 text-center text-sm font-semibold tabular-nums">{settings.fontSize}<span className="text-[10px] font-normal text-muted-foreground ml-0.5">px</span></span>
                  <button
                    onClick={() => handleFontSizeChange(FONT_SIZE_STEP)}
                    disabled={settings.fontSize >= FONT_SIZE_MAX}
                    className="flex h-9 w-9 items-center justify-center rounded-xl bg-muted text-foreground hover:bg-muted/80 disabled:opacity-30 transition-colors"
                  >
                    <Plus className="h-4 w-4" />
                  </button>
                </div>
              </div>

              {/* 行高 — 图标按钮 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">行高</label>
                <OptionButtons
                  options={LINE_HEIGHT_OPTIONS}
                  current={settings.lineHeight}
                  onChange={(v) => updateSettings({ lineHeight: v })}
                  iconFn={(opt) => <LineHeightIcon ratio={opt.value} />}
                />
              </div>

              {/* 段落间距 — 图标按钮 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">段落间距</label>
                <OptionButtons
                  options={PARAGRAPH_SPACING_OPTIONS}
                  current={settings.paragraphSpacing}
                  onChange={(v) => updateSettings({ paragraphSpacing: v })}
                  iconFn={(opt) => <ParagraphSpacingIcon ratio={opt.value} />}
                />
              </div>

              {/* 页面边距 — 图标按钮 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">页面边距</label>
                <OptionButtons
                  options={PAGE_MARGIN_OPTIONS}
                  current={settings.pageMargin}
                  onChange={(v) => updateSettings({ pageMargin: v })}
                  iconFn={(opt) => <MarginIcon padding={opt.value} />}
                />
              </div>
            </>
          )}

          {/* ===== 朗读 Tab ===== */}
          {tab === 'tts' && (
            <TtsSettingsPanel />
          )}

          {/* ===== 高级 Tab ===== */}
          {tab === 'advanced' && (
            <>
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">翻页动画</label>
                <div className="flex gap-2">
                  {ANIMATION_OPTIONS.map((opt) => (
                    <button
                      key={opt.value}
                      onClick={() => updateSettings({ pageAnimation: opt.value })}
                      className={`rounded-lg px-4 py-2 text-xs transition-colors ${
                        settings.pageAnimation === opt.value
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted text-foreground'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <div className="mb-2 flex items-center justify-between">
                  <label className="text-xs font-medium text-muted-foreground">自动滚动</label>
                  <span className="text-xs font-medium tabular-nums">
                    {settings.autoScrollSpeed === 0 ? '关闭' : `${settings.autoScrollSpeed} px/s`}
                  </span>
                </div>
                <input
                  type="range"
                  min={0}
                  max={200}
                  step={10}
                  value={settings.autoScrollSpeed}
                  onChange={(e) => updateSettings({ autoScrollSpeed: Number(e.target.value) })}
                  className="w-full accent-primary h-1.5"
                />
              </div>

              <div>
                <div className="mb-2 flex items-center justify-between">
                  <label className="text-xs font-medium text-muted-foreground">PDF 缩放</label>
                  <span className="text-xs font-medium tabular-nums">{settings.pdfScale.toFixed(1)}x</span>
                </div>
                <input
                  type="range"
                  min={0.5}
                  max={3}
                  step={0.25}
                  value={settings.pdfScale}
                  onChange={(e) => updateSettings({ pdfScale: Number(e.target.value) })}
                  className="w-full accent-primary h-1.5"
                />
              </div>

              <button
                onClick={resetSettings}
                className="flex w-full items-center justify-center gap-1.5 rounded-xl bg-muted py-2.5 text-xs font-medium text-foreground hover:bg-muted/80 transition-colors"
              >
                <RotateCcw className="h-3.5 w-3.5" />
                恢复默认设置
              </button>
            </>
          )}
        </div>
        </div>
      </div>
    </div>
  )
}
