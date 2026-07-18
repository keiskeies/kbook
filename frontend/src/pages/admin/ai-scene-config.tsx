import { useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Link2, Unlink, Settings2 } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import {
  listAiScenes, bindAiScene, unbindAiScene, listAiConfigs,
  type AiSceneView, type AiProviderConfig, type BindSceneRequest,
} from '@/api/aiConfig'
import { ROUTES } from '@/constants'

const CATEGORY_LABEL: Record<string, string> = {
  QA: '大型问答(带思考)',
  QA_WITHOUT_THINKING: '大型问答(无思考)',
  TOOL: '小型工具',
  COMPRESSION: '压缩',
  VISION: 'OCR视觉',
  EMBEDDING: '向量嵌入',
}

const CATEGORY_COLOR: Record<string, string> = {
  QA: 'bg-blue-500/10 text-blue-600 border-blue-500/30',
  QA_WITHOUT_THINKING: 'bg-cyan-500/10 text-cyan-600 border-cyan-500/30',
  TOOL: 'bg-amber-500/10 text-amber-600 border-amber-500/30',
  COMPRESSION: 'bg-purple-500/10 text-purple-600 border-purple-500/30',
  VISION: 'bg-pink-500/10 text-pink-600 border-pink-500/30',
  EMBEDDING: 'bg-emerald-500/10 text-emerald-600 border-emerald-500/30',
}

const THINKING_MODE_LABEL: Record<string, string> = {
  NONE: '不支持思考',
  SWITCH: '开关模式',
  REASONING_EFFORT: '强度调节',
  THINKING_BUDGET: '预算模式',
}

export default function AiSceneConfigPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [editingScene, setEditingScene] = useState<string | null>(null)
  // 编辑态思考参数（独立的 state，绑定提交时一起发送）
  const [editThinking, setEditThinking] = useState<{
    thinkingEnabled: boolean | null
    reasoningEffort: string | null
    thinkingBudget: number | null
  }>({ thinkingEnabled: null, reasoningEffort: null, thinkingBudget: null })

  const { data: scenes = [], isLoading } = useQuery({
    queryKey: ['admin', 'ai-scene-config'],
    queryFn: listAiScenes,
  })

  const { data: allConfigs = [] } = useQuery({
    queryKey: ['admin', 'ai-config', 'all'],
    queryFn: () => listAiConfigs().then(d => Array.isArray(d) ? d : []),
  })

  const bindMutation = useMutation({
    mutationFn: ({ sceneKey, configId, req }: { sceneKey: string; configId: number; req: BindSceneRequest }) =>
      bindAiScene(sceneKey, configId, req),
    onSuccess: () => {
      toast.success('场景绑定已更新')
      queryClient.invalidateQueries({ queryKey: ['admin', 'ai-scene-config'] })
      setEditingScene(null)
    },
    onError: (err: any) => toast.error(err.message || '绑定失败'),
  })

  const unbindMutation = useMutation({
    mutationFn: (sceneKey: string) => unbindAiScene(sceneKey),
    onSuccess: () => {
      toast.success('已清除绑定，回退到默认分类')
      queryClient.invalidateQueries({ queryKey: ['admin', 'ai-scene-config'] })
    },
    onError: (err: any) => toast.error(err.message || '清除失败'),
  })

  const openEdit = (scene: AiSceneView) => {
    setEditingScene(scene.sceneKey)
    setEditThinking({
      thinkingEnabled: scene.thinkingEnabled ?? scene.thinking,
      reasoningEffort: scene.reasoningEffort ?? null,
      thinkingBudget: scene.thinkingBudget ?? null,
    })
  }

  const grouped = groupByDomain(scenes)

  return (
    <div className="absolute inset-0 flex flex-col overflow-hidden bg-background overscroll-contain">
      <header className="shrink-0 flex items-center gap-3 border-b bg-navbar/95 px-4 md:px-6 lg:px-8 py-3 backdrop-blur supports-[backdrop-filter]:bg-navbar/60 z-20">
        <button onClick={() => navigate(ROUTES.ADMIN_AI_CONFIG)} className="rounded-full p-1.5 active:bg-muted">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <h1 className="text-h3 font-bold flex-1">AI 场景配置</h1>
        <button onClick={() => navigate(ROUTES.ADMIN_AI_CONFIG)} className="flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs font-medium hover:bg-muted">
          <Settings2 className="h-3.5 w-3.5" />配置管理
        </button>
      </header>

      <main className="flex-1 overflow-y-auto overscroll-contain px-4 md:px-6 lg:px-8 py-4">
        <div className="max-w-5xl mx-auto space-y-6">
          <section className="rounded-xl bg-card shadow-xs p-4 border border-border">
            <h2 className="text-sm font-bold mb-1.5">每个业务场景独立配置 AI 模型 + 思考参数</h2>
            <p className="text-xs text-muted-foreground leading-relaxed">
              共 {scenes.length} 个场景。未显式绑定的场景自动回退到默认分类（QA/TOOL/COMPRESSION 等）。
              <strong className="text-foreground">思考参数与 AI 配置的 thinkingMode 联动</strong>：
              配置声明模式（开关/强度/预算），场景按模式配置具体值。如配置是 SWITCH，场景只能选开/关；
              配置是 REASONING_EFFORT，场景可选 low/medium/high。
            </p>
          </section>

          {isLoading ? (
            <div className="text-center py-12 text-sm text-muted-foreground">加载中...</div>
          ) : (
            Object.entries(grouped).map(([domain, domainScenes]) => (
              <section key={domain} className="rounded-xl bg-card shadow-xs overflow-hidden">
                <div className="border-b bg-muted/30 px-4 py-2.5">
                  <h3 className="text-sm font-bold">{domain}</h3>
                  <span className="text-xs text-muted-foreground">{domainScenes.length} 个场景</span>
                </div>
                <div className="divide-y divide-border">
                  {domainScenes.map(scene => (
                    <SceneRow
                      key={scene.sceneKey}
                      scene={scene}
                      configs={allConfigs}
                      isEditing={editingScene === scene.sceneKey}
                      editThinking={editThinking}
                      setEditThinking={setEditThinking}
                      onEdit={() => editingScene === scene.sceneKey ? setEditingScene(null) : openEdit(scene)}
                      onBind={(configId) => bindMutation.mutate({
                        sceneKey: scene.sceneKey,
                        configId,
                        req: editThinking,
                      })}
                      onUnbind={() => unbindMutation.mutate(scene.sceneKey)}
                      binding={bindMutation.isPending}
                      unbinding={unbindMutation.isPending}
                    />
                  ))}
                </div>
              </section>
            ))
          )}
        </div>
      </main>
    </div>
  )
}

function SceneRow({
  scene, configs, isEditing, editThinking, setEditThinking,
  onEdit, onBind, onUnbind, binding, unbinding,
}: {
  scene: AiSceneView
  configs: AiProviderConfig[]
  isEditing: boolean
  editThinking: { thinkingEnabled: boolean | null; reasoningEffort: string | null; thinkingBudget: number | null }
  setEditThinking: Dispatch<SetStateAction<{ thinkingEnabled: boolean | null; reasoningEffort: string | null; thinkingBudget: number | null }>>
  onEdit: () => void
  onBind: (configId: number) => void
  onUnbind: () => void
  binding: boolean
  unbinding: boolean
}) {
  const enabledConfigs = configs.filter(c => c.enabled)
  // 联动：根据当前生效配置的 thinkingMode 决定显示哪些思考选项
  // 编辑态使用 editThinking；非编辑态使用 scene 的实际值
  const thinkingMode = scene.boundThinkingMode ?? 'SWITCH'

  return (
    <div className="px-4 py-3">
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-sm font-medium">{scene.displayName}</span>
            <code className="rounded bg-muted px-1.5 py-0.5 text-[10px] font-mono text-muted-foreground">{scene.sceneKey}</code>
            <span className={`rounded border px-1.5 py-0.5 text-[10px] font-medium ${CATEGORY_COLOR[scene.defaultCategory] || 'bg-muted text-muted-foreground border-border'}`}>
              {CATEGORY_LABEL[scene.defaultCategory] || scene.defaultCategory}
            </span>
            {scene.streaming && (
              <span className="rounded bg-indigo-500/10 text-indigo-600 border border-indigo-500/30 px-1.5 py-0.5 text-[10px] font-medium">流式</span>
            )}
          </div>
          <div className="mt-1.5 text-xs">
            {scene.boundConfigId ? (
              <span className={scene.explicitlyBound ? 'text-foreground' : 'text-muted-foreground italic'}>
                {scene.explicitlyBound ? '✓ ' : '↪ 默认: '}
                {scene.boundConfigName}
                <span className="text-muted-foreground"> · {scene.boundProvider}/{scene.boundModelName}</span>
                {scene.boundEnabled === false && <span className="ml-1 text-red-500">(已禁用)</span>}
              </span>
            ) : (
              <span className="text-muted-foreground italic">未配置（回退失败）</span>
            )}
          </div>
          {/* 思考参数显示 */}
          {scene.explicitlyBound && thinkingMode !== 'NONE' && (
            <div className="mt-1 text-[11px] text-muted-foreground">
              思考: {THINKING_MODE_LABEL[thinkingMode]}
              {scene.thinkingEnabled != null && ` · ${scene.thinkingEnabled ? '开启' : '关闭'}`}
              {scene.reasoningEffort && ` · ${scene.reasoningEffort}`}
              {scene.thinkingBudget != null && ` · budget=${scene.thinkingBudget}`}
            </div>
          )}
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button onClick={onEdit} className="rounded-lg border border-border px-2.5 py-1 text-xs hover:bg-muted flex items-center gap-1">
            <Link2 className="h-3 w-3" />{scene.explicitlyBound ? '换绑' : '绑定'}
          </button>
          {scene.explicitlyBound && (
            <button
              onClick={onUnbind}
              disabled={unbinding}
              className="rounded-lg border border-border px-2 py-1 text-xs hover:bg-muted text-red-600 disabled:opacity-50 flex items-center gap-1"
            >
              <Unlink className="h-3 w-3" />
            </button>
          )}
        </div>
      </div>

      {isEditing && (
        <div className="mt-3 pt-3 border-t border-border space-y-3">
          {/* 配置选择 */}
          <div>
            <div className="text-xs font-medium text-muted-foreground mb-2">选择配置（仅显示启用的配置）</div>
            {enabledConfigs.length === 0 ? (
              <div className="text-xs text-muted-foreground py-2">暂无启用的配置，请先在「配置管理」中创建并启用。</div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-1.5 max-h-48 overflow-y-auto">
                {enabledConfigs.map(c => (
                  <button
                    key={c.id}
                    onClick={() => onBind(c.id!)}
                    disabled={binding}
                    className={`text-left rounded-lg border px-3 py-2 text-xs transition-colors disabled:opacity-50 ${
                      scene.boundConfigId === c.id
                        ? 'border-primary bg-primary/10'
                        : 'border-border hover:bg-muted'
                    }`}
                  >
                    <div className="font-medium">{c.name}</div>
                    <div className="text-muted-foreground mt-0.5">
                      {c.provider}/{c.modelName}
                      {c.roles && <span className="ml-1">· {c.roles}</span>}
                      {c.thinkingMode && <span className="ml-1">· {THINKING_MODE_LABEL[c.thinkingMode] || c.thinkingMode}</span>}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* 联动思考参数表单 */}
          {thinkingMode !== 'NONE' && (
            <div className="rounded-lg bg-muted/30 p-3 space-y-2">
              <div className="text-xs font-medium">思考参数 <span className="text-muted-foreground">（联动模式: {THINKING_MODE_LABEL[thinkingMode]}）</span></div>

              {/* 开/关 toggle — 所有非 NONE 模式通用 */}
              <div className="flex items-center gap-2">
                <span className="text-xs w-20">思考开关</span>
                <div className="flex gap-1">
                  {[
                    { value: true, label: '开启' },
                    { value: false, label: '关闭' },
                    { value: null as boolean | null, label: '默认' },
                  ].map(opt => (
                    <button
                      key={String(opt.value)}
                      onClick={() => setEditThinking((s: typeof editThinking) => ({ ...s, thinkingEnabled: opt.value }))}
                      className={`rounded border px-2.5 py-1 text-[11px] font-medium transition-colors ${
                        editThinking.thinkingEnabled === opt.value
                          ? 'border-primary bg-primary/10 text-primary'
                          : 'border-border text-muted-foreground hover:bg-muted'
                      }`}
                    >{opt.label}</button>
                  ))}
                </div>
                {editThinking.thinkingEnabled === null && (
                  <span className="text-[10px] text-muted-foreground">回退到场景默认 ({scene.thinking ? '开' : '关'})</span>
                )}
              </div>

              {/* REASONING_EFFORT 模式：强度下拉 */}
              {thinkingMode === 'REASONING_EFFORT' && (
                <div className="flex items-center gap-2">
                  <span className="text-xs w-20">思考强度</span>
                  <select
                    value={editThinking.reasoningEffort ?? ''}
                    onChange={e => setEditThinking((s: typeof editThinking) => ({ ...s, reasoningEffort: e.target.value || null }))}
                    className="rounded border border-border bg-background px-2 py-1 text-xs outline-none focus:border-primary"
                  >
                    <option value="">不发送（模型默认）</option>
                    <option value="low">low</option>
                    <option value="medium">medium</option>
                    <option value="high">high</option>
                  </select>
                </div>
              )}

              {/* THINKING_BUDGET 模式：预算输入 */}
              {thinkingMode === 'THINKING_BUDGET' && (
                <div className="flex items-center gap-2">
                  <span className="text-xs w-20">思考预算</span>
                  <input
                    type="number"
                    value={editThinking.thinkingBudget ?? ''}
                    onChange={e => setEditThinking(s => ({ ...s, thinkingBudget: e.target.value === '' ? null : Number(e.target.value) }))}
                    min={0} step={1000}
                    placeholder="留空=不发送"
                    className="rounded border border-border bg-background px-2 py-1 text-xs outline-none focus:border-primary w-32"
                  />
                  <span className="text-[10px] text-muted-foreground">tokens</span>
                </div>
              )}

              <p className="text-[10px] text-muted-foreground">
                选中配置后点击上方配置卡片即应用当前思考参数。
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/** 按业务域分组（基于 sceneKey 前缀） */
function groupByDomain(scenes: AiSceneView[]): Record<string, AiSceneView[]> {
  const groups: Record<string, AiSceneView[]> = {}
  for (const s of scenes) {
    const domain = getDomain(s.sceneKey)
    if (!groups[domain]) groups[domain] = []
    groups[domain].push(s)
  }
  return groups
}

function getDomain(sceneKey: string): string {
  if (sceneKey.startsWith('BOOK_QA') || sceneKey === 'AI_ASSISTANT' || sceneKey === 'ADMIN_ASSISTANT' ||
      sceneKey.startsWith('PRESET') || sceneKey.startsWith('BOOK_SUMMARY') || sceneKey === 'SPEED_READ') {
    return '图书问答域'
  }
  if (sceneKey.startsWith('ROUND_TABLE_')) return '圆桌派域'
  if (sceneKey.startsWith('DEBATE_')) return '辩论域'
  if (sceneKey.startsWith('LIST_QUERY_') || sceneKey === 'QUERY_EXPAND' || sceneKey === 'VECTOR_QUERY_EXPAND' || sceneKey === 'FOLLOW_UP_QUESTION') {
    return 'RAG/检索域'
  }
  if (sceneKey === 'BOOK_METADATA_INFER' || sceneKey === 'BOOK_PARSE_COMBINED' || sceneKey === 'PDF_OCR' || sceneKey === 'EMBEDDING') {
    return '元数据/OCR/嵌入域'
  }
  if (sceneKey.endsWith('_COMPRESSION')) return '压缩域'
  return '其他'
}
