import request from '@/utils/request'
import type { AzureTokenResponse, XfyunAuthResponse } from '@/types/speech'

/** 获取 Azure Speech 临时 Token */
export async function getAzureToken(): Promise<AzureTokenResponse> {
  const res = await request.get<AzureTokenResponse>('/speech/azure/token')
  return res
}

/** 获取讯飞 WebSocket 签名 URL */
export async function getXfyunAuth(): Promise<XfyunAuthResponse> {
  const res = await request.post<XfyunAuthResponse>('/speech/xfyun/auth')
  return res
}
