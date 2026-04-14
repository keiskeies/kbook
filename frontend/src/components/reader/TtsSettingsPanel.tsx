import { useTtsStore } from '@/store/tts'
import type { TtsVoice } from '@/store/tts'
import { Volume2, Gauge } from 'lucide-react'

/**
 * TTS 设置面板
 * 用于选择音色和调节语速
 */
export default function TtsSettingsPanel() {
  const { settings, voices, updateSettings } = useTtsStore()

  // 按语言分组
  const groupedVoices = voices.reduce<Record<string, TtsVoice[]>>((acc, v) => {
    const lang = v.lang.split('-')[0]
    acc[lang] = acc[lang] || []
    acc[lang].push(v)
    return acc
  }, {})

  // 中文优先排序
  const sortedLangs = Object.keys(groupedVoices).sort((a, b) => {
    if (a === 'zh') return -1
    if (b === 'zh') return 1
    return a.localeCompare(b)
  })

  const langLabels: Record<string, string> = {
    zh: '中文',
    en: 'English',
    ja: '日本語',
    ko: '한국어',
    fr: 'Français',
    de: 'Deutsch',
    es: 'Español',
    ru: 'Русский',
  }

  return (
    <div className="space-y-4">
      {/* 语速 */}
      <div>
        <label className="flex items-center gap-1.5 text-sm font-medium mb-2">
          <Gauge className="h-4 w-4" />
          语速
          <span className="text-muted-foreground text-xs">({settings.rate.toFixed(1)}x)</span>
        </label>
        <div className="flex items-center gap-3">
          <span className="text-xs text-muted-foreground">0.5x</span>
          <input
            type="range"
            min={0.5}
            max={2}
            step={0.1}
            value={settings.rate}
            onChange={(e) => updateSettings({ rate: parseFloat(e.target.value) })}
            className="flex-1 accent-primary"
          />
          <span className="text-xs text-muted-foreground">2.0x</span>
        </div>
      </div>

      {/* 音色选择 */}
      <div>
        <label className="flex items-center gap-1.5 text-sm font-medium mb-2">
          <Volume2 className="h-4 w-4" />
          音色
        </label>
        {voices.length === 0 ? (
          <p className="text-xs text-muted-foreground">未检测到可用音色，请使用支持语音合成的浏览器</p>
        ) : (
          <select
            value={settings.voiceURI || ''}
            onChange={(e) => updateSettings({ voiceURI: e.target.value || null })}
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
          >
            <option value="">默认音色</option>
            {sortedLangs.map((lang) => (
              <optgroup key={lang} label={langLabels[lang] || lang.toUpperCase()}>
                {groupedVoices[lang].map((v) => (
                  <option key={v.voiceURI} value={v.voiceURI}>
                    {v.name}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        )}
      </div>
    </div>
  )
}
