import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { ArrowLeft, Save, Trash2, RefreshCw, Zap, Eye, EyeOff, ExternalLink, ChevronDown, ChevronUp, Globe, MapPin, Plus, Star, StarOff, Pencil, X } from 'lucide-react'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { toast } from 'sonner'
import {
  listAiConfigsByPurpose,
  createAiConfig,
  updateAiConfig,
  deleteAiConfig,
  switchDefaultConfig,
  testAiConfig,
  fetchProviderPresets,
  type AiProviderConfig,
  type AiProviderPreset,
} from '@/api/aiConfig'

const CHAT_PURPOSE = 'CHAT'

/**
 * AI 供应商配置管理页
 * 支持多配置管理，可切换激活（默认）配置。
 */
export default function AiConfigPage() {
  const goBack = useGoBack()
  const [configs, setConfigs] = useState<AiProviderConfig[]>([])
  const [presets, setPresets] = useState<AiProviderPreset[]>([])
  const [loading, setLoading] = useState(true)
  const [showPresets, setShowPresets] = useState(false)
  const [regionFilter, setRegionFilter] = useState<'ALL' | 'CN' | 'GLOBAL'>('ALL')
  const [testing, setTesting] = useState<number | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  // 表单状态
  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [showApiKey, setShowApiKey] = useState(false)
  const [isCustomModel, setIsCustomModel] = useState(false)
  const [form, setForm] = useState<AiProviderConfig>({
    name: '',
    purpose: CHAT_PURPOSE,
    provider: 'OLLAMA',
    baseUrl: 'http://localhost:11434',
    modelName: '',
    apiKey: '',
    temperature: 0.7,
    timeout: 120,
    toolsEnabled: null,
    enabled: true,
    isDefault: false,
    ragTopK: 5,
  })

  const filteredPresets = useMemo(() => {
    if (regionFilter === 'ALL') return presets
    return presets.filter((p) => p.region === regionFilter)
  }, [regionFilter, presets])
  const loadPresets = useCallback(async () => {
    try {
      const data = await fetchProviderPresets()
      setPresets(Array.isArray(data) ? data : [])
    } catch {
      setPresets([])
    }
  }, [])
  const loadConfigs = useCallback(async () => {
    try {
      setLoading(true)
      const data = await listAiConfigsByPurpose(CHAT_PURPOSE)
      setConfigs(Array.isArray(data) ? data : [])
    } catch (err: any) {
      toast.error(err.message || '加载配置失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadPresets()
    loadConfigs()
  }, [loadPresets, loadConfigs])

  const resetForm = () => {
    setForm({
      name: '',
      purpose: CHAT_PURPOSE,
      provider: 'OLLAMA',
      baseUrl: 'http://localhost:11434',
      modelName: '',
      apiKey: '',
      temperature: 0.7,
      timeout: 120,
      toolsEnabled: null,
      enabled: true,
      isDefault: false,
      ragTopK: 5,
    })
    setEditingId(null)
    setShowApiKey(false)
    setIsCustomModel(false)
  }

  const openCreateForm = () => {
    resetForm()
    setShowForm(true)
  }

  const openEditForm = (config: AiProviderConfig) => {
    setForm({
      ...config,
    })
    setEditingId(config.id ?? null)
    const preset = presets.find((p) => p.baseUrl === config.baseUrl)
    const isCustom = preset
      ? !preset.models.some((m) => m.name === config.modelName)
      : !!config.modelName
    setIsCustomModel(isCustom)
    setShowForm(true)
  }

  const handleSelectPreset = (preset: AiProviderPreset) => {
    const firstModel = preset.models[0]
    setForm((f) => ({
      ...f,
      name: preset.name,
      provider: preset.provider,
      baseUrl: preset.baseUrl,
      modelName: firstModel?.name || '',
      apiKey: f.provider !== preset.provider ? '' : f.apiKey,
      maxTokens: firstModel?.maxTokens,
    }))
    setShowPresets(false)
    setIsCustomModel(false)
    toast.success(`已选择 ${preset.name}`, { description: '请填写名称和 API Key 后保存' })
  }

  const handleSave = async () => {
    if (!form.name?.trim()) {
      toast.error('请输入配置名称')
      return
    }
    if (!form.modelName.trim()) {
      toast.error('请输入模型名称')
      return
    }
    if (!form.baseUrl.trim()) {
      toast.error('请输入 API 地址')
      return
    }

      try {
        const payload = {
          ...form,
          apiKey: form.apiKey?.trim() || undefined,
        }
      if (editingId) {
        await updateAiConfig(editingId, payload)
        toast.success('配置已更新')
      } else {
        await createAiConfig(payload)
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
    if (!config) return
    if (!confirm(`确定删除「${config.name}」？`)) return

    try {
      await deleteAiConfig(id)
      toast.success('配置已删除')
      if (editingId === id) {
        resetForm()
        setShowForm(false)
      }
      loadConfigs()
    } catch (err: any) {
      toast.error(err.message || '删除失败')
    }
  }

  const handleSwitchDefault = async (id: number) => {
    try {
      await switchDefaultConfig(id)
      toast.success('已切换默认配置')
      loadConfigs()
    } catch (err: any) {
      toast.error(err.message || '切换失败')
    }
  }

  const handleTest = async (id: number) => {
    try {
      setTesting(id)
      const data = await testAiConfig(id)
      const result = typeof data === 'string' ? data : String(data || '')
      toast.success('连接测试成功', {
        description: result.length > 100 ? result.substring(0, 100) + '...' : result,
      })
    } catch (err: any) {
      toast.error('连接测试失败', {
        description: err.message || '未知错误',
      })
    } finally {
      setTesting(null)
    }
  }

  const currentPreset = useMemo(
    () => presets.find((p) => p.baseUrl === form.baseUrl),
    [form.baseUrl, presets]
  )
  configs.find((c) => c.isDefault);
  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background overscroll-contain">
      {/* Header */}
      <header className="shrink-0 flex items-center gap-3 border-b bg-background/95 px-4 md:px-6 lg:px-8 py-3 backdrop-blur supports-[backdrop-filter]:bg-background/60 z-20">
        <button onClick={() => goBack()} className="rounded-full p-1.5 active:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-h3 font-bold">AI 配置管理</h1>
      </header>

      <main ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 md:px-6 lg:px-8 py-4">
        {/* 说明卡片 — 全宽 */}
        <section className="rounded-xl bg-card p-4 shadow-xs">
          <div className="flex items-start gap-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10">
              <Zap className="h-4.5 w-4.5 text-primary" />
            </div>
            <div className="flex-1 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">多配置管理</p>
              <p className="mt-1">
                可添加多个 AI 配置，点击 <Star className="inline h-3.5 w-3.5" /> 切换激活配置。
                未配置或已禁用时自动回退到 yml 默认模型。
              </p>
            </div>
          </div>
        </section>

        {/* PC 两栏布局：左=配置列表，右=表单+说明 */}
        <div className="mt-4 md:grid md:grid-cols-2 md:gap-4 space-y-4 md:space-y-0">
          {/* 左栏：配置列表 */}
          <section className="rounded-xl bg-card shadow-xs">
            <div className="flex items-center justify-between border-b px-4 py-3">
              <h2 className="text-sm font-bold">对话 AI 配置</h2>
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
                      {/* 切换默认按钮 */}
                      <button
                        onClick={() => handleSwitchDefault(c.id!)}
                        className={`shrink-0 ${c.isDefault ? 'text-yellow-500' : 'text-muted-foreground hover:text-yellow-500'}`}
                        title={c.isDefault ? '当前激活' : '设为默认'}
                      >
                        {c.isDefault ? (
                          <Star className="h-5 w-5 fill-yellow-500" />
                        ) : (
                          <StarOff className="h-5 w-5" />
                        )}
                      </button>

                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-1.5">
                          <span className="font-medium text-sm truncate">{c.name}</span>
                          {c.isDefault && (
                            <span className="rounded bg-primary/20 px-1.5 py-0.5 text-xs font-medium text-primary">
                              激活
                            </span>
                          )}
                          {!c.enabled && (
                            <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/30 dark:text-red-400">
                              已禁用
                            </span>
                          )}
                        </div>
                        <p className="text-xs text-muted-foreground truncate">
                          {c.provider} / {c.modelName}
                        </p>
                      </div>

                      {/* 操作按钮 */}
                      <div className="flex shrink-0 gap-1">
                        <button
                          onClick={() => handleTest(c.id!)}
                          disabled={testing === c.id}
                          className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50"
                          title="测试连接"
                        >
                          <RefreshCw className={`h-4 w-4 ${testing === c.id ? 'animate-spin' : ''}`} />
                        </button>
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

          {/* 右栏：表单 + 说明 */}
          <div className="space-y-4">
            {/* 新增/编辑表单 */}
            {showForm && (
              <section className="rounded-xl bg-card p-4 shadow-xs space-y-4">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-bold">{editingId ? '编辑配置' : '新增配置'}</h2>
                  <button onClick={() => { setShowForm(false); resetForm(); }} className="rounded p-1 text-muted-foreground hover:bg-muted">
                    <X className="h-4 w-4" />
                  </button>
                </div>

                {/* 配置名称 */}
                <div>
                  <label className="mb-1.5 block text-sm font-medium">配置名称</label>
                  <input
                    type="text"
                    value={form.name || ''}
                    onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                    placeholder="如：DeepSeek-V4、Qwen3-Max"
                    className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                  />
                </div>

                {/* 供应商预设选择 */}
                <section className="rounded-lg bg-muted/30 p-3">
                  <button
                    onClick={() => setShowPresets(!showPresets)}
                    className="flex w-full items-center justify-between text-sm font-medium"
                  >
                    <span>选择 AI 供应商（快捷预设）</span>
                    {showPresets ? <ChevronUp className="h-4 w-4 text-muted-foreground" /> : <ChevronDown className="h-4 w-4 text-muted-foreground" />}
                  </button>

                  {showPresets && (
                    <div className="mt-3 space-y-3">
                      <div className="flex gap-1.5">
                        {[
                          { value: 'ALL' as const, label: '全部' },
                          { value: 'CN' as const, label: '国内', icon: MapPin },
                          { value: 'GLOBAL' as const, label: '国际', icon: Globe },
                        ].map((opt) => (
                          <button
                            key={opt.value}
                            onClick={() => setRegionFilter(opt.value)}
                            className={`flex items-center gap-1 rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                              regionFilter === opt.value
                                ? 'bg-primary text-primary-foreground'
                                : 'bg-muted text-muted-foreground hover:bg-muted/80'
                            }`}
                          >
                            {opt.icon && <opt.icon className="h-3 w-3" />}
                            {opt.label}
                          </button>
                        ))}
                      </div>

                      <div className="space-y-2 max-h-[480px] overflow-y-auto overscroll-y-contain">
                        {filteredPresets.map((preset) => (
                          <button
                            key={preset.id}
                            onClick={() => handleSelectPreset(preset)}
                            className={`w-full rounded-lg border p-3 text-left transition-colors ${
                              form.baseUrl === preset.baseUrl
                                ? 'border-primary bg-primary/5'
                                : 'border-border hover:bg-muted/30'
                            }`}
                          >
                            <div className="flex items-start justify-between gap-2">
                              <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-1.5">
                                  <span className="text-sm font-medium">{preset.name}</span>
                                  {preset.region === 'CN' ? (
                                    <span className="rounded bg-info/10 px-1 py-0.5 text-xs font-medium text-info dark:bg-info/10 dark:text-info">国内</span>
                                  ) : (
                                    <span className="rounded bg-purple-100 px-1 py-0.5 text-xs font-medium text-purple-700 dark:bg-purple-900/30 dark:text-purple-400">国际</span>
                                  )}
                                </div>
                                <p className="mt-0.5 text-xs text-muted-foreground">{preset.description}</p>
                                <div className="mt-1.5 flex flex-wrap gap-1">
                                  {preset.models.slice(0, 3).map((m) => (
                                    <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${m.free ? 'bg-success/10 text-success dark:bg-success/10 dark:text-success' : 'bg-muted text-muted-foreground'}`}>
                                      {m.label}{m.free && ' (免费)'}
                                    </span>
                                  ))}
                                </div>
                              </div>
                              {preset.apiKeyUrl && (
                                <a href={preset.apiKeyUrl} target="_blank" rel="noopener noreferrer" onClick={(e) => e.stopPropagation()} className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground">
                                  <ExternalLink className="h-3.5 w-3.5" />
                                </a>
                              )}
                            </div>
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </section>

                {/* 供应商类型 */}
                <div>
                  <label className="mb-1.5 block text-sm font-medium">供应商类型</label>
                  <div className="grid grid-cols-2 gap-2">
                    {(['OLLAMA', 'OPENAI'] as const).map((p) => (
                      <button
                        key={p}
                        onClick={() => setForm((f) => ({ ...f, provider: p, baseUrl: p === 'OLLAMA' ? 'http://localhost:11434' : 'https://api.deepseek.com/v1' }))}
                        className={`rounded-lg border px-3 py-2 text-sm font-medium transition-colors ${
                          form.provider === p ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted/50'
                        }`}
                      >
                        {p === 'OLLAMA' ? 'Ollama' : 'OpenAI 兼容'}
                      </button>
                    ))}
                  </div>
                </div>

                {/* API 地址 */}
                <div>
                  <label className="mb-1.5 block text-sm font-medium">API 地址</label>
                  <input
                    type="text"
                    value={form.baseUrl}
                    onChange={(e) => setForm((f) => ({ ...f, baseUrl: e.target.value }))}
                    placeholder={form.provider === 'OLLAMA' ? 'http://localhost:11434' : 'https://api.deepseek.com/v1'}
                    className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                  />
                </div>

                {/* 模型名称 */}
                <div>
                  <label className="mb-1.5 block text-sm font-medium">模型名称</label>
                  {currentPreset && currentPreset.models.length > 0 ? (
                    <div className="space-y-2">
                      <select
                        value={isCustomModel ? '__custom__' : (
                          currentPreset.models.some((m) => m.name === form.modelName) ? form.modelName : '__custom__'
                        )}
                        onChange={(e) => {
                          if (e.target.value === '__custom__') {
                            setIsCustomModel(true)
                            setForm((f) => ({ ...f, modelName: '' }))
                          } else {
                            setIsCustomModel(false)
                            const selected = currentPreset.models.find(m => m.name === e.target.value)
                            setForm((f) => ({ ...f, modelName: e.target.value, maxTokens: selected?.maxTokens }))
                          }
                        }}
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                      >
                        {currentPreset.models.map((m) => (
                          <option key={m.name} value={m.name}>{m.label} ({m.name})</option>
                        ))}
                        <option value="__custom__">{isCustomModel && form.modelName ? `自定义: ${form.modelName}` : '自定义模型名...'}</option>
                      </select>
                      {isCustomModel && (
                        <input
                          type="text"
                          value={form.modelName}
                          onChange={(e) => setForm((f) => ({ ...f, modelName: e.target.value }))}
                          placeholder="输入自定义模型名"
                          className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                        />
                      )}
                    </div>
                  ) : (
                    <input
                      type="text"
                      value={form.modelName}
                      onChange={(e) => setForm((f) => ({ ...f, modelName: e.target.value }))}
                      placeholder={form.provider === 'OLLAMA' ? 'qwen3:8b' : 'deepseek-v4-flash'}
                      className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                    />
                  )}
                </div>

                {/* API Key */}
                {form.provider === 'OPENAI' && (
                  <div>
                    <label className="mb-1.5 block text-sm font-medium">API Key</label>
                    <div className="relative">
                      <input
                        type={showApiKey ? 'text' : 'password'}
                        value={form.apiKey || ''}
                        onChange={(e) => setForm((f) => ({ ...f, apiKey: e.target.value }))}
                        placeholder="sk-xxx"
                        className="w-full rounded-lg border border-border bg-background px-3 py-2 pr-10 text-sm outline-none focus:border-primary"
                      />
                      <button type="button" onClick={() => setShowApiKey(!showApiKey)} className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground">
                        {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </div>
                  </div>
                )}

                {/* 温度 + 超时 */}
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="mb-1.5 block text-sm font-medium">温度 ({form.temperature?.toFixed(1)})</label>
                    <input type="range" min="0" max="2" step="0.1" value={form.temperature || 0.7} onChange={(e) => setForm((f) => ({ ...f, temperature: parseFloat(e.target.value) }))} className="w-full accent-primary" />
                  </div>
                  <div>
                    <label className="mb-1.5 block text-sm font-medium">超时(秒)</label>
                    <input type="number" value={form.timeout || 120} onChange={(e) => setForm((f) => ({ ...f, timeout: parseInt(e.target.value) || 120 }))} min={30} max={600} className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
                  </div>
                </div>

                {/* RAG TopK */}
                <div>
                  <label className="mb-1.5 block text-sm font-medium">RAG 检索片段数 (TopK)</label>
                  <input 
                    type="number" 
                    value={form.ragTopK ?? ''} 
                    onChange={(e) => {
                      const val = e.target.value
                      setForm(f => ({ ...f, ragTopK: val === '' ? undefined : Number(val) }))
                    }} 
                    min={1} 
                    max={200} 
                    placeholder="留空使用全局默认值" 
                    className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" 
                  />
                  <p className="mt-1 text-xs text-muted-foreground">每次问答从向量库检索的参考片段数量。大上下文模型可设为 50~100。</p>
                </div>

                {/* 上下文长度 */}
                <div>
                  <label className="mb-1.5 block text-sm font-medium">上下文长度 (tokens)</label>
                  <input 
                    type="number" 
                    value={form.maxTokens ?? ''} 
                    onChange={(e) => {
                      const val = e.target.value
                      setForm(f => ({ ...f, maxTokens: val === '' ? undefined : Number(val) }))
                    }} 
                    min={0} 
                    step={1000}
                    placeholder={(() => {
                      const mt = currentPreset?.models.find(m => m.name === form.modelName)?.maxTokens
                      return mt ? `默认 ${(mt / 1024).toFixed(0)}K` : '留空默认 32K'
                    })()}
                    className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" 
                  />
                  <p className="mt-1 text-xs text-muted-foreground">
                    超过此长度的 {((form.maxTokens || 32768) * 1.5 / 10000).toFixed(1)} 万字符时自动压缩最早未压缩的 AI 回复。
                    当前选中模型上下文 {(() => {
                      const mt = currentPreset?.models.find(m => m.name === form.modelName)?.maxTokens
                      return mt ? `${(mt / 1024).toFixed(0)}K` : '未标注'
                    })()} tokens。
                  </p>
                </div>

                {/* Tool Calling */}
                <div>
                  <label className="mb-1.5 block text-sm font-medium">Tool Calling 支持</label>
                  <div className="grid grid-cols-3 gap-2">
                    {[
                      { value: null, label: '自动检测' },
                      { value: true, label: '支持' },
                      { value: false, label: '不支持' },
                    ].map((opt) => (
                      <button
                        key={String(opt.value)}
                        onClick={() => setForm((f) => ({ ...f, toolsEnabled: opt.value }))}
                        className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
                          form.toolsEnabled === opt.value ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted/50'
                        }`}
                      >
                        {opt.label}
                      </button>
                    ))}
                  </div>
                </div>

                {/* 启用开关 */}
                <div className="flex items-center justify-between">
                  <label className="text-sm font-medium">启用</label>
                  <button
                    onClick={() => setForm((f) => ({ ...f, enabled: !f.enabled }))}
                    className={`relative h-6 w-11 rounded-full transition-colors ${form.enabled ? 'bg-primary' : 'bg-muted-foreground/30'}`}
                  >
                    <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${form.enabled ? 'left-[22px]' : 'left-0.5'}`} />
                  </button>
                </div>

                {/* 操作按钮 */}
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

            {/* 使用说明 */}
            <section className="rounded-xl bg-muted/30 p-4 text-xs text-muted-foreground space-y-1.5">
              <p className="font-medium text-foreground">配置说明</p>
              <p>· 可添加多个 AI 配置，点击 <Star className="inline h-3.5 w-3.5 fill-yellow-500 text-yellow-500" /> 切换激活配置</p>
              <p>· 修改配置后立即生效，无需重启服务</p>
              <p>· 删除配置不会影响其他配置</p>
              <p>· 所有配置均未激活时自动回退到 yml 默认模型</p>
            </section>
          </div>
        </div>
      </main>
    </div>
  )
}
