import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import {
  ArrowLeft, Plus, Trash2, Power, PowerOff, Loader2,
  CheckCircle2, XCircle, Server, Cpu, ChevronDown, Zap, RefreshCw,
} from 'lucide-react'
import {
  getAiConfigs, saveAiConfig, deleteAiConfig,
  enableAiConfig, disableAiConfig, testAiConfig, testAiConnection,
} from '@/api/admin'
import type { AiProviderConfig, AiProviderPreset, AiProviderType, ConnectionTestResult, ThinkingLevel } from '@/types/ai'

/** Thinking 等级选项 */
const THINKING_LEVELS: { value: ThinkingLevel; label: string; desc: string }[] = [
  { value: 'NONE', label: '关闭', desc: '不思考，快速响应（追加 /no_think）' },
  { value: 'LOW', label: '低', desc: '轻度思考，2x 超时' },
  { value: 'MEDIUM', label: '中', desc: '中度思考，4x 超时' },
  { value: 'HIGH', label: '高', desc: '深度思考，8x 超时' },
]

/** 提供商预设列表 */
const PROVIDER_PRESETS: AiProviderPreset[] = [
  { label: 'OpenAI', provider: 'OPENAI', baseUrl: 'https://api.openai.com/v1', modelName: 'gpt-4o-mini', requireApiKey: true },
  { label: 'DeepSeek', provider: 'OPENAI', baseUrl: 'https://api.deepseek.com/v1', modelName: 'deepseek-chat', requireApiKey: true },
  { label: 'DeepSeek-R1 (Thinking)', provider: 'OPENAI', baseUrl: 'https://api.deepseek.com/v1', modelName: 'deepseek-reasoner', requireApiKey: true, thinkingLevel: 'HIGH' },
  { label: '通义千问', provider: 'OPENAI', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', modelName: 'qwen-turbo', requireApiKey: true },
  { label: '智谱 GLM', provider: 'OPENAI', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', modelName: 'glm-4-flash', requireApiKey: true },
  { label: 'Ollama（本地）', provider: 'OLLAMA', baseUrl: 'http://localhost:11434', modelName: 'qwen2.5:7b', requireApiKey: false },
]

/** 空配置模板 */
const EMPTY_CONFIG: Partial<AiProviderConfig> = {
  provider: 'OPENAI',
  configName: '',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: '',
  modelName: 'gpt-4o-mini',
  temperature: 0.7,
  maxTokens: 2048,
  thinkingLevel: 'NONE',
  enabled: false,
}

export default function AdminAiConfigPage() {
  const navigate = useNavigate()
  const [configs, setConfigs] = useState<AiProviderConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [editingConfig, setEditingConfig] = useState<Partial<AiProviderConfig> | null>(null)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState<number | 'new' | null>(null)
  const [testResult, setTestResult] = useState<Map<number | string, ConnectionTestResult>>(new Map())
  const [showPresets, setShowPresets] = useState(false)

  const loadConfigs = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getAiConfigs()
      setConfigs(data || [])
    } catch {
      toast.error('加载配置失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadConfigs()
  }, [loadConfigs])

  // 保存配置
  const handleSave = async () => {
    if (!editingConfig) return
    if (!editingConfig.configName?.trim()) {
      toast.error('请填写配置名称')
      return
    }
    if (!editingConfig.baseUrl?.trim()) {
      toast.error('请填写端点地址')
      return
    }
    if (!editingConfig.modelName?.trim()) {
      toast.error('请填写模型名称')
      return
    }
    if (editingConfig.provider === 'OPENAI' && !editingConfig.apiKey?.trim()) {
      toast.error('OpenAI 兼容 API 需要填写 API Key')
      return
    }

    setSaving(true)
    try {
      await saveAiConfig(editingConfig as AiProviderConfig)
      toast.success('配置已保存')
      setEditingConfig(null)
      loadConfigs()
    } catch (e: any) {
      toast.error(e?.message || '保存失败')
    } finally {
      setSaving(false)
    }
  }

  // 删除配置
  const handleDelete = async (id: number) => {
    if (!confirm('确定删除此配置？')) return
    try {
      await deleteAiConfig(id)
      toast.success('已删除')
      loadConfigs()
    } catch {
      toast.error('删除失败')
    }
  }

  // 启用/禁用
  const handleToggleEnabled = async (config: AiProviderConfig) => {
    try {
      if (config.enabled) {
        await disableAiConfig(config.id!)
        toast.success('已禁用')
      } else {
        await enableAiConfig(config.id!)
        toast.success('已启用，其他配置已自动禁用')
      }
      loadConfigs()
    } catch {
      toast.error('操作失败')
    }
  }

  // 连接测试
  const handleTest = async (config: Partial<AiProviderConfig>, configId?: number) => {
    const testKey = configId || 'new'
    setTesting(testKey)
    setTestResult((prev) => { const m = new Map(prev); m.delete(testKey); return m })

    try {
      let result: ConnectionTestResult
      if (configId) {
        result = await testAiConfig(configId)
      } else {
        result = await testAiConnection(config as AiProviderConfig)
      }
      setTestResult((prev) => new Map(prev).set(testKey, result))
      if (result.success) {
        toast.success('连接测试成功')
      } else {
        toast.error(result.message)
      }
    } catch (e: any) {
      const failResult: ConnectionTestResult = { success: false, message: e?.message || '测试失败' }
      setTestResult((prev) => new Map(prev).set(testKey, failResult))
      toast.error('连接测试失败')
    } finally {
      setTesting(null)
    }
  }

  // 选择预设
  const handleSelectPreset = (preset: AiProviderPreset) => {
    if (!editingConfig) return
    setEditingConfig({
      ...editingConfig,
      provider: preset.provider,
      baseUrl: preset.baseUrl,
      modelName: preset.modelName,
      apiKey: preset.requireApiKey ? editingConfig.apiKey : '',
      thinkingLevel: preset.thinkingLevel || 'NONE',
    })
    setShowPresets(false)
  }

  // 新建配置
  const handleNew = () => {
    setEditingConfig({ ...EMPTY_CONFIG })
    setTestResult(new Map())
  }

  // 编辑配置
  const handleEdit = (config: AiProviderConfig) => {
    setEditingConfig({ ...config })
    setTestResult(new Map())
  }

  return (
    <div className="min-h-screen bg-background">
      {/* 顶部 */}
      <header className="sticky top-0 z-10 border-b border-border/50 bg-background/80 backdrop-blur-xl">
        <div className="flex items-center gap-3 px-4 py-3">
          <button onClick={() => navigate(-1)} className="flex h-9 w-9 items-center justify-center rounded-xl hover:bg-muted">
            <ArrowLeft className="h-5 w-5" />
          </button>
          <h1 className="text-lg font-semibold">AI 模型配置</h1>
          <button onClick={loadConfigs} className="ml-auto">
            <RefreshCw className="h-4 w-4 text-muted-foreground" />
          </button>
        </div>
      </header>

      <div className="px-4 py-4 space-y-4">
        {/* 当前活跃配置 */}
        {configs.filter((c) => c.enabled).length > 0 && (
          <div className="rounded-xl bg-green-50 p-3">
            <div className="flex items-center gap-2">
              <Zap className="h-4 w-4 text-green-600" />
              <span className="text-sm font-medium text-green-700">
                当前使用：{configs.find((c) => c.enabled)?.configName}
              </span>
              <span className="text-xs text-green-600">
                ({configs.find((c) => c.enabled)?.provider} / {configs.find((c) => c.enabled)?.modelName})
              </span>
            </div>
          </div>
        )}

        {/* 新建按钮 */}
        {!editingConfig && (
          <button
            onClick={handleNew}
            className="flex w-full items-center justify-center gap-2 rounded-xl border-2 border-dashed border-muted-foreground/20 py-3 text-sm text-muted-foreground hover:border-primary hover:text-primary"
          >
            <Plus className="h-4 w-4" />
            添加新配置
          </button>
        )}

        {/* 配置列表 */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : (
          configs.map((config) => (
            <div
              key={config.id}
              className={`rounded-xl bg-card p-4 shadow-xs border-l-4 ${
                config.enabled ? 'border-l-green-500' : 'border-l-transparent'
              }`}
            >
              <div className="flex items-start justify-between">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    {config.provider === 'OLLAMA' ? (
                      <Cpu className="h-4 w-4 text-orange-500" />
                    ) : (
                      <Server className="h-4 w-4 text-blue-500" />
                    )}
                    <span className="font-medium text-sm">{config.configName}</span>
                    {config.enabled && (
                      <span className="rounded-full bg-green-100 px-2 py-0.5 text-[10px] font-medium text-green-700">
                        使用中
                      </span>
                    )}
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground truncate">
                    {config.provider === 'OLLAMA' ? 'Ollama' : 'OpenAI 兼容'} · {config.modelName}
                    {config.thinkingLevel && config.thinkingLevel !== 'NONE' && (
                      <span className="ml-1 rounded-full bg-purple-100 px-1.5 py-0.5 text-[10px] font-medium text-purple-700">
                        Thinking: {config.thinkingLevel}
                      </span>
                    )}
                  </p>
                  <p className="text-xs text-muted-foreground truncate">{config.baseUrl}</p>
                </div>
              </div>

              {/* 连接测试结果 */}
              {testResult.has(config.id!) && (
                <div className={`mt-2 flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-xs ${
                  testResult.get(config.id!)?.success
                    ? 'bg-green-50 text-green-700'
                    : 'bg-red-50 text-red-700'
                }`}>
                  {testResult.get(config.id!)?.success ? (
                    <CheckCircle2 className="h-3.5 w-3.5 flex-shrink-0" />
                  ) : (
                    <XCircle className="h-3.5 w-3.5 flex-shrink-0" />
                  )}
                  <span className="truncate">{testResult.get(config.id!)?.message}</span>
                  {testResult.get(config.id!)?.reply && (
                    <span className="truncate text-muted-foreground ml-1">— "{testResult.get(config.id!)?.reply}"</span>
                  )}
                </div>
              )}

              {/* 操作按钮 */}
              <div className="mt-3 flex items-center gap-2">
                <button
                  onClick={() => handleTest(config, config.id!)}
                  disabled={testing === config.id}
                  className="flex items-center gap-1 rounded-lg bg-blue-50 px-2.5 py-1.5 text-xs font-medium text-blue-600 hover:bg-blue-100 disabled:opacity-50"
                >
                  {testing === config.id ? <Loader2 className="h-3 w-3 animate-spin" /> : <Zap className="h-3 w-3" />}
                  测试连接
                </button>
                <button
                  onClick={() => handleToggleEnabled(config)}
                  className={`flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-medium ${
                    config.enabled
                      ? 'bg-orange-50 text-orange-600 hover:bg-orange-100'
                      : 'bg-green-50 text-green-600 hover:bg-green-100'
                  }`}
                >
                  {config.enabled ? <PowerOff className="h-3 w-3" /> : <Power className="h-3 w-3" />}
                  {config.enabled ? '禁用' : '启用'}
                </button>
                <button
                  onClick={() => handleEdit(config)}
                  className="flex items-center gap-1 rounded-lg bg-muted px-2.5 py-1.5 text-xs font-medium text-foreground hover:bg-muted/80"
                >
                  编辑
                </button>
                <button
                  onClick={() => handleDelete(config.id!)}
                  className="flex items-center gap-1 rounded-lg bg-red-50 px-2.5 py-1.5 text-xs font-medium text-red-600 hover:bg-red-100 ml-auto"
                >
                  <Trash2 className="h-3 w-3" />
                </button>
              </div>
            </div>
          ))
        )}

        {/* 编辑/新建表单 */}
        {editingConfig && (
          <div className="rounded-xl bg-card p-4 shadow-xs border border-primary/20">
            <h3 className="mb-3 text-sm font-semibold">
              {editingConfig.id ? '编辑配置' : '新建配置'}
            </h3>

            <div className="space-y-4">
              {/* 快速预设 */}
              {!editingConfig.id && (
                <div>
                  <label className="mb-2 block text-xs font-medium text-muted-foreground">快速预设</label>
                  <div className="relative">
                    <button
                      onClick={() => setShowPresets(!showPresets)}
                      className="flex w-full items-center justify-between rounded-lg border bg-background px-3 py-2.5 text-sm"
                    >
                      <span className="flex items-center gap-2">
                        {editingConfig.provider === 'OLLAMA' ? (
                          <Cpu className="h-4 w-4 text-orange-500" />
                        ) : (
                          <Server className="h-4 w-4 text-blue-500" />
                        )}
                        选择预设模板...
                      </span>
                      <ChevronDown className="h-4 w-4 text-muted-foreground" />
                    </button>
                    {showPresets && (
                      <div className="absolute inset-x-0 top-full z-10 mt-1 rounded-lg border bg-popover shadow-lg">
                        {PROVIDER_PRESETS.map((preset) => (
                          <button
                            key={preset.label}
                            onClick={() => handleSelectPreset(preset)}
                            className="flex w-full items-center gap-2 px-3 py-2.5 text-sm hover:bg-muted"
                          >
                            {preset.provider === 'OLLAMA' ? (
                              <Cpu className="h-4 w-4 text-orange-500" />
                            ) : (
                              <Server className="h-4 w-4 text-blue-500" />
                            )}
                            <span className="flex-1 text-left">{preset.label}</span>
                            {preset.thinkingLevel && preset.thinkingLevel !== 'NONE' && (
                              <span className="rounded-full bg-purple-100 px-1.5 py-0.5 text-[10px] font-medium text-purple-700">
                                Thinking
                              </span>
                            )}
                            <span className="text-xs text-muted-foreground">{preset.modelName}</span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* 提供商快速切换 */}
                  <div className="mt-2 flex gap-2">
                    {(['OPENAI', 'OLLAMA'] as AiProviderType[]).map((type) => (
                      <button
                        key={type}
                        onClick={() => {
                          const preset = PROVIDER_PRESETS.find((p) => p.provider === type)
                          if (preset) handleSelectPreset(preset)
                        }}
                        className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                          editingConfig.provider === type
                            ? 'bg-primary text-primary-foreground'
                            : 'bg-muted text-muted-foreground hover:bg-muted/80'
                        }`}
                      >
                        {type === 'OPENAI' ? 'OpenAI 兼容' : 'Ollama'}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* 配置名称 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">配置名称</label>
                <input
                  type="text"
                  value={editingConfig.configName || ''}
                  onChange={(e) => setEditingConfig((prev) => ({ ...prev!, configName: e.target.value }))}
                  placeholder="如：DeepSeek-Chat、本地 Qwen2.5"
                  className="w-full rounded-lg border bg-background px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/20"
                />
              </div>

              {/* 端点地址 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">
                  {editingConfig.provider === 'OLLAMA' ? 'Ollama 端点地址' : 'API Base URL'}
                </label>
                <input
                  type="text"
                  value={editingConfig.baseUrl || ''}
                  onChange={(e) => setEditingConfig((prev) => ({ ...prev!, baseUrl: e.target.value }))}
                  placeholder={editingConfig.provider === 'OLLAMA' ? 'http://localhost:11434' : 'https://api.openai.com/v1'}
                  className="w-full rounded-lg border bg-background px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/20"
                />
              </div>

              {/* API Key（仅 OpenAI） */}
              {editingConfig.provider === 'OPENAI' && (
                <div>
                  <label className="mb-2 block text-xs font-medium text-muted-foreground">API Key</label>
                  <input
                    type="password"
                    value={editingConfig.apiKey || ''}
                    onChange={(e) => setEditingConfig((prev) => ({ ...prev!, apiKey: e.target.value }))}
                    placeholder="sk-..."
                    className="w-full rounded-lg border bg-background px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/20"
                  />
                </div>
              )}

              {/* 模型名称 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">模型名称</label>
                <input
                  type="text"
                  value={editingConfig.modelName || ''}
                  onChange={(e) => setEditingConfig((prev) => ({ ...prev!, modelName: e.target.value }))}
                  placeholder={editingConfig.provider === 'OLLAMA' ? 'qwen2.5:7b' : 'gpt-4o-mini'}
                  className="w-full rounded-lg border bg-background px-3 py-2.5 text-sm outline-none focus:ring-2 focus:ring-primary/20"
                />
                {editingConfig.provider === 'OLLAMA' && (
                  <p className="mt-1 text-xs text-muted-foreground">
                    填写 Ollama 已下载的模型，如 qwen2.5:7b、llama3.1:8b
                  </p>
                )}
              </div>

              {/* 温度 */}
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <label className="text-xs font-medium text-muted-foreground">温度</label>
                  <span className="text-xs font-medium">{editingConfig.temperature?.toFixed(1)}</span>
                </div>
                <input
                  type="range"
                  min={0}
                  max={2}
                  step={0.1}
                  value={editingConfig.temperature ?? 0.7}
                  onChange={(e) => setEditingConfig((prev) => ({ ...prev!, temperature: Number(e.target.value) }))}
                  className="w-full accent-primary"
                />
                <div className="mt-1 flex justify-between text-[10px] text-muted-foreground">
                  <span>精确</span>
                  <span>平衡</span>
                  <span>创意</span>
                </div>
              </div>

              {/* Thinking 等级 */}
              <div>
                <div className="mb-2 flex items-center justify-between">
                  <label className="text-xs font-medium text-muted-foreground">Thinking 等级</label>
                  <span className="text-xs text-muted-foreground">
                    {THINKING_LEVELS.find(l => l.value === (editingConfig.thinkingLevel || 'NONE'))?.desc}
                  </span>
                </div>
                <div className="flex gap-2">
                  {THINKING_LEVELS.map((level) => (
                    <button
                      key={level.value}
                      type="button"
                      onClick={() => setEditingConfig((prev) => ({ ...prev!, thinkingLevel: level.value }))}
                      className={`flex-1 rounded-lg py-2 text-xs font-medium transition-colors ${
                        (editingConfig.thinkingLevel || 'NONE') === level.value
                          ? 'bg-primary text-primary-foreground'
                          : 'bg-muted text-muted-foreground hover:bg-muted/80'
                      }`}
                    >
                      {level.label}
                    </button>
                  ))}
                </div>
                <p className="mt-1.5 text-[10px] text-muted-foreground">
                  关闭思考时自动追加 /no_think 指令，Qwen3/DeepSeek-R1 等模型将跳过思考直接回答
                </p>
              </div>

              {/* 连接测试结果（新建时） */}
              {testResult.has('new') && (
                <div className={`flex items-center gap-2 rounded-lg px-3 py-2 text-xs ${
                  testResult.get('new')?.success
                    ? 'bg-green-50 text-green-700'
                    : 'bg-red-50 text-red-700'
                }`}>
                  {testResult.get('new')?.success ? (
                    <CheckCircle2 className="h-3.5 w-3.5 flex-shrink-0" />
                  ) : (
                    <XCircle className="h-3.5 w-3.5 flex-shrink-0" />
                  )}
                  <span className="truncate">{testResult.get('new')?.message}</span>
                </div>
              )}

              {/* 操作按钮 */}
              <div className="flex gap-3">
                <button
                  onClick={() => setEditingConfig(null)}
                  className="flex-1 rounded-lg bg-muted py-2.5 text-sm font-medium text-foreground hover:bg-muted/80"
                >
                  取消
                </button>
                <button
                  onClick={() => handleTest(editingConfig)}
                  disabled={testing === 'new'}
                  className="flex items-center gap-1 rounded-lg bg-blue-500 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-600 disabled:opacity-50"
                >
                  {testing === 'new' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Zap className="h-3.5 w-3.5" />}
                  测试
                </button>
                <button
                  onClick={handleSave}
                  disabled={saving}
                  className="flex-1 rounded-lg bg-primary py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                >
                  {saving ? '保存中...' : '保存'}
                </button>
              </div>
            </div>
          </div>
        )}

        {/* 说明 */}
        <div className="rounded-xl bg-muted/50 p-4">
          <h3 className="mb-2 text-sm font-semibold">使用说明</h3>
          <ul className="space-y-1.5 text-xs text-muted-foreground">
            <li className="flex items-center gap-2">
              <Server className="h-4 w-4 text-blue-500 flex-shrink-0" />
              <span><strong>OpenAI 兼容</strong> — 支持 OpenAI / DeepSeek / 通义千问 / 智谱等 API</span>
            </li>
            <li className="flex items-center gap-2">
              <Cpu className="h-4 w-4 text-orange-500 flex-shrink-0" />
              <span><strong>Ollama</strong> — 本地部署模型，运行 <code className="rounded bg-muted px-1">ollama pull qwen2.5:7b</code> 下载</span>
            </li>
            <li>全局只能启用一个配置，启用新配置会自动禁用旧配置</li>
            <li>保存配置前建议先点击"测试"确认连接正常</li>
            <li>Ollama 服务需与后端在同一网络，或填写可访问的地址</li>
          </ul>
        </div>
      </div>
    </div>
  )
}
