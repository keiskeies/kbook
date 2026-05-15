import { useState, useEffect, useCallback, useMemo } from 'react'
import { ArrowLeft, Save, Trash2, RefreshCw, Zap, Eye, EyeOff, ExternalLink, ChevronDown, ChevronUp, Globe, MapPin } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import {
  listAiConfigs,
  saveAiConfig,
  deleteAiConfig,
  testAiConfig,
  AI_PROVIDER_PRESETS,
  type AiProviderConfig,
  type AiProviderPreset,
} from '@/api/aiConfig'

/**
 * AI 供应商配置管理页
 * 管理员可在此配置对话类 AI（图书问答/阅读助手/图书管理员）使用的模型。
 * 未配置时自动回退到 application.yml 中的默认模型。
 */
export default function AiConfigPage() {
  const navigate = useNavigate()
  const [configs, setConfigs] = useState<AiProviderConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState<number | null>(null)
  const [showApiKey, setShowApiKey] = useState(false)
  const [showPresets, setShowPresets] = useState(false)
  const [regionFilter, setRegionFilter] = useState<'ALL' | 'CN' | 'GLOBAL'>('ALL')

  // 编辑表单
  const [editingChat, setEditingChat] = useState<AiProviderConfig | null>(null)
  const [form, setForm] = useState<AiProviderConfig>({
    purpose: 'CHAT',
    provider: 'OLLAMA',
    baseUrl: 'http://localhost:11434',
    modelName: '',
    apiKey: '',
    temperature: 0.7,
    timeout: 120,
    toolsEnabled: null,
    enabled: true,
  })

  // 预设筛选
  const filteredPresets = useMemo(() => {
    if (regionFilter === 'ALL') return AI_PROVIDER_PRESETS
    return AI_PROVIDER_PRESETS.filter((p) => p.region === regionFilter)
  }, [regionFilter])

  const cnPresets = useMemo(() => AI_PROVIDER_PRESETS.filter((p) => p.region === 'CN'), [])
  const globalPresets = useMemo(() => AI_PROVIDER_PRESETS.filter((p) => p.region === 'GLOBAL'), [])

  const loadConfigs = useCallback(async () => {
    try {
      setLoading(true)
      const data = await listAiConfigs()
      const list = Array.isArray(data) ? data : []
      setConfigs(list)
      const chatConfig = list.find((c: AiProviderConfig) => c.purpose === 'CHAT')
      if (chatConfig) {
        setEditingChat(chatConfig)
        setForm({
          ...chatConfig,
          apiKey: chatConfig.apiKey || '',
        })
      }
    } catch (err: any) {
      toast.error(err.message || '加载配置失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadConfigs()
  }, [loadConfigs])

  const handleSelectPreset = (preset: AiProviderPreset) => {
    setForm((f) => ({
      ...f,
      provider: preset.provider,
      baseUrl: preset.baseUrl,
      modelName: preset.models[0]?.name || '',
      apiKey: f.provider !== preset.provider ? '' : f.apiKey,
    }))
    setShowPresets(false)
    toast.success(`已选择 ${preset.name}`, { description: '请填写 API Key 后保存' })
  }

  const handleSave = async () => {
    if (!form.modelName.trim()) {
      toast.error('请输入模型名称')
      return
    }
    if (!form.baseUrl.trim()) {
      toast.error('请输入 API 地址')
      return
    }

    try {
      setSaving(true)
      const payload = {
        ...form,
        id: editingChat?.id,
        apiKey: form.apiKey?.trim() || undefined,
      }
      await saveAiConfig(payload)
      toast.success('配置已保存')
      loadConfigs()
    } catch (err: any) {
      toast.error(err.message || '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    if (!editingChat?.id) return
    if (!confirm('确定删除对话 AI 配置？删除后将回退到默认模型。')) return

    try {
      await deleteAiConfig(editingChat.id)
      toast.success('配置已删除')
      setEditingChat(null)
      setForm({
        purpose: 'CHAT',
        provider: 'OLLAMA',
        baseUrl: 'http://localhost:11434',
        modelName: '',
        apiKey: '',
        temperature: 0.7,
        timeout: 120,
        toolsEnabled: null,
        enabled: true,
      })
      loadConfigs()
    } catch (err: any) {
      toast.error(err.message || '删除失败')
    }
  }

  const handleTest = async () => {
    if (!editingChat?.id) {
      toast.error('请先保存配置再测试')
      return
    }
    try {
      setTesting(editingChat.id)
      const data = await testAiConfig(editingChat.id)
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

  const isEditing = !!editingChat?.id

  // 当前匹配的预设（用于显示链接）
  const currentPreset = useMemo(
    () => AI_PROVIDER_PRESETS.find((p) => p.baseUrl === form.baseUrl),
    [form.baseUrl]
  )

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <header className="sticky top-0 z-50 flex items-center gap-3 border-b bg-background/95 px-4 py-3 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <button onClick={() => navigate(-1)} className="rounded-full p-1.5 active:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-lg font-bold">AI 配置管理</h1>
      </header>

      <main className="mx-auto max-w-lg space-y-4 p-4">
        {/* 说明卡片 */}
        <section className="rounded-xl bg-card p-4 shadow-xs">
          <div className="flex items-start gap-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10">
              <Zap className="h-4.5 w-4.5 text-primary" />
            </div>
            <div className="flex-1 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">对话 AI 配置</p>
              <p className="mt-1">
                此配置统一用于 <b>AI 图书问答</b>、<b>AI 阅读助手</b>、<b>AI 图书管理员</b>。
                如果未配置或已禁用，将自动使用标签评分的默认模型。
              </p>
            </div>
          </div>
        </section>

        {/* 供应商预设选择 */}
        <section className="rounded-xl bg-card p-4 shadow-xs">
          <button
            onClick={() => setShowPresets(!showPresets)}
            className="flex w-full items-center justify-between"
          >
            <span className="text-sm font-bold">选择 AI 供应商（快捷预设）</span>
            {showPresets ? (
              <ChevronUp className="h-4 w-4 text-muted-foreground" />
            ) : (
              <ChevronDown className="h-4 w-4 text-muted-foreground" />
            )}
          </button>

          {showPresets && (
            <div className="mt-3 space-y-3">
              {/* 地区筛选 */}
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
                    {opt.value === 'CN' && (
                      <span className="ml-0.5 rounded bg-primary-foreground/20 px-1 text-[10px]">
                        {cnPresets.length}
                      </span>
                    )}
                    {opt.value === 'GLOBAL' && (
                      <span className="ml-0.5 rounded bg-primary-foreground/20 px-1 text-[10px]">
                        {globalPresets.length}
                      </span>
                    )}
                  </button>
                ))}
              </div>

              {/* 预设列表 */}
              <div className="space-y-2">
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
                            <span className="rounded bg-blue-100 px-1 py-0.5 text-[10px] font-medium text-blue-700 dark:bg-blue-900/30 dark:text-blue-400">
                              国内
                            </span>
                          ) : (
                            <span className="rounded bg-purple-100 px-1 py-0.5 text-[10px] font-medium text-purple-700 dark:bg-purple-900/30 dark:text-purple-400">
                              国际
                            </span>
                          )}
                        </div>
                        <p className="mt-0.5 text-xs text-muted-foreground">{preset.description}</p>
                        <div className="mt-1.5 flex flex-wrap gap-1">
                          {preset.models.slice(0, 3).map((m) => (
                            <span
                              key={m.name}
                              className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${
                                m.free
                                  ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                                  : 'bg-muted text-muted-foreground'
                              }`}
                            >
                              {m.label}
                              {m.free && ' (免费)'}
                            </span>
                          ))}
                          {preset.models.length > 3 && (
                            <span className="text-[10px] text-muted-foreground">
                              +{preset.models.length - 3} 更多
                            </span>
                          )}
                        </div>
                      </div>
                      {/* 链接按钮 */}
                      <div className="flex shrink-0 flex-col gap-1">
                        {preset.apiKeyUrl && (
                          <a
                            href={preset.apiKeyUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            onClick={(e) => e.stopPropagation()}
                            className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
                            title="获取 API Key"
                          >
                            <ExternalLink className="h-3.5 w-3.5" />
                          </a>
                        )}
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          )}
        </section>

        {/* 配置表单 */}
        <section className="rounded-xl bg-card p-4 shadow-xs space-y-4">
          {/* 启用开关 */}
          <div className="flex items-center justify-between">
            <label className="text-sm font-medium">启用对话 AI 配置</label>
            <button
              onClick={() => setForm((f) => ({ ...f, enabled: !f.enabled }))}
              className={`relative h-6 w-11 rounded-full transition-colors ${
                form.enabled ? 'bg-primary' : 'bg-muted-foreground/30'
              }`}
            >
              <span
                className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${
                  form.enabled ? 'left-[22px]' : 'left-0.5'
                }`}
              />
            </button>
          </div>

          {/* 供应商类型 */}
          <div>
            <label className="mb-1.5 block text-sm font-medium">供应商类型</label>
            <div className="grid grid-cols-2 gap-2">
              {['OLLAMA', 'OPENAI'].map((p) => (
                <button
                  key={p}
                  onClick={() =>
                    setForm((f) => ({
                      ...f,
                      provider: p,
                      baseUrl:
                        p === 'OLLAMA'
                          ? 'http://localhost:11434'
                          : 'https://api.deepseek.com/v1',
                    }))
                  }
                  className={`rounded-lg border px-3 py-2 text-sm font-medium transition-colors ${
                    form.provider === p
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-border text-muted-foreground hover:bg-muted/50'
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

          {/* 模型名称 — 改为下拉+自定义输入 */}
          <div>
            <label className="mb-1.5 block text-sm font-medium">模型名称</label>
            {currentPreset && currentPreset.models.length > 0 ? (
              <div className="space-y-2">
                <select
                  value={currentPreset.models.some((m) => m.name === form.modelName) ? form.modelName : '__custom__'}
                  onChange={(e) => {
                    if (e.target.value !== '__custom__') {
                      setForm((f) => ({ ...f, modelName: e.target.value }))
                    }
                  }}
                  className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
                >
                  {currentPreset.models.map((m) => (
                    <option key={m.name} value={m.name}>
                      {m.label} ({m.name})
                    </option>
                  ))}
                  <option value="__custom__">自定义模型名...</option>
                </select>
                {/* 如果选了自定义，显示输入框 */}
                {!currentPreset.models.some((m) => m.name === form.modelName) && (
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

          {/* API Key (仅 OpenAI 兼容) */}
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
                <button
                  type="button"
                  onClick={() => setShowApiKey(!showApiKey)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground"
                >
                  {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              {/* 当前供应商快捷链接 */}
              {currentPreset && (
                <div className="mt-2 flex flex-wrap gap-2">
                  {currentPreset.apiKeyUrl && (
                    <a
                      href={currentPreset.apiKeyUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-1 text-xs font-medium text-primary hover:bg-primary/20"
                    >
                      <ExternalLink className="h-3 w-3" />
                      获取 API Key
                    </a>
                  )}
                  {currentPreset.tokenPlanUrl && (
                    <a
                      href={currentPreset.tokenPlanUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 rounded-md bg-muted px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-muted/80"
                    >
                      <ExternalLink className="h-3 w-3" />
                      Token 计费
                    </a>
                  )}
                  {currentPreset.codingPlanUrl && (
                    <a
                      href={currentPreset.codingPlanUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 rounded-md bg-muted px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-muted/80"
                    >
                      <ExternalLink className="h-3 w-3" />
                      Coding Plan
                    </a>
                  )}
                  {currentPreset.websiteUrl && !currentPreset.apiKeyUrl && (
                    <a
                      href={currentPreset.websiteUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 rounded-md bg-muted px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-muted/80"
                    >
                      <ExternalLink className="h-3 w-3" />
                      官网
                    </a>
                  )}
                </div>
              )}
            </div>
          )}

          {/* 温度 + 超时 */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1.5 block text-sm font-medium">
                温度 ({form.temperature?.toFixed(1)})
              </label>
              <input
                type="range"
                min="0"
                max="2"
                step="0.1"
                value={form.temperature || 0.7}
                onChange={(e) => setForm((f) => ({ ...f, temperature: parseFloat(e.target.value) }))}
                className="w-full accent-primary"
              />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium">超时(秒)</label>
              <input
                type="number"
                value={form.timeout || 120}
                onChange={(e) => setForm((f) => ({ ...f, timeout: parseInt(e.target.value) || 120 }))}
                min={30}
                max={600}
                className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary"
              />
            </div>
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
                    form.toolsEnabled === opt.value
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-border text-muted-foreground hover:bg-muted/50'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              部分模型（如 gemma3n）不支持工具调用，设为"不支持"将禁用搜索/推荐等功能
            </p>
          </div>

          {/* 操作按钮 */}
          <div className="flex gap-2 pt-2">
            <button
              onClick={handleSave}
              disabled={saving || !form.enabled}
              className="flex flex-1 items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground disabled:opacity-50"
            >
              <Save className="h-4 w-4" />
              {saving ? '保存中...' : isEditing ? '更新配置' : '创建配置'}
            </button>

            {isEditing && (
              <button
                onClick={handleTest}
                disabled={testing !== null}
                className="flex items-center gap-2 rounded-lg border border-border px-4 py-2.5 text-sm font-medium hover:bg-muted/50 disabled:opacity-50"
              >
                <RefreshCw className={`h-4 w-4 ${testing !== null ? 'animate-spin' : ''}`} />
                {testing !== null ? '测试中...' : '测试'}
              </button>
            )}
          </div>

          {isEditing && (
            <button
              onClick={handleDelete}
              className="flex w-full items-center justify-center gap-2 rounded-lg border border-destructive/30 px-4 py-2.5 text-sm font-medium text-destructive hover:bg-destructive/10"
            >
              <Trash2 className="h-4 w-4" />
              删除配置
            </button>
          )}
        </section>

        {/* 当前生效信息 */}
        <section className="rounded-xl bg-card p-4 shadow-xs">
          <h3 className="mb-3 text-sm font-bold">当前配置状态</h3>
          <div className="space-y-2 text-sm">
            {configs.length === 0 ? (
              <p className="text-muted-foreground">未配置，使用 yml 默认模型</p>
            ) : (
              configs.map((c) => (
                <div key={c.id} className="flex items-center gap-2 rounded-lg bg-muted/30 px-3 py-2">
                  <Zap className="h-4 w-4 text-muted-foreground shrink-0" />
                  <div className="flex-1 min-w-0">
                    <span className="font-medium">{c.purpose}</span>
                    <span className="mx-1.5 text-muted-foreground">·</span>
                    <span className="text-muted-foreground truncate">{c.provider}/{c.modelName}</span>
                  </div>
                  <span
                    className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${
                      c.enabled ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400' : 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'
                    }`}
                  >
                    {c.enabled ? '启用' : '禁用'}
                  </span>
                </div>
              ))
            )}
          </div>
        </section>

        {/* 供应商速查表 */}
        <section className="rounded-xl bg-muted/30 p-4 space-y-3">
          <p className="text-xs font-bold">供应商速查（价格截至 2026-05，请以官网为准）</p>
          <div className="space-y-2">
            {[
              { name: 'DeepSeek', price: 'V4-Flash ¥1/¥2/M / V4-Pro ¥3/¥6/M(2.5折) / 1M上下文', link: 'https://platform.deepseek.com' },
              { name: '通义千问', price: 'Qwen3.6 Flash ¥0.15/M 起 / Coding Plan 有', link: 'https://tongyi.aliyun.com' },
              { name: '智谱 GLM', price: 'GLM-5.1 最新 / Flash 免费 / Coding ¥49/月', link: 'https://open.bigmodel.cn' },
              { name: 'Kimi', price: 'K2.6 最新开源 / K2.5 万亿参数 / Coding 有', link: 'https://platform.moonshot.cn' },
              { name: '豆包', price: 'Seed 2.0 Pro ¥0.67/M / Mini ¥0.06/M', link: 'https://www.volcengine.com/product/doubao' },
              { name: '小米 MiMo', price: 'V2.5 Flash $0.1/M / Pro $1/M 1M上下文', link: 'https://platform.xiaomimimo.com' },
              { name: 'MiniMax', price: 'M2.7 最新 / M2.5 开源 / Coding ¥29/月', link: 'https://platform.minimaxi.com' },
              { name: '硅基流动', price: 'V4 Flash 等模型免费 / 聚合平台', link: 'https://siliconflow.cn' },
              { name: '阶跃星辰', price: 'Step 3.5 Flash 350TPS / Coding ¥25/月', link: 'https://www.stepfun.com' },
              { name: '腾讯混元', price: 'Turbo S 最新 / Lite 免费', link: 'https://cloud.tencent.com/product/hunyuan' },
              { name: '讯飞星火', price: '4.0 Ultra / Lite 永久免费', link: 'https://xinghuo.xfyun.cn' },
              { name: 'OpenAI', price: 'GPT-5.5 $5/M / o3-mini $1.1/M', link: 'https://platform.openai.com' },
              { name: 'Claude', price: 'Opus 4.6 $5/M / Sonnet 4.6 $3/M', link: 'https://www.anthropic.com' },
              { name: 'Gemini', price: '3.1 Pro $2/M / Flash-Lite $0.1/M', link: 'https://aistudio.google.com' },
              { name: 'xAI Grok', price: 'Grok 4 Fast $0.2/M / 2M上下文', link: 'https://x.ai' },
              { name: 'OpenRouter', price: '聚合 400+ 模型 / 按需付费', link: 'https://openrouter.ai' },
            ].map((item) => (
              <div key={item.name} className="flex items-center justify-between text-xs">
                <div className="flex-1 min-w-0">
                  <span className="font-medium">{item.name}</span>
                  <span className="mx-1.5 text-muted-foreground">·</span>
                  <span className="text-muted-foreground">{item.price}</span>
                </div>
                <a
                  href={item.link}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="shrink-0 text-primary hover:underline"
                >
                  官网
                </a>
              </div>
            ))}
          </div>
        </section>

        {/* 使用说明 */}
        <section className="rounded-xl bg-muted/30 p-4 text-xs text-muted-foreground space-y-1.5">
          <p className="font-medium text-foreground">配置说明</p>
          <p>· <b>Ollama</b>：本地/远程 Ollama 服务，无需 API Key</p>
          <p>· <b>OpenAI 兼容</b>：支持 DeepSeek、通义千问、智谱、Kimi 等所有兼容 API</p>
          <p>· 点击上方预设可快速填入 API 地址和模型名</p>
          <p>· 修改配置后立即生效，无需重启服务</p>
          <p>· 删除配置后将自动回退到 yml 默认模型</p>
          <p>· 价格信息仅供参考，请以各厂商官网实时定价为准</p>
        </section>
      </main>
    </div>
  )
}
