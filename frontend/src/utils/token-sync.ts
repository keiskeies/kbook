import { STORAGE_KEYS } from '@/constants'

/**
 * 多标签页 Token 同步机制
 *
 * 使用 BroadcastChannel + storage 事件双重保障：
 * - BroadcastChannel：同域不同标签页实时通信（现代浏览器支持）
 * - storage 事件：localStorage 变更时触发（兼容性更好，但只在不同标签页触发）
 *
 * 场景：标签页A刷新 token 后，标签页B收到通知更新本地 token
 */

const CHANNEL_NAME = 'kbook_token_sync'

interface TokenSyncMessage {
  type: 'token-updated' | 'token-cleared'
  token?: string
  refreshToken?: string
}

let bc: BroadcastChannel | null = null

function getBroadcastChannel(): BroadcastChannel | null {
  if (typeof BroadcastChannel === 'undefined') return null
  if (!bc) {
    try {
      bc = new BroadcastChannel(CHANNEL_NAME)
    } catch {
      return null
    }
  }
  return bc
}

/**
 * 广播 token 更新消息到其他标签页
 */
export function broadcastTokenUpdate(token: string, refreshToken: string) {
  const channel = getBroadcastChannel()
  if (channel) {
    channel.postMessage({
      type: 'token-updated',
      token,
      refreshToken,
    } as TokenSyncMessage)
  }
}

/**
 * 广播 token 清除消息到其他标签页
 */
export function broadcastTokenCleared() {
  const channel = getBroadcastChannel()
  if (channel) {
    channel.postMessage({
      type: 'token-cleared',
    } as TokenSyncMessage)
  }
}

/**
 * 注册 token 同步监听器
 * 应在应用初始化时调用一次
 */
export function initTokenSyncListener(
  onTokenUpdated: (token: string, refreshToken: string) => void,
  onTokenCleared: () => void,
) {
  // BroadcastChannel 监听（实时，同域所有标签页）
  const channel = getBroadcastChannel()
  if (channel) {
    channel.onmessage = (event) => {
      const msg = event.data as TokenSyncMessage
      if (msg.type === 'token-updated' && msg.token && msg.refreshToken) {
        onTokenUpdated(msg.token, msg.refreshToken)
      } else if (msg.type === 'token-cleared') {
        onTokenCleared()
      }
    }
  }

  // storage 事件监听（兼容性兜底，不同标签页 localStorage 变更时触发）
  window.addEventListener('storage', (event) => {
    if (event.key === STORAGE_KEYS.TOKEN) {
      if (event.newValue) {
        const refreshToken = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN)
        if (refreshToken) {
          onTokenUpdated(event.newValue, refreshToken)
        }
      } else {
        onTokenCleared()
      }
    }
  })
}

/**
 * 清理 BroadcastChannel（应用卸载时调用）
 */
export function cleanupTokenSync() {
  if (bc) {
    bc.close()
    bc = null
  }
}
