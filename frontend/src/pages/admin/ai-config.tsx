import { useState, useMemo, useRef, type ReactNode } from 'react'
import {
  ArrowLeft, Save, Trash2, RefreshCw, Eye, EyeOff,
  ExternalLink, ChevronDown, ChevronUp, Globe, MapPin,
  Plus, Pencil, MessageSquare, Layers, Users, Star, Sliders,
} from 'lucide-react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useGoBack } from '@/hooks/useGoBack'
import { useScrollRestore } from '@/hooks/useScrollRestore'
import { ROUTES } from '@/constants'
import { toast } from 'sonner'
import {
  listAiConfigsByPurpose,
  createAiConfig,
  updateAiConfig,
  deleteAiConfig,
  testAiConfig,
  fetchProviderPresets,
  reloadAiConfig,
  activateAiConfig,
  type AiProviderConfig,
  type AiProviderPreset,
} from '@/api/aiConfig'

const CHAT = 'CHAT'
const EMBEDDING = 'EMBEDDING'
type ActiveTab = 'CHAT' | 'EMBEDDING' | 'ROLES'

/** 提取配置列表的渲染逻辑，按用途区分 */
function renderConfigRow(
  c: AiProviderConfig,
  purpose: string,
  opts: {
    activeEmbeddingId: number | null
    testing: number | null
    expandedId: number | 'new' | null
    onActivateEmbedding: (id: number) => void
    onTest: (id: number) => void
    onEdit: (c: AiProviderConfig) => void
    onDelete: (id: number) => void
    renderForm: () => React.ReactNode
  }
) {
  const { activeEmbeddingId, testing, expandedId, onActivateEmbedding, onTest, onEdit, onDelete, renderForm } = opts

  if (purpose === EMBEDDING) {
    const isActive = c.id === activeEmbeddingId
    return (
      <div key={c.id}>
        <div className={`flex items-center gap-2 px-3 py-2.5 ${isActive ? 'bg-primary/5' : ''}`}>
          {/* 激活星标 — 可点击切换 */}
          <button
            onClick={c.enabled ? () => onActivateEmbedding(c.id!) : undefined}
            className={`shrink-0 ${isActive ? 'text-yellow-500 cursor-default' : c.enabled ? 'text-muted-foreground hover:text-yellow-500 cursor-pointer' : 'text-muted-foreground/30'}`}
            title={isActive ? '当前激活 — 最新更新的启用配置' : c.enabled ? '点击设为激活' : '已禁用'}
          >
            {isActive ? (
              <Star className="h-5 w-5 fill-yellow-500" />
            ) : (
              <Star className="h-5 w-5" />
            )}
          </button>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-1.5">
              <span className="font-medium text-sm truncate">{c.name}</span>
              {isActive && (
                <span className="rounded bg-primary/20 px-1.5 py-0.5 text-xs font-medium text-primary">激活</span>
              )}
              {!c.enabled && (
                <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/30 dark:text-red-400">已禁用</span>
              )}
            </div>
            <p className="text-xs text-muted-foreground truncate">
              {c.provider} / {c.modelName}
              {c.embeddingDimension ? ` / dim=${c.embeddingDimension}` : ''}
            </p>
          </div>
          <div className="flex shrink-0 gap-0.5">
            <button onClick={() => onTest(c.id!)} disabled={testing === c.id} className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50" title="测试连接"><RefreshCw className={`h-4 w-4 ${testing === c.id ? 'animate-spin' : ''}`} /></button>
            <button onClick={() => onEdit(c)} className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground" title="编辑"><Pencil className="h-4 w-4" /></button>
            <button onClick={() => onDelete(c.id!)} className="rounded p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive" title="删除"><Trash2 className="h-4 w-4" /></button>
          </div>
        </div>
        {expandedId === c.id && (
          <div className="border-t border-border px-4 py-4">{renderForm()}</div>
        )}
      </div>
    )
  }

  // CHAT config row
  return (
    <div key={c.id}>
      <div className="flex items-center gap-2 px-3 py-2.5">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <span className="font-medium text-sm truncate">{c.name}</span>
            {!c.enabled && (
              <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/30 dark:text-red-400">已禁用</span>
            )}
          </div>
          <p className="text-xs text-muted-foreground truncate">
            {c.provider} / {c.modelName}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-0.5">
          <button onClick={() => onTest(c.id!)} disabled={testing === c.id} className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground disabled:opacity-50" title="测试连接"><RefreshCw className={`h-4 w-4 ${testing === c.id ? 'animate-spin' : ''}`} /></button>
          <button onClick={() => onEdit(c)} className="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground" title="编辑"><Pencil className="h-4 w-4" /></button>
          <button onClick={() => onDelete(c.id!)} className="rounded p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive" title="删除"><Trash2 className="h-4 w-4" /></button>
        </div>
      </div>
      {expandedId === c.id && (
        <div className="border-t border-border px-4 py-4">{renderForm()}</div>
      )}
    </div>
  )
}

/** 模型配置列 — 模块级避免每次渲染重建导致输入框丢焦点 */
function ModelColumn(props: {
  purpose: string; configs: AiProviderConfig[]; loading: boolean; title: string; desc: string
  expandedId: number | 'new' | null; formPurpose: string
  renderFormContent: () => ReactNode; openCreateForm: (p: string) => void
  rowOpts: Parameters<typeof renderConfigRow>[2]
}) {
  return (
    <section className="rounded-xl bg-card shadow-xs">
      <div className="flex items-center justify-between border-b px-4 py-3">
        <div>
          <h2 className="text-sm font-bold">{props.title}</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">{props.desc}</p>
        </div>
        <button onClick={() => props.openCreateForm(props.purpose)} className="shrink-0 flex items-center gap-1 rounded-full bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90"><Plus className="h-3.5 w-3.5" />新增</button>
      </div>
      {props.expandedId === 'new' && props.formPurpose === props.purpose && (<div className="border-b px-4 py-4">{props.renderFormContent()}</div>)}
      {props.loading ? (<div className="px-4 py-8 text-center text-sm text-muted-foreground">加载中...</div>)
        : props.configs.length === 0 && !(props.expandedId === 'new' && props.formPurpose === props.purpose) ? (<div className="px-4 py-8 text-center text-sm text-muted-foreground">暂无配置，点击上方「新增」添加</div>)
          : (<div className="divide-y">{props.configs.map(c => renderConfigRow(c, props.purpose, props.rowOpts))}</div>)}
    </section>
  )
}

export default function AiConfigPage() {
  const goBack = useGoBack()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [activeTab, setActiveTab] = useState<ActiveTab>('CHAT')
  const [showPresets, setShowPresets] = useState(false)
  const [regionFilter, setRegionFilter] = useState<'ALL' | 'CN' | 'GLOBAL'>('ALL')
  const [testing, setTesting] = useState<number | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const { handleScroll } = useScrollRestore(scrollRef)

  const { data: chatConfigs = [], isLoading: loadingChat } = useQuery({
    queryKey: ['admin', 'ai-config', 'CHAT'],
    queryFn: () => listAiConfigsByPurpose(CHAT).then(d => Array.isArray(d) ? d : []),
  })
  const { data: embeddingConfigs = [], isLoading: loadingEmbedding } = useQuery({
    queryKey: ['admin', 'ai-config', 'EMBEDDING'],
    queryFn: () => listAiConfigsByPurpose(EMBEDDING).then(d => Array.isArray(d) ? d : []),
  })
  const { data: presets = [] } = useQuery({
    queryKey: ['admin', 'ai-presets'],
    queryFn: () => fetchProviderPresets().then(d => Array.isArray(d) ? d : [] as AiProviderPreset[]),
  })

  const [expandedId, setExpandedId] = useState<number | 'new' | null>(null)
  const [showApiKey, setShowApiKey] = useState(false)
  const [isCustomModel, setIsCustomModel] = useState(false)
  const [form, setForm] = useState<AiProviderConfig>({
    name: '', purpose: CHAT, provider: 'OLLAMA', baseUrl: 'http://localhost:11434',
    modelName: '', apiKey: '', temperature: 0.7, timeout: 120,
    toolsEnabled: null, enabled: true, ragTopK: 5, roles: '', embeddingDimension: 1024,
    thinkingMode: 'SWITCH',
  })

  const isChatForm = form.purpose === CHAT
  const isEmbeddingForm = form.purpose === EMBEDDING

  /** 当前激活的嵌入配置：最新更新的启用配置 */
  const activeEmbeddingId = useMemo(() => {
    const enabled = embeddingConfigs.filter(c => c.enabled && c.updatedAt)
    if (enabled.length === 0) return null
    enabled.sort((a, b) => new Date(b.updatedAt!).getTime() - new Date(a.updatedAt!).getTime())
    return enabled[0].id ?? null
  }, [embeddingConfigs])

  const filteredPresets = useMemo(() => {
    if (regionFilter === 'ALL') return presets
    return presets.filter(p => p.region === regionFilter)
  }, [regionFilter, presets])

  const configs = activeTab === EMBEDDING ? embeddingConfigs : chatConfigs
  const loading = activeTab === EMBEDDING ? loadingEmbedding : loadingChat

  const resetForm = () => {
    setForm({
      name: '', purpose: CHAT, provider: 'OLLAMA', baseUrl: 'http://localhost:11434',
      modelName: '', apiKey: '', temperature: 0.7, timeout: 120,
      toolsEnabled: null, enabled: true, ragTopK: 5, roles: '', embeddingDimension: 1024,
      thinkingMode: 'SWITCH',
    })
    setExpandedId(null); setShowApiKey(false); setIsCustomModel(false)
  }

  const openCreateForm = (purpose: string) => {
    resetForm()
    setForm(f => ({ ...f, purpose }))
    setExpandedId('new')
  }

  const openEditForm = (config: AiProviderConfig) => {
    setForm({ ...config })
    setExpandedId(config.id ?? null)
    if (config.purpose !== EMBEDDING) {
      const preset = presets.find(p => p.baseUrl === config.baseUrl)
      setIsCustomModel(preset ? !preset.models.some(m => m.name === config.modelName) : !!config.modelName)
    }
  }

  const handleSelectPreset = (preset: AiProviderPreset) => {
    const firstModel = preset.models[0]
    setForm(f => ({ ...f, name: preset.name, provider: preset.provider, baseUrl: preset.baseUrl, modelName: firstModel?.name || '', apiKey: f.provider !== preset.provider ? '' : f.apiKey, maxTokens: firstModel?.maxTokens }))
    setShowPresets(false); setIsCustomModel(false)
    toast.success(`已选择 ${preset.name}`, { description: '请填写名称和 API Key 后保存' })
  }

  const invalidateConfigs = (purpose: string) => {
    queryClient.invalidateQueries({ queryKey: ['admin', 'ai-config', purpose] })
  }

  const handleSave = async () => {
    if (!form.name?.trim()) { toast.error('请输入配置名称'); return }
    if (!form.modelName.trim()) { toast.error('请输入模型名称'); return }
    if (!form.baseUrl.trim()) { toast.error('请输入 API 地址'); return }
    try {
      const payload = { ...form, apiKey: form.apiKey?.trim() || undefined }
      if (expandedId === 'new') { await createAiConfig(payload); toast.success('配置已创建') }
      else { await updateAiConfig(expandedId as number, payload); toast.success('配置已更新') }
      resetForm(); invalidateConfigs(form.purpose)
    } catch (err: any) { toast.error(err.message || '保存失败') }
  }

  const handleDelete = async (id: number) => {
    const all = form.purpose === EMBEDDING ? embeddingConfigs : chatConfigs
    const config = all.find(c => c.id === id)
    if (!config || !confirm(`确定删除「${config.name}」？`)) return
    try {
      await deleteAiConfig(id); toast.success('配置已删除')
      if (expandedId === id) resetForm()
      invalidateConfigs(config.purpose)
    } catch (err: any) { toast.error(err.message || '删除失败') }
  }

  const handleActivateEmbedding = async (id: number) => {
    try {
      await activateAiConfig(id)
      toast.success('已切换激活嵌入模型')
      invalidateConfigs(EMBEDDING)
    } catch (err: any) { toast.error(err.message || '激活失败') }
  }

  const handleTest = async (id: number) => {
    try {
      setTesting(id)
      const data = await testAiConfig(id)
      const result = typeof data === 'string' ? data : String(data || '')
      toast.success('连接测试成功', { description: result.length > 100 ? result.substring(0, 100) + '...' : result })
    } catch (err: any) { toast.error('连接测试失败', { description: err.message || '未知错误' }) } finally { setTesting(null) }
  }

  const currentPreset = useMemo(() => presets.find(p => p.baseUrl === form.baseUrl), [form.baseUrl, presets])

  // ==================== 表单渲染 ====================

  function renderFormContent() {
    return (
      <div className="space-y-4">
        <div>
          <label className="mb-1.5 block text-sm font-medium">配置名称</label>
          <input type="text" value={form.name || ''} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="如：DeepSeek-V4、Qwen3-Max" className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
        </div>

        {/* 供应商 preset - only for CHAT */}
        {isChatForm && (
          <section className="rounded-lg bg-muted p-3">
            <button onClick={() => setShowPresets(!showPresets)} className="flex w-full items-center justify-between text-sm font-medium">
              <span>选择 AI 供应商（快捷预设）</span>
              {showPresets ? <ChevronUp className="h-4 w-4 text-muted-foreground" /> : <ChevronDown className="h-4 w-4 text-muted-foreground" />}
            </button>
            {showPresets && (
              <div className="mt-3 space-y-3">
                <div className="flex gap-1.5">
                  {[{ value: 'ALL' as const, label: '全部' }, { value: 'CN' as const, label: '国内', icon: MapPin }, { value: 'GLOBAL' as const, label: '国际', icon: Globe }].map(opt => (
                    <button key={opt.value} onClick={() => setRegionFilter(opt.value)}
                      className={`flex items-center gap-1 rounded-full px-3 py-1 text-xs font-medium transition-colors ${regionFilter === opt.value ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/80'}`}>
                      {opt.icon && <opt.icon className="h-3 w-3" />}{opt.label}
                    </button>
                  ))}
                </div>
                <div className="space-y-2 max-h-[300px] overflow-y-auto overscroll-y-contain">
                  {filteredPresets.map(preset => (
                    <button key={preset.id} onClick={() => handleSelectPreset(preset)}
                      className={`w-full rounded-lg border p-3 text-left transition-colors ${form.baseUrl === preset.baseUrl ? 'border-primary bg-primary/5' : 'border-border hover:bg-muted'}`}>
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-1.5">
                            <span className="text-sm font-medium">{preset.name}</span>
                            {preset.region === 'CN' ? <span className="rounded bg-info/10 px-1 py-0.5 text-xs font-medium text-info">国内</span> : <span className="rounded bg-purple-100 px-1 py-0.5 text-xs font-medium text-purple-700 dark:bg-purple-900/30 dark:text-purple-400">国际</span>}
                          </div>
                          <p className="mt-0.5 text-xs text-muted-foreground">{preset.description}</p>
                          <div className="mt-1.5 flex flex-wrap gap-1">
                            {preset.models.slice(0, 3).map(m => (
                              <span key={m.name} className={`rounded px-1.5 py-0.5 text-xs font-medium ${m.free ? 'bg-success/10 text-success' : 'bg-muted text-muted-foreground'}`}>{m.label}{m.free && ' (免费)'}</span>
                            ))}
                          </div>
                        </div>
                        {preset.apiKeyUrl && <a href={preset.apiKeyUrl} target="_blank" rel="noopener noreferrer" onClick={e => e.stopPropagation()} className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground"><ExternalLink className="h-3.5 w-3.5" /></a>}
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            )}
          </section>
        )}

        <div>
          <label className="mb-1.5 block text-sm font-medium">供应商类型</label>
          <div className="grid grid-cols-2 gap-2">
            {(['OLLAMA', 'OPENAI'] as const).map(p => (
              <button key={p} onClick={() => setForm(f => ({ ...f, provider: p, baseUrl: p === 'OLLAMA' ? 'http://localhost:11434' : 'https://api.deepseek.com/v1' }))}
                className={`rounded-lg border px-3 py-2 text-sm font-medium transition-colors ${form.provider === p ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted'}`}>
                {p === 'OLLAMA' ? 'Ollama' : 'OpenAI 兼容'}
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="mb-1.5 block text-sm font-medium">API 地址</label>
          <input type="text" value={form.baseUrl} onChange={e => setForm(f => ({ ...f, baseUrl: e.target.value }))} placeholder={form.provider === 'OLLAMA' ? 'http://localhost:11434' : 'https://api.deepseek.com/v1'} className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
        </div>

        <div>
          <label className="mb-1.5 block text-sm font-medium">模型名称</label>
          {isChatForm && currentPreset && currentPreset.models.length > 0 ? (
            <div className="space-y-2">
              <select value={isCustomModel ? '__custom__' : (currentPreset.models.some(m => m.name === form.modelName) ? form.modelName : '__custom__')}
                onChange={e => {
                  if (e.target.value === '__custom__') { setIsCustomModel(true); setForm(f => ({ ...f, modelName: '' })) }
                  else { setIsCustomModel(false); const selected = currentPreset.models.find(m => m.name === e.target.value); setForm(f => ({ ...f, modelName: e.target.value, maxTokens: selected?.maxTokens })) }
                }} className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary">
                {currentPreset.models.map(m => (<option key={m.name} value={m.name}>{m.label} ({m.name})</option>))}
                <option value="__custom__">{isCustomModel && form.modelName ? `自定义: ${form.modelName}` : '自定义模型名...'}</option>
              </select>
              {isCustomModel && <input type="text" value={form.modelName} onChange={e => setForm(f => ({ ...f, modelName: e.target.value }))} placeholder="输入自定义模型名" className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />}
            </div>
          ) : (
            <input type="text" value={form.modelName} onChange={e => setForm(f => ({ ...f, modelName: e.target.value }))} placeholder={form.provider === 'OLLAMA' ? 'qwen3:8b' : 'deepseek-v4-flash'} className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
          )}
        </div>

        {form.provider === 'OPENAI' && (
          <div>
            <label className="mb-1.5 block text-sm font-medium">API Key</label>
            <div className="relative">
              <input type={showApiKey ? 'text' : 'password'} value={form.apiKey || ''} onChange={e => setForm(f => ({ ...f, apiKey: e.target.value }))} placeholder="sk-xxx" className="w-full rounded-lg border border-border bg-background px-3 py-2 pr-10 text-sm outline-none focus:border-primary" />
              <button type="button" onClick={() => setShowApiKey(!showApiKey)} className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground">{showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}</button>
            </div>
          </div>
        )}

        {isEmbeddingForm ? (
          <div>
            <label className="mb-1.5 block text-sm font-medium">超时(秒)</label>
            <input type="number" value={form.timeout || 120} onChange={e => setForm(f => ({ ...f, timeout: parseInt(e.target.value) || 120 }))} min={30} max={600} className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1.5 block text-sm font-medium">温度 ({form.temperature?.toFixed(1)})</label>
              <input type="range" min="0" max="2" step="0.1" value={form.temperature || 0.7} onChange={e => setForm(f => ({ ...f, temperature: parseFloat(e.target.value) }))} className="w-full accent-primary" />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium">超时(秒)</label>
              <input type="number" value={form.timeout || 120} onChange={e => setForm(f => ({ ...f, timeout: parseInt(e.target.value) || 120 }))} min={30} max={600} className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
            </div>
          </div>
        )}

        {isChatForm && (
          <div>
            <label className="mb-1.5 block text-sm font-medium">RAG 检索片段数 (TopK)</label>
            <input type="number" value={form.ragTopK ?? ''} onChange={e => { const val = e.target.value; setForm(f => ({ ...f, ragTopK: val === '' ? undefined : Number(val) })) }} min={1} max={200} placeholder="留空使用全局默认值" className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
            <p className="mt-1 text-xs text-muted-foreground">每次问答从向量库检索的参考片段数量。大上下文模型可设为 50~100。</p>
          </div>
        )}

        {isEmbeddingForm && (
          <div>
            <label className="mb-1.5 block text-sm font-medium">嵌入向量维度</label>
            <input type="number" value={form.embeddingDimension ?? ''} onChange={e => { const val = e.target.value; setForm(f => ({ ...f, embeddingDimension: val === '' ? undefined : Number(val) })) }} min={64} max={8192} step={128} placeholder="如: 1024 (bge-m3)" className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
            <p className="mt-1 text-xs text-muted-foreground">向量维度需与 Qdrant 集合中配置的维度一致。</p>
          </div>
        )}

        {isChatForm && (
          <div>
            <label className="mb-1.5 block text-sm font-medium">上下文长度 (tokens)</label>
            <input type="number" value={form.maxTokens ?? ''} onChange={e => { const val = e.target.value; setForm(f => ({ ...f, maxTokens: val === '' ? undefined : Number(val) })) }} min={0} step={1000} placeholder={(() => { const mt = currentPreset?.models.find(m => m.name === form.modelName)?.maxTokens; return mt ? `默认 ${(mt / 1024).toFixed(0)}K` : '留空默认 32K' })()} className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary" />
            <p className="mt-1 text-xs text-muted-foreground">超过此长度的 {((form.maxTokens || 32768) * 1.5 / 10000).toFixed(1)} 万字符时自动压缩最早未压缩的 AI 回复。</p>
          </div>
        )}

        {isChatForm && (
          <div className="space-y-2 rounded-lg border border-border bg-muted/30 p-3">
            <div>
              <span className="text-sm font-medium">思考模式</span>
              <p className="mt-0.5 text-xs text-muted-foreground">声明该模型支持的思考能力。场景配置时根据此模式联动渲染思考选项。</p>
            </div>
            <select value={form.thinkingMode ?? 'SWITCH'} onChange={e => setForm(f => ({ ...f, thinkingMode: e.target.value }))} className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary">
              <option value="SWITCH">SWITCH — 仅支持开/关（大多数模型）</option>
              <option value="REASONING_EFFORT">REASONING_EFFORT — 支持 low/medium/high 强度</option>
              <option value="THINKING_BUDGET">THINKING_BUDGET — 支持 token 预算（o 系列）</option>
              <option value="NONE">NONE — 不支持思考参数（Gemini 等）</option>
            </select>
            <p className="text-xs text-muted-foreground">
              {form.thinkingMode === 'NONE' && '该模型发送任何思考参数都会 400，场景配置时不会显示思考选项。'}
              {form.thinkingMode === 'SWITCH' && '场景配置时仅显示开/关 toggle。'}
              {form.thinkingMode === 'REASONING_EFFORT' && '场景配置时显示开/关 + low/medium/high 下拉。'}
              {form.thinkingMode === 'THINKING_BUDGET' && '场景配置时显示开/关 + token 预算输入。'}
            </p>
          </div>
        )}

        {isChatForm && (
          <div>
            <label className="mb-1.5 block text-sm font-medium">Tool Calling 支持</label>
            <div className="grid grid-cols-3 gap-2">
              {[{ value: null as boolean | null, label: '自动检测' }, { value: true, label: '支持' }, { value: false, label: '不支持' }].map(opt => (
                <button key={String(opt.value)} onClick={() => setForm(f => ({ ...f, toolsEnabled: opt.value }))}
                  className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${form.toolsEnabled === opt.value ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted'}`}>{opt.label}</button>
              ))}
            </div>
          </div>
        )}

        <div className="flex items-center justify-between">
          <label className="text-sm font-medium">启用</label>
          <button onClick={() => setForm(f => ({ ...f, enabled: !f.enabled }))} className={`relative h-6 w-11 rounded-full transition-colors ${form.enabled ? 'bg-primary' : 'bg-muted-foreground/30'}`}>
            <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${form.enabled ? 'left-[22px]' : 'left-0.5'}`} />
          </button>
        </div>

        <div className="flex gap-2 pt-2">
          <button onClick={handleSave} className="flex flex-1 items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground"><Save className="h-4 w-4" />{expandedId === 'new' ? '创建' : '更新'}</button>
          <button onClick={() => resetForm()} className="flex items-center gap-2 rounded-lg border border-border px-4 py-2.5 text-sm font-medium hover:bg-muted">取消</button>
        </div>
      </div>
    )
  }

  // ==================== 面板渲染 ====================

  const rowOpts = { activeEmbeddingId, testing, expandedId, onActivateEmbedding: handleActivateEmbedding, onTest: handleTest, onEdit: openEditForm, onDelete: handleDelete, renderForm: renderFormContent }

  // ==================== 页面 ====================

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background overscroll-contain">
      <header className="shrink-0 flex items-center gap-3 border-b bg-navbar/95 px-4 md:px-6 lg:px-8 py-3 backdrop-blur supports-[backdrop-filter]:bg-navbar/60 z-20">
        <button onClick={() => goBack()} className="rounded-full p-1.5 active:bg-muted"><ArrowLeft className="h-5 w-5" /></button>
        <h1 className="text-h3 font-bold flex-1">AI 配置管理</h1>
        <button onClick={() => navigate(ROUTES.ADMIN_AI_SCENE)} className="flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs font-medium hover:bg-muted">
          <Sliders className="h-3.5 w-3.5" />场景配置
        </button>
      </header>

      {/* 手机版 Tabs */}
      <div className="md:hidden shrink-0 flex border-b bg-navbar/95 px-4 backdrop-blur supports-[backdrop-filter]:bg-navbar/60">
        {[{ tab: 'CHAT' as const, icon: MessageSquare, label: '对话模型' }, { tab: 'EMBEDDING' as const, icon: Layers, label: '嵌入模型' }, { tab: 'ROLES' as const, icon: Users, label: '角色配置' }].map(t => (
          <button key={t.tab} onClick={() => setActiveTab(t.tab)}
            className={`flex items-center gap-1.5 px-3 py-3 text-sm font-medium border-b-2 transition-colors ${activeTab === t.tab ? 'border-primary text-primary' : 'border-transparent text-muted-foreground hover:text-foreground'}`}>
            <t.icon className="h-4 w-4" />{t.label}
          </button>
        ))}
      </div>

      <main ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto overscroll-contain px-4 md:px-6 lg:px-8 py-4">

        {/* 手机版：按 tab 显示 */}
        <div className="md:hidden">
          {activeTab === 'ROLES' ? (
            <div className="max-w-2xl mx-auto">
              <section className="rounded-xl bg-card shadow-xs">
                <div className="flex items-center justify-between border-b px-4 py-3">
                  <div>
                    <h2 className="text-sm font-bold">角色配置热加载</h2>
                    <p className="mt-0.5 text-xs text-muted-foreground">编辑 <code className="rounded bg-muted px-1 py-0.5 font-mono">deploy/ai-config.json</code> 文件后点击刷新，无需重启服务。</p>
                  </div>
                  <button onClick={() => reloadAiConfig().then(() => toast.success('角色配置已重载')).catch((e: any) => toast.error(e.message || '重载失败'))} className="flex items-center gap-1 rounded-lg bg-primary px-5 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 whitespace-nowrap"><RefreshCw className="h-3.5 w-3.5" />重载</button>
                </div>
                <div className="p-4 space-y-4 text-sm">
                  <div>
                    <h3 className="font-medium text-foreground mb-2">可修改的内容</h3>
                    <ul className="space-y-3 pl-4">
                      <li className="list-disc marker:text-muted-foreground/40"><p className="font-medium text-foreground">图书问答风格</p><p className="text-xs text-muted-foreground mt-0.5"><code className="rounded bg-muted px-1 font-mono">bookChat.styles[].prompt</code> — 随和 / 深度 / 简洁 / 幽默四种对话风格的系统提示词。</p></li>
                      <li className="list-disc marker:text-muted-foreground/40"><p className="font-medium text-foreground">圆桌派角色</p><p className="text-xs text-muted-foreground mt-0.5"><code className="rounded bg-muted px-1 font-mono">roundTable.roles[].prompt</code> — 各角色的说话风格与立场描述。</p><p className="text-xs text-muted-foreground"><code className="rounded bg-muted px-1 font-mono">roundTable.roles[].icon</code> / <code className="rounded bg-muted px-1 font-mono">color</code> — 角色 emoji 图标与代表色。</p><p className="text-xs text-muted-foreground"><code className="rounded bg-muted px-1 font-mono">roundTable.roles[].params</code> — 抢话权重、话多程度、立场强度等参数。</p></li>
                      <li className="list-disc marker:text-muted-foreground/40"><p className="font-medium text-foreground">奇葩说辩手性格</p><p className="text-xs text-muted-foreground mt-0.5"><code className="rounded bg-muted px-1 font-mono">debate.personalities[].prompt</code> — 各辩手的辩论风格与立场。</p><p className="text-xs text-muted-foreground"><code className="rounded bg-muted px-1 font-mono">debate.personalities[].icon</code> / <code className="rounded bg-muted px-1 font-mono">color</code> — 辩手图标与代表色。</p></li>
                    </ul>
                  </div>
                  <div>
                    <h3 className="font-medium text-foreground mb-2">注意事项</h3>
                    <ul className="space-y-1.5 pl-4 text-xs text-muted-foreground list-disc marker:text-muted-foreground/40">
                      <li>修改后点击「重载」即可生效，无需重启服务。</li>
                      <li>注意 JSON 格式必须合法，否则重载会失败。</li>
                      <li>如修改后效果不理想，可重新编辑后再次重载。</li>
                      <li>新增角色/性格需要前端同步更新对应的渲染逻辑。</li>
                    </ul>
                  </div>
                </div>
              </section>
            </div>
           ) : (
             <ModelColumn
               purpose={activeTab} configs={configs} loading={loading}
               title={activeTab === EMBEDDING ? '嵌入模型配置' : '对话模型配置'}
               desc={activeTab === EMBEDDING ? '配置向量嵌入模型供应商，用于生成图书内容向量。最新更新的启用模型自动激活。' : '配置 AI 对话模型供应商。为每个模型声明 thinkingMode（思考能力），然后在「场景配置」中为每个业务场景独立绑定模型 + 思考参数。'}
               expandedId={expandedId} formPurpose={form.purpose} renderFormContent={renderFormContent} openCreateForm={openCreateForm} rowOpts={rowOpts}
             />
          )}
        </div>

        {/* PC版：三列并排 */}
        <div className="hidden md:grid md:grid-cols-3 md:gap-4 md:items-start">
          <ModelColumn
            purpose={CHAT} configs={chatConfigs} loading={loadingChat}
            title="对话模型配置"
            desc="配置 AI 对话模型供应商。QA：图书问答、AI 助理、圆桌派、奇葩说等大任务；TOOL：内容压缩、元数据推断、查询扩展等后台小任务。各角色唯一，点击即切换。"
            expandedId={expandedId} formPurpose={form.purpose} renderFormContent={renderFormContent} openCreateForm={openCreateForm} rowOpts={rowOpts}
          />
          <ModelColumn
            purpose={EMBEDDING} configs={embeddingConfigs} loading={loadingEmbedding}
            title="嵌入模型配置"
            desc="配置向量嵌入模型供应商，用于生成图书内容向量。点击 ⭐ 切换激活，最新更新的启用模型自动激活。"
            expandedId={expandedId} formPurpose={form.purpose} renderFormContent={renderFormContent} openCreateForm={openCreateForm} rowOpts={rowOpts}
          />
          {/* 角色配置列 */}
          <section className="rounded-xl bg-card shadow-xs">
            <div className="border-b px-4 py-3">
              <h2 className="text-sm font-bold">角色配置热加载</h2>
              <p className="mt-0.5 text-xs text-muted-foreground">编辑 <code className="rounded bg-muted px-1 py-0.5 font-mono">deploy/ai-config.json</code> 后点击重载，无需重启服务。</p>
            </div>
            <div className="px-4 py-3">
              <button onClick={() => reloadAiConfig().then(() => toast.success('角色配置已重载')).catch((e: any) => toast.error(e.message || '重载失败'))} className="flex w-full items-center justify-center gap-1 rounded-lg bg-primary px-5 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 whitespace-nowrap"><RefreshCw className="h-4 w-4" />重载</button>
            </div>
            <div className="px-4 pb-4 space-y-3 text-sm">
              <div>
                <h3 className="font-medium text-foreground mb-2">可修改的内容</h3>
                <ul className="space-y-2.5 pl-4">
                  <li className="list-disc marker:text-muted-foreground/40"><p className="font-medium text-foreground">图书问答风格</p><p className="text-xs text-muted-foreground mt-0.5"><code className="rounded bg-muted px-1 font-mono">bookChat.styles[].prompt</code></p></li>
                  <li className="list-disc marker:text-muted-foreground/40"><p className="font-medium text-foreground">圆桌派角色</p><p className="text-xs text-muted-foreground mt-0.5"><code className="rounded bg-muted px-1 font-mono">roundTable.roles[].prompt</code> / <code className="rounded bg-muted px-1 font-mono">icon</code> / <code className="rounded bg-muted px-1 font-mono">color</code> / <code className="rounded bg-muted px-1 font-mono">params</code></p></li>
                  <li className="list-disc marker:text-muted-foreground/40"><p className="font-medium text-foreground">奇葩说辩手性格</p><p className="text-xs text-muted-foreground mt-0.5"><code className="rounded bg-muted px-1 font-mono">debate.personalities[].*</code></p></li>
                </ul>
              </div>
              <div>
                <h3 className="font-medium text-foreground mb-2">注意事项</h3>
                <ul className="space-y-1 pl-4 text-xs text-muted-foreground list-disc marker:text-muted-foreground/40">
                  <li>修改后点击「重载」即可生效，无需重启服务。</li>
                  <li>注意 JSON 格式必须合法。</li>
                  <li>新增角色/性格需要前端同步更新渲染逻辑。</li>
                </ul>
              </div>
            </div>
          </section>
        </div>
      </main>
    </div>
  )
}
