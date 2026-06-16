import request from '@/utils/request'

export interface TtsConfig {
  id?: number
  name: string
  ttsType: 'LLM' | 'TRADITIONAL' | 'CLONE'
  provider: 'XIAOMI' | 'IFLYTEK' | 'GPT_SOVITS' | 'AZURE' | 'CUSTOM'
  baseUrl?: string
  modelName?: string
  apiKey?: string
  apiSecret?: string
  appId?: string
  voice?: string
  voicePresetId?: string
  language?: string
  speed?: number
  pitch?: number
  enabled?: boolean
  isDefault?: boolean
  streaming?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface GptSovitsVoicePreset {
  id: string
  name: string
  lang: string
  gptCkpt: string
  sovitsPth: string
  refAudioPath: string
  promptText: string
}

export interface TtsSynthesizeRequest {
  text: string
  configId?: number
}

export function listTtsConfigs() {
  return request.get<TtsConfig[]>('/admin/tts-config')
}

export function getActiveTtsConfig() {
  return request.get<TtsConfig>('/tts/config/active')
}

export function getActiveTtsConfigAdmin() {
  return request.get<TtsConfig>('/admin/tts-config/active')
}

export function createTtsConfig(data: TtsConfig) {
  return request.post<TtsConfig>('/admin/tts-config', data)
}

export function updateTtsConfig(id: number, data: TtsConfig) {
  return request.put<TtsConfig>(`/admin/tts-config/${id}`, data)
}

export function deleteTtsConfig(id: number) {
  return request.delete(`/admin/tts-config/${id}`)
}

export function switchDefaultTtsConfig(id: number) {
  return request.post<TtsConfig>(`/admin/tts-config/${id}/switch-default`)
}

export async function synthesizeTts(text: string, configId?: number): Promise<ArrayBuffer> {
  const res = await fetch('/api/tts/synthesize', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${localStorage.getItem('kbook_token')}`,
    },
    body: JSON.stringify({ text, configId } satisfies TtsSynthesizeRequest),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: 'TTS 请求失败' }))
    throw new Error(err.message || 'TTS 请求失败')
  }
  return res.arrayBuffer()
}

export function isStreamingSupported(configId?: number): Promise<boolean> {
  return request.get<boolean>('/tts/streaming-supported', { params: { configId } })
}

export function listGptSovitsVoices(): Promise<GptSovitsVoicePreset[]> {
  return request.get<GptSovitsVoicePreset[]>('/tts/gpt-sovits/voices')
}
