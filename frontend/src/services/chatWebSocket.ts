import SockJS from 'sockjs-client'
import { Client, type IMessage } from '@stomp/stompjs'
import { STORAGE_KEYS } from '@/constants'
import { useChatStore } from '@/store/chat'

class ChatWebSocketService {
  private client: Client | null = null
  private initialized = false
  private onMessageCallback: ((message: any) => void) | null = null

  setOnMessage(callback: ((message: any) => void) | null) {
    this.onMessageCallback = callback
  }

  connect() {
    const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
    if (!token) return

    if (this.initialized) return
    this.initialized = true

    this.client = new Client({
      webSocketFactory: () => new SockJS('/api/ws/chat'),
      connectHeaders: {
        Authorization: `Bearer ${token}`
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        useChatStore.getState().setConnected(true)
        this.client?.subscribe('/user/queue/messages', (msg: IMessage) => {
          try {
            const data = JSON.parse(msg.body)
            this.handleMessage(data)
          } catch { /* ignore */ }
        })
      },
      onDisconnect: () => {
        useChatStore.getState().setConnected(false)
      }
    })

    this.client.activate()
  }

  private handleMessage(message: any) {
    const store = useChatStore.getState()
    const convId = message.conversationId
    
    if (store.currentConversationId !== convId) {
      store.incrementUnread(convId)
    }
    
    store.addMessage(convId, message)
    
    store.updateConversation(convId, {
      lastMessage: message.content || message.fileName || '[文件]',
      updatedAt: message.createdAt
    })

    this.onMessageCallback?.(message)
  }

  disconnect() {
    if (this.client) {
      this.client.deactivate()
      this.client = null
    }
    this.initialized = false
    useChatStore.getState().setConnected(false)
  }
}

export const chatWebSocketService = new ChatWebSocketService()
