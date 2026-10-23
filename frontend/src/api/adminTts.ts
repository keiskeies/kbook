import request from '@/utils/request'
import { STORAGE_KEYS } from '@/constants'

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

/** 渐进式播放凭据 */
export interface TtsStreamTicket {
  /** 凭据ID，用于拼接音源地址 */
  ticketId: string
  /** 音频容器类型（audio/mpeg / audio/wav） */
  contentType: string
}

/**
 * 申请渐进式播放凭据。
 * <p>
 * `&lt;audio&gt;` 的 `src` 只能发 GET 且无法携带自定义请求头，长文本直接放 query
 * 会超出 URL 长度限制。因此先 POST 换取一个短 ID，再用它作为音源地址。
 *
 * @param text     待朗读纯文本
 * @param configId TTS 配置ID，省略时使用当前默认配置
 */
export function prepareTtsStream(text: string, configId?: number): Promise<TtsStreamTicket> {
  return request.post<TtsStreamTicket>('/tts/stream/prepare', { text, configId } satisfies TtsSynthesizeRequest)
}

/**
 * 构造渐进式播放音源地址。
 * <p>
 * 必须带 `?token=`：audio 元素无法设置 Authorization 头，后端
 * `JwtAuthenticationFilter` 已支持从 query 读取 token。
 * 用相对路径，交给当前页面的同源策略与代理处理。
 *
 * @param ticketId 凭据ID
 * @param mode     `progressive`（默认，边合成边播，首帧最快）或
 *                 `complete`（补齐全部分段后整段返回，支持任意位置跳转）
 */
export function buildTtsStreamUrl(ticketId: string, mode: 'progressive' | 'complete' = 'progressive'): string {
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN) ?? ''
  const params = new URLSearchParams()
  if (token) params.set('token', token)
  if (mode === 'complete') params.set('mode', 'complete')
  const query = params.toString()
  return `/api/tts/stream/${encodeURIComponent(ticketId)}${query ? `?${query}` : ''}`
}

/**
 * 释放播放凭据，避免服务端临时记录长期滞留。
 *
 * @param ticketId 凭据ID
 */
export function releaseTtsStream(ticketId: string): Promise<void> {
  return request.delete(`/tts/stream/${encodeURIComponent(ticketId)}`)
}

export function listGptSovitsVoices(): Promise<GptSovitsVoicePreset[]> {
  return request.get<GptSovitsVoicePreset[]>('/tts/gpt-sovits/voices')
}
