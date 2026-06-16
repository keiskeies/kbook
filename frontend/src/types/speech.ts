/** Azure Token 响应 */
export interface AzureTokenResponse {
  token: string
  region: string
}

/** 讯飞签名 URL 响应 */
export interface XfyunAuthResponse {
  wsUrl: string
  appId: string
}

/** 语音服务状态 */
export type SpeechServiceStatus = 'idle' | 'speaking' | 'error'
