import { useState, useEffect, useCallback, useRef } from 'react'
import { ArrowLeft, Save, Trash2, Plus, Star, StarOff, Pencil, X, Volume2, Cpu, Mic, Zap } from 'lucide-react'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { toast } from 'sonner'
import {
  listTtsConfigs,
  createTtsConfig,
  updateTtsConfig,
  deleteTtsConfig,
  switchDefaultTtsConfig,
  listGptSovitsVoices,
  type TtsConfig,
  type GptSovitsVoicePreset,
} from '@/api/adminTts'

const PROVIDER_OPTIONS: { value: string; label: string; type: string }[] = [
  { value: 'XIAOMI', label: '小米 AI TTS', type: 'LLM' },
  { value: 'IFLYTEK', label: '科大讯飞', type: 'TRADITIONAL' },
  { value: 'GPT_SOVITS', label: 'GPT-SoVITS', type: 'CLONE' },
]

export default function TtsConfigPage() {
  const goBack = useGoBack()
  const [configs, setConfigs] = useState<TtsConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [isCustomVoice, setIsCustomVoice] = useState(false)
  const [gptSovitsVoices, setGptSovitsVoices] = useState<GptSovitsVoicePreset[]>([])
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  const xiaomiVoices = [
    { id: 'mimo_default', label: 'MiMo-默认（冰糖/Mia）', lang: '混合', gender: '-' },
    { id: '冰糖', label: '冰糖', lang: '中文', gender: '女性' },
    { id: '茉莉', label: '茉莉', lang: '中文', gender: '女性' },
    { id: '苏打', label: '苏打', lang: '中文', gender: '男性' },
    { id: '白桦', label: '白桦', lang: '中文', gender: '男性' },
    { id: 'Mia', label: 'Mia', lang: '英文', gender: '女性' },
    { id: 'Chloe', label: 'Chloe', lang: '英文', gender: '女性' },
    { id: 'Milo', label: 'Milo', lang: '英文', gender: '男性' },
    { id: 'Dean', label: 'Dean', lang: '英文', gender: '男性' },
  ]

  const [form, setForm] = useState<TtsConfig>({
    name: '',
    ttsType: 'LLM',
    provider: 'XIAOMI',
    baseUrl: '',
    modelName: 'mimo-v2.5-tts',
    apiKey: '',
    apiSecret: '',
    appId: '',
    voice: '',
    language: 'zh',
    speed: 50,
    pitch: 50,
    enabled: true,
    isDefault: false,
    streaming: false,
  })

  const loadConfigs = useCallback(async () => {
    try {
      setLoading(true)
      const data = await listTtsConfigs()
      setConfigs(Array.isArray(data) ? data : [])
    } catch (err: any) {
      toast.error(err.message || '加载配置失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadConfigs()
  }, [loadConfigs])

  const resetForm = () => {
    setForm({
      name: '',
      ttsType: 'LLM',
      provider: 'XIAOMI',
      baseUrl: '',
      modelName: 'mimo-v2.5-tts',
      apiKey: '',
      apiSecret: '',
      appId: '',
      voice: '',
      language: 'zh',
      speed: 50,
      pitch: 50,
      enabled: true,
      isDefault: false,
      streaming: false,
    })
    setIsCustomVoice(false)
    setEditingId(null)
  }

  const openCreateForm = () => {
    resetForm()
    setShowForm(true)
  }

  const openEditForm = (config: TtsConfig) => {
    setForm({ ...config })
    setEditingId(config.id ?? null)
    setShowForm(true)
  }

  const handleProviderChange = (provider: string) => {
    const opt = PROVIDER_OPTIONS.find((p) => p.value === provider)
    const ttsType = (opt?.type as 'LLM' | 'TRADITIONAL' | 'CLONE') || 'LLM'
    setForm((f) => ({
      ...f,
      provider: provider as TtsConfig['provider'],
      ttsType,
      baseUrl: provider === 'XIAOMI' ? 'https://api.xiaomimimo.com/v1' : provider === 'GPT_SOVITS' ? 'http://127.0.0.1:9880' : '',
      modelName: provider === 'XIAOMI' ? 'mimo-v2.5-tts' : '',
      apiKey: '',
      apiSecret: '',
      appId: '',
      voice: provider === 'XIAOMI' ? '冰糖' : 'xiaoyan',
      voicePresetId: provider === 'GPT_SOVITS' ? '' : undefined,
    }))
    if (provider === 'GPT_SOVITS' && gptSovitsVoices.length === 0) {
      listGptSovitsVoices().then(setGptSovitsVoices).catch(() => {})
    }
  }

  const handleSave = async () => {
    if (!form.name?.trim()) { toast.error('请输入配置名称'); return }
    if (form.provider === 'XIAOMI' && !form.apiKey?.trim()) { toast.error('请输入 API Key'); return }
    if (form.provider === 'IFLYTEK' && (!form.appId?.trim() || !form.apiKey?.trim() || !form.apiSecret?.trim())) {
      toast.error('科大讯飞需填写 AppId、API Key 和 API Secret'); return
    }
    if (form.provider === 'GPT_SOVITS' && !form.voicePresetId?.trim()) {
      toast.error('请选择音色预设'); return
    }

    try {
      const payload = {
        ...form,
        apiKey: form.apiKey?.trim() || undefined,
        apiSecret: form.apiSecret?.trim() || undefined,
        appId: form.appId?.trim() || undefined,
      }
      if (editingId) {
        await updateTtsConfig(editingId, payload)
        toast.success('配置已更新')
      } else {
        await createTtsConfig(payload)
        toast.success('配置已创建')
      }
      resetForm()
      setShowForm(false)
      loadConfigs()
    } catch (err: any) {
      toast.error(err.message || '保存失败')
    }
  }

  const handleDelete = async (id: number) => {
    const config = configs.find((c) => c.id === id)
    if (!config || !confirm(`确定删除「${config.name}」？`)) return
    try {
      await deleteTtsConfig(id)
      toast.success('配置已删除')
      if (editingId === id) { resetForm(); setShowForm(false) }
      loadConfigs()
    } catch (err: any) {
      toast.error(err.message || '删除失败')
    }
  }

  const handleSwitchDefault = async (id: number) => {
    try {
      await switchDefaultTtsConfig(id)
      toast.success('已切换默认配置')
      loadConfigs()
    } catch (err: any) {
      toast.error(err.message || '切换失败')
    }
  }

  const typeIcon = (t: string) => t === 'LLM' ? <Cpu className="h-3.5 w-3.5" /> : <Mic className="h-3.5 w-3.5" />
  const typeLabel = (t: string) => t === 'LLM' ? '大模型 TTS' : t === 'CLONE' ? '语音克隆 TTS' : '传统 TTS'
  const providerLabel = (p: string) => PROVIDER_OPTIONS.find((o) => o.value === p)?.label || p

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background overscroll-contain">
      <header className="shrink-0 flex items-center gap-3 border-b bg-background/95 px-4 md:px-6 lg:px-8 py-3 backdrop-blur supports-[backdrop-filter]:bg-background/60 z-20">
        <button onClick={() => goBack()} className="rounded-full p-1.5 active:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-h3 font-bold">TTS 配置管理</h1>
      </header>

      <main ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 md:px-6 lg:px-8 py-4 space-y-4">
        {/* 顶部朗读配置 - 全宽 */}
        <section className="rounded-xl bg-card p-4 shadow-xs">
          <div className="flex items-start gap-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10">
              <Volume2 className="h-4.5 w-4.5 text-primary" />
            </div>
            <div className="flex-1 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">朗读配置</p>
              <p className="mt-1">
                配置后台 TTS 后，朗读功能将使用后台合成语音。未配置时回退浏览器朗读。
              </p>
            </div>
          </div>
        </section>

        {/* PC两栏布局 */}
        <div className="md:grid md:grid-cols-2 md:gap-4 space-y-4 md:space-y-0">
          {/* 左栏：TTS配置 + 配置说明 */}
          <div className="space-y-4">
            <section className="rounded-xl bg-card shadow-xs">
              <div className="flex items-center justify-between border-b px-4 py-3">
                <h2 className="text-sm font-bold">TTS 配置</h2>
                <button
                  onClick={openCreateForm}
                  className="flex items-center gap-1 rounded-full bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90"
                >
                  <Plus className="h-3.5 w-3.5" />
                  新增
                </button>
              </div>

              {loading ? (
                <div className="px-4 py-8 text-center text-sm text-muted-foreground">加载中...</div>
              ) : configs.length === 0 ? (
                <div className="px-4 py-8 text-center text-sm text-muted-foreground">
                  暂无配置，点击上方「新增」添加
                </div>
              ) : (
                <div className="divide-y">
                  {configs.map((c) => (
                    <div key={c.id} className={`px-4 py-3 ${c.isDefault ? 'bg-primary/5' : ''}`}>
                      <div className="flex items-center gap-2">
                        <button
                          onClick={() => handleSwitchDefault(c.id!)}
                          className={`shrink-0 ${c.isDefault ? 'text-yellow-500' : 'text-muted-foreground hover:text-yellow-500'}`}
                          title={c.isDefault ? '当前激活' : '设为默认'}
                        >
                          {c.isDefault ? <Star className="h-5 w-5 fill-yellow-500" /> : <StarOff className="h-5 w-5" />}
                        </button>

                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-1.5">
                            <span className="font-medium text-sm truncate">{c.name}</span>
                            {c.isDefault && (
                              <span className="rounded bg-primary/20 px-1.5 py-0.5 text-xs font-medium text-primary">激活</span>
                            )}
                            {!c.enabled && (
                              <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/30 dark:text-red-400">已禁用</span>
                            )}
                          </div>
                          <p className="text-xs text-muted-foreground truncate flex items-center gap-1">
                            {typeIcon(c.ttsType)}
                            {typeLabel(c.ttsType)} / {providerLabel(c.provider)}
                            {c.voice && <span>· {c.voice}</span>}
                            {c.streaming && <span className="text-amber-500">· 流式</span>}
                          </p>
                        </div>

                        <div className="flex shrink-0 gap-1">
                          <button
                            onClick={() => openEditForm(c)}
                            className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                            title="编辑"
                          >
                            <Pencil className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => handleDelete(c.id!)}
                            className="rounded p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                            title="删除"
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <section className="rounded-xl bg-muted/30 p-4 text-xs text-muted-foreground space-y-1.5">
              <p className="font-medium text-foreground">配置说明</p>
              <p>· 大模型 TTS：如小米 AI TTS，使用大模型生成自然语音</p>
              <p>· 传统 TTS：如科大讯飞，使用传统语音合成引擎</p>
              <p>· 语音克隆 TTS：如 GPT-SoVITS，基于零样本语音克隆，需本地部署服务</p>
              <p>· 启用并设为默认后，朗读功能将使用后台合成语音</p>
              <p>· 后台 TTS 不可用时自动回退浏览器朗读</p>
            </section>
          </div>

          {/* 右栏：新增/编辑配置 */}
          <div>
            {showForm && (
              <section className="rounded-xl bg-card p-4 shadow-xs space-y-4">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-bold">{editingId ? '编辑配置' : '新增配置'}</h2>
                  <button onClick={() => { setShowForm(false); resetForm(); }} className="rounded p-1 text-muted-foreground hover:bg-muted">
                    <X className="h-4 w-4" />
                  </button>
                </div>

                <div>
                  <label className="mb-1.5 block text-sm font-medium">配置名称</label>
                  <input
                    type="text"
                    value={form.name || ''}
                    onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                    placeholder="如：小米 TTS、讯飞朗读"
                    className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                  />
                </div>

                <div>
                  <label className="mb-1.5 block text-sm font-medium">提供商</label>
                  <div className="grid grid-cols-2 gap-2">
                    {PROVIDER_OPTIONS.map((opt) => (
                      <button
                        key={opt.value}
                        onClick={() => handleProviderChange(opt.value)}
                        className={`flex items-center gap-2 rounded-lg border px-3 py-2.5 text-sm font-medium transition-colors ${
                          form.provider === opt.value
                            ? 'border-primary bg-primary/10 text-primary'
                            : 'border-border text-muted-foreground hover:bg-muted/50'
                        }`}
                      >
                        {opt.value === 'XIAOMI' ? <Cpu className="h-4 w-4" /> : opt.value === 'GPT_SOVITS' ? <Volume2 className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
                        <div className="text-left">
                          <div>{opt.label}</div>
                          <div className="text-xs opacity-60">{opt.type === 'LLM' ? '大模型 TTS' : opt.type === 'CLONE' ? '语音克隆 TTS' : '传统 TTS'}</div>
                        </div>
                      </button>
                    ))}
                  </div>
                </div>

                {form.provider === 'XIAOMI' && (
                  <>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">API 地址</label>
                      <input
                        type="text"
                        value={form.baseUrl || ''}
                        onChange={(e) => setForm((f) => ({ ...f, baseUrl: e.target.value }))}
                        placeholder="https://api.xiaomimimo.com/v1"
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">模型名称</label>
                      <input
                        type="text"
                        value={form.modelName || ''}
                        onChange={(e) => setForm((f) => ({ ...f, modelName: e.target.value }))}
                        placeholder="mimo-v2.5-tts"
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">API Key</label>
                      <input
                        type="text"
                        value={form.apiKey || ''}
                        onChange={(e) => setForm((f) => ({ ...f, apiKey: e.target.value }))}
                        placeholder="小米开放平台 API Key"
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">音色 (Voice)</label>
                      <select
                        value={isCustomVoice ? '__custom__' : (xiaomiVoices.some((v) => v.id === form.voice) ? (form.voice || '') : '__custom__')}
                        onChange={(e) => {
                          if (e.target.value === '__custom__') {
                            setIsCustomVoice(true)
                            setForm((f) => ({ ...f, voice: '' }))
                          } else {
                            setIsCustomVoice(false)
                            setForm((f) => ({ ...f, voice: e.target.value }))
                          }
                        }}
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      >
                        <option value="" disabled>选择音色</option>
                        {xiaomiVoices.map((v) => (
                          <option key={v.id} value={v.id}>{v.label}（{v.lang}·{v.gender}）</option>
                        ))}
                        <option value="__custom__">自定义音色...</option>
                      </select>
                      {isCustomVoice && (
                        <input
                          type="text"
                          value={form.voice || ''}
                          onChange={(e) => setForm((f) => ({ ...f, voice: e.target.value }))}
                          placeholder="输入音色 ID"
                          className="mt-2 w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                        />
                      )}
                      <p className="mt-1 text-xs text-muted-foreground">选择预置音色或自定义输入音色 ID</p>
                    </div>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Zap className="h-4 w-4 text-amber-500" />
                        <div>
                          <label className="text-sm font-medium">流式输出</label>
                          <p className="text-xs text-muted-foreground">边生成边播放，降低首字延迟</p>
                        </div>
                      </div>
                      <button
                        onClick={() => setForm((f) => ({ ...f, streaming: !f.streaming }))}
                        className={`relative h-6 w-11 rounded-full transition-colors ${form.streaming ? 'bg-amber-500' : 'bg-muted-foreground/30'}`}
                      >
                        <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${form.streaming ? 'left-[22px]' : 'left-0.5'}`} />
                      </button>
                    </div>
                  </>
                )}

                {form.provider === 'IFLYTEK' && (
                  <>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">AppId</label>
                      <input
                        type="text"
                        value={form.appId || ''}
                        onChange={(e) => setForm((f) => ({ ...f, appId: e.target.value }))}
                        placeholder="讯飞开放平台 AppId"
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">API Key</label>
                      <input
                        type="text"
                        value={form.apiKey || ''}
                        onChange={(e) => setForm((f) => ({ ...f, apiKey: e.target.value }))}
                        placeholder="讯飞 API Key"
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">API Secret</label>
                      <input
                        type="text"
                        value={form.apiSecret || ''}
                        onChange={(e) => setForm((f) => ({ ...f, apiSecret: e.target.value }))}
                        placeholder="讯飞 API Secret"
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">发音人</label>
                      <input
                        type="text"
                        value={form.voice || ''}
                        onChange={(e) => setForm((f) => ({ ...f, voice: e.target.value }))}
                        placeholder="xiaoyan"
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      />
                      <p className="mt-1 text-xs text-muted-foreground">讯飞发音人，如：xiaoyan（小燕）、aisjinger（小婧）等</p>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="mb-1.5 block text-sm font-medium">语速 ({form.speed ?? 50})</label>
                        <input type="range" min="0" max="100" value={form.speed ?? 50} onChange={(e) => setForm((f) => ({ ...f, speed: parseInt(e.target.value) }))} className="w-full accent-primary" />
                      </div>
                      <div>
                        <label className="mb-1.5 block text-sm font-medium">音调 ({form.pitch ?? 50})</label>
                        <input type="range" min="0" max="100" value={form.pitch ?? 50} onChange={(e) => setForm((f) => ({ ...f, pitch: parseInt(e.target.value) }))} className="w-full accent-primary" />
                      </div>
                    </div>
                  </>
                )}

                {form.provider === 'GPT_SOVITS' && (
                  <>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">API 地址</label>
                      <input
                        type="text"
                        value={form.baseUrl || ''}
                        onChange={(e) => setForm((f) => ({ ...f, baseUrl: e.target.value }))}
                        placeholder="http://127.0.0.1:9880"
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      />
                      <p className="mt-1 text-xs text-muted-foreground">GPT-SoVITS 本地服务地址</p>
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium">音色预设</label>
                      {gptSovitsVoices.length === 0 ? (
                        <p className="text-xs text-muted-foreground">加载音色预设中...</p>
                      ) : (
                        <div className="grid grid-cols-2 gap-2">
                          {gptSovitsVoices.map((v) => (
                            <button
                              key={v.id}
                              onClick={() => setForm((f) => ({ ...f, voicePresetId: v.id, voice: v.name }))}
                              className={`flex items-center gap-2 rounded-lg border px-3 py-2.5 text-sm font-medium transition-colors ${
                                form.voicePresetId === v.id
                                  ? 'border-primary bg-primary/10 text-primary'
                                  : 'border-border text-muted-foreground hover:bg-muted/50'
                              }`}
                            >
                              <Volume2 className="h-4 w-4" />
                              <div className="text-left">
                                <div>{v.name}</div>
                                <div className="text-xs opacity-60">{v.lang.toUpperCase()}</div>
                              </div>
                            </button>
                          ))}
                        </div>
                      )}
                      <p className="mt-1 text-xs text-muted-foreground">选择预设音色，音色配置来自 tts-voices.yml</p>
                    </div>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <Zap className="h-4 w-4 text-amber-500" />
                        <div>
                          <label className="text-sm font-medium">流式输出</label>
                          <p className="text-xs text-muted-foreground">边生成边播放，降低首字延迟</p>
                        </div>
                      </div>
                      <button
                        onClick={() => setForm((f) => ({ ...f, streaming: !f.streaming }))}
                        className={`relative h-6 w-11 rounded-full transition-colors ${form.streaming ? 'bg-amber-500' : 'bg-muted-foreground/30'}`}
                      >
                        <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${form.streaming ? 'left-[22px]' : 'left-0.5'}`} />
                      </button>
                    </div>
                  </>
                )}

                <div className="flex items-center justify-between">
                  <label className="text-sm font-medium">启用</label>
                  <button
                    onClick={() => setForm((f) => ({ ...f, enabled: !f.enabled }))}
                    className={`relative h-6 w-11 rounded-full transition-colors ${form.enabled ? 'bg-primary' : 'bg-muted-foreground/30'}`}
                  >
                    <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${form.enabled ? 'left-[22px]' : 'left-0.5'}`} />
                  </button>
                </div>

                <div className="flex gap-2 pt-2">
                  <button onClick={handleSave} className="flex flex-1 items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground">
                    <Save className="h-4 w-4" />
                    {editingId ? '更新' : '创建'}
                  </button>
                  <button onClick={() => { setShowForm(false); resetForm(); }} className="flex items-center gap-2 rounded-lg border border-border px-4 py-2.5 text-sm font-medium hover:bg-muted/50">
                    取消
                  </button>
                </div>
              </section>
            )}
          </div>
        </div>
      </main>
    </div>
  )
}
