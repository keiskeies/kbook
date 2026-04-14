import { useState } from 'react'
import { useReaderStore } from '@/store/reader'
import { useTtsStore } from '@/store/tts'
import { READER_THEMES } from '@/constants'
import TtsSettingsPanel from './TtsSettingsPanel'

const FONT_OPTIONS = [
  { label: '系统默认', value: 'system-ui, -apple-system, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "WenQuanYi Micro Hei", sans-serif' },
  { label: '宋体', value: '"Songti SC", "STSong", "SimSun", "Noto Serif CJK SC", serif' },
  { label: '黑体', value: '"Heiti SC", "STHeiti", "SimHei", "PingFang SC", "Noto Sans CJK SC", sans-serif' },
  { label: '楷体', value: '"Kaiti SC", "STKaiti", "KaiTi", "Noto Serif CJK SC", serif' },
  { label: '仿宋', value: '"Fangsong SC", "STFangsong", "FangSong", "Noto Serif CJK SC", serif' },
]

const ANIMATION_OPTIONS = [
  { label: '无', value: 'none' },
  { label: '滑动', value: 'slide' },
  { label: '淡入', value: 'fade' },
]

export default function SettingsPanel({ isSystemDark }: { isSystemDark?: boolean }) {
  const { settings, updateSettings, resetSettings, toggleSettings } = useReaderStore()
  const [tab, setTab] = useState<'font' | 'theme' | 'tts' | 'advanced'>('font')

  const { status } = useTtsStore()

  const themeEntries = Object.entries(READER_THEMES) as [keyof typeof READER_THEMES, typeof READER_THEMES[keyof typeof READER_THEMES]][]

  return (
    <div className="fixed inset-0 z-50" onClick={toggleSettings}>
      {/* 遮罩层 - 点击关闭 */}
      <div className="absolute inset-0 bg-black/30" />
      <div
        className="absolute inset-x-0 bottom-0 max-h-[70vh] overflow-y-auto rounded-t-2xl bg-card shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
      <div className="flex justify-center py-2">
        <div className="h-1 w-10 rounded-full bg-muted-foreground/30" />
      </div>

      <div className="flex border-b px-4">
        {(['font', 'theme', 'tts', 'advanced'] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`flex-1 py-2.5 text-center text-sm font-medium transition-colors ${
              tab === t ? 'border-b-2 border-primary text-primary' : 'text-muted-foreground'
            }`}
          >
            {t === 'font' ? '字体' : t === 'theme' ? '主题' : t === 'tts' ? '朗读' : '高级'}
          </button>
        ))}
      </div>

      <div className="space-y-5 p-4">
        {tab === 'font' && (
          <>
            <div>
              <label className="mb-2 block text-xs font-medium text-muted-foreground">字体</label>
              <div className="flex flex-wrap gap-2">
                {FONT_OPTIONS.map((opt) => (
                  <button
                    key={opt.value}
                    onClick={() => updateSettings({ fontFamily: opt.value })}
                    className={`rounded-lg px-3 py-2 text-xs transition-colors ${
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

            <SliderRow label="字号" value={settings.fontSize} min={12} max={32} step={1} unit="px"
              onChange={(v) => updateSettings({ fontSize: v })} />

            <SliderRow label="行高" value={settings.lineHeight} min={1.2} max={3} step={0.1} unit=""
              onChange={(v) => updateSettings({ lineHeight: v })} />

            <SliderRow label="段落间距" value={settings.paragraphSpacing} min={4} max={40} step={2} unit="px"
              onChange={(v) => updateSettings({ paragraphSpacing: v })} />
          </>
        )}

        {tab === 'theme' && (
          <>
            <div>
              <label className="mb-2 block text-xs font-medium text-muted-foreground">背景主题</label>
              {isSystemDark ? (
                <div className="rounded-lg bg-muted px-4 py-3 text-center text-xs text-muted-foreground">
                  已跟随系统夜间模式，当前为夜间主题
                </div>
              ) : (
                <div className="grid grid-cols-4 gap-3">
                  {themeEntries.map(([key, theme]) => (
                    <button
                      key={key}
                      onClick={() => updateSettings({ themeKey: key })}
                      className={`flex flex-col items-center gap-1.5 rounded-xl p-3 transition-all ${
                        settings.themeKey === key ? 'ring-2 ring-primary ring-offset-2' : 'hover:opacity-80'
                      }`}
                      style={{ backgroundColor: theme.bg }}
                    >
                      <span className="text-sm font-medium" style={{ color: theme.fg }}>Aa</span>
                      <span className="text-[10px]" style={{ color: theme.fg }}>{theme.name}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>

            <SliderRow label="亮度" value={settings.brightness} min={0.3} max={1} step={0.05} unit="%"
              onChange={(v) => updateSettings({ brightness: v })}
              formatValue={(v) => Math.round(v * 100)} />
          </>
        )}

        {tab === 'tts' && (
          <TtsSettingsPanel />
        )}

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

            <SliderRow label="自动滚动" value={settings.autoScrollSpeed} min={0} max={200} step={10} unit=" px/s"
              onChange={(v) => updateSettings({ autoScrollSpeed: v })}
              formatValue={(v) => v === 0 ? '关闭' : `${v} px/s`} />

            <SliderRow label="PDF 缩放" value={settings.pdfScale} min={0.5} max={3} step={0.25} unit="x"
              onChange={(v) => updateSettings({ pdfScale: v })}
              formatValue={(v) => `${v.toFixed(1)}x`} />

            <button
              onClick={resetSettings}
              className="w-full rounded-lg bg-muted py-2.5 text-xs font-medium text-foreground hover:bg-muted/80"
            >
              恢复默认设置
            </button>
          </>
        )}
      </div>
      </div>
    </div>
  )
}

function SliderRow({
  label, value, min, max, step, unit, onChange, formatValue,
}: {
  label: string
  value: number
  min: number
  max: number
  step: number
  unit: string
  onChange: (v: number) => void
  formatValue?: (v: number) => string
}) {
  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <label className="text-xs font-medium text-muted-foreground">{label}</label>
        <span className="text-xs font-medium">
          {formatValue ? formatValue(value) : `${value}${unit}`}
        </span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-full accent-primary"
      />
    </div>
  )
}
