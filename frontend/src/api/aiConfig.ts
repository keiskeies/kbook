import request from '@/utils/request'

export interface AiProviderPreset {
  name: string
  id: string
  provider: 'OLLAMA' | 'OPENAI'
  baseUrl: string
  models: { name: string; label: string; free?: boolean; maxTokens?: number }[]
  apiKeyUrl?: string
  tokenPlanUrl?: string
  codingPlanUrl?: string
  websiteUrl?: string
  region: 'CN' | 'GLOBAL'
  description: string
}

export interface AiProviderConfig {
  id?: number
  name: string
  purpose: string
  roles?: string       // "QA,TOOL" comma-separated
  provider: string
  baseUrl: string
  modelName: string
  apiKey?: string
  temperature?: number
  timeout?: number
  toolsEnabled?: boolean | null
  enabled?: boolean
  ragTopK?: number
  maxTokens?: number
  embeddingDimension?: number
  createdAt?: string
  updatedAt?: string
}

export function fetchProviderPresets() {
  return request.get<AiProviderPreset[]>('/ai/providers/presets')
}

export function listAiConfigs() {
  return request.get<AiProviderConfig[]>('/admin/ai-config')
}

export function listAiConfigsByPurpose(purpose: string) {
  return request.get<AiProviderConfig[]>(`/admin/ai-config/purpose/${purpose}`)
}

export function getActiveByPurpose(purpose: string) {
  return request.get<AiProviderConfig>(`/admin/ai-config/${purpose}/active`)
}

export function getActiveByPurposeAndRole(purpose: string, role: string) {
  return request.get<AiProviderConfig>(`/admin/ai-config/${purpose}/active/${role}`)
}

export function createAiConfig(data: Partial<AiProviderConfig>) {
  return request.post<AiProviderConfig>('/admin/ai-config', data)
}

export function updateAiConfig(id: number, data: Partial<AiProviderConfig>) {
  return request.put<AiProviderConfig>(`/admin/ai-config/${id}`, data)
}

export function deleteAiConfig(id: number) {
  return request.delete(`/admin/ai-config/${id}`)
}

/** 激活指定配置（刷新 updatedAt 使其成为最新更新的启用配置） */
export function activateAiConfig(id: number) {
  return request.post<AiProviderConfig>(`/admin/ai-config/${id}/activate`)
}

/** 切换指定配置的角色 (QA / TOOL) — 有该角色则移除，无则添加 */
export function setConfigRole(id: number, role: string) {
  return request.post<AiProviderConfig>(`/admin/ai-config/${id}/set-role/${role}`)
}

export function testAiConfig(id: number) {
  return request.post<string>(`/admin/ai-config/${id}/test`)
}

/** 热加载 ai-config.json 配置文件（管理员在编辑 deploy/ai-config.json 后调用） */
export function reloadAiConfig() {
  return request.post('/admin/ai-config/file/reload')
}
