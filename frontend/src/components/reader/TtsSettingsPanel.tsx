import { useTtsStore } from '@/store/tts'
import type { TtsVoice } from '@/store/tts'
import { Volume2, Gauge, Cpu } from 'lucide-react'

const providerLabels: Record<string, string> = {
  XIAOMI: '小米 AI TTS',
  IFLYTEK: '科大讯飞',
}

export default function TtsSettingsPanel() {
  const { settings, voices, updateSettings, backendMode, backendConfig, setBackendMode } = useTtsStore()

  const groupedVoices = voices.reduce<Record<string, TtsVoice[]>>((acc, v) => {
    const lang = v.lang.split('-')[0]
    acc[lang] = acc[lang] || []
    acc[lang].push(v)
    return acc
  }, {})

  const sortedLangs = Object.keys(groupedVoices).sort((a, b) => {
    if (a === 'zh') return -1
    if (b === 'zh') return 1
    return a.localeCompare(b)
  })

  const langLabels: Record<string, string> = {
    zh: '中文', en: 'English', ja: '日本語', ko: '한국어',
    fr: 'Français', de: 'Deutsch', es: 'Español', ru: 'Русский',
  }

  return (
    <div className="space-y-4">
      {/* 后端 TTS 模式（如果已配置） */}
      {backendConfig && (
        <div className="rounded-lg border border-primary/20 bg-primary/5 p-3">
          <div className="flex items-center justify-between mb-2">
            <div className="flex items-center gap-2 text-sm font-medium">
              <Cpu className="h-4 w-4 text-primary" />
              后台 TTS
            </div>
            <button
              onClick={() => setBackendMode(!backendMode)}
              className={`relative h-5 w-9 rounded-full transition-colors ${backendMode ? 'bg-primary' : 'bg-muted-foreground/30'}`}
            >
              <span className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform ${backendMode ? 'left-[18px]' : 'left-0.5'}`} />
            </button>
          </div>
          <p className="text-xs text-muted-foreground">
            {providerLabels[backendConfig.provider] || backendConfig.provider}
            {backendConfig.voice && <span> · {backendConfig.voice}</span>}
          </p>
          <p className="text-[10px] text-muted-foreground mt-0.5">
            {backendMode ? '正在使用后台 TTS' : '后台 TTS 已配置但未启用'}
          </p>
        </div>
      )}

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

      {/* 浏览器音色选择（仅在非后端模式或后端模式关闭时可用） */}
      {!backendMode && (
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
                    <option key={v.voiceURI} value={v.voiceURI}>{v.name}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          )}
        </div>
      )}
    </div>
  )
}
