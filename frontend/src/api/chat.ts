import request from '@/utils/request'

export interface ChatMessageVO {
  id: number
  conversationId: number
  senderId: number
  recipientId: number
  messageType: 'TEXT' | 'IMAGE' | 'VOICE' | 'FILE'
  content: string | null
  fileName: string | null
  fileSize: number | null
  fileUrl: string | null
  voiceDuration: number | null
  read: boolean
  createdAt: string
  isPending?: boolean
  isFailed?: boolean
  tempId?: string
}

export interface ConversationVO {
  id: number
  otherUserId: number
  otherUserNickname: string
  otherUserAvatar: string | null
  lastMessage: string | null
  unreadCount: number
  updatedAt: string
}

export interface UploadResult {
  url: string
}

export function getConversations() {
  return request.get<ConversationVO[]>('/chat/conversations')
}

export function searchConversations(keyword: string) {
  return request.get<ConversationVO[]>('/chat/conversations/search', {
    params: { keyword }
  })
}

export function getConversation(conversationId: number) {
  return request.get<ConversationVO>(`/chat/conversations/${conversationId}`)
}

export function startConversation(recipientId: number) {
  return request.post<ConversationVO>('/chat/conversations', null, { params: { recipientId } })
}

export function getMessages(conversationId: number, beforeId: number | null | undefined, limit: number = 20): Promise<ChatMessageVO[]> {
  const params: Record<string, any> = { limit }
  if (beforeId != null) params.beforeId = beforeId
  return request.get<ChatMessageVO[]>(
    `/chat/conversations/${conversationId}/messages`,
    { params }
  ) as unknown as Promise<ChatMessageVO[]>
}

export function sendMessage(recipientId: number, content: string, messageType: string, 
                            fileName?: string, fileSize?: number, fileUrl?: string, voiceDuration?: number) {
  const data: Record<string, any> = {
    recipientId,
    content,
    messageType
  }
  if (fileName) data.fileName = fileName
  if (fileSize != null) data.fileSize = fileSize
  if (fileUrl) data.fileUrl = fileUrl
  if (voiceDuration != null) data.voiceDuration = voiceDuration
  return request.post<ConversationVO>('/chat/messages', data)
}

export function markAsRead(conversationId: number) {
  return request.put(`/chat/conversations/${conversationId}/read`)
}

export function deleteConversation(conversationId: number) {
  return request.delete(`/chat/conversations/${conversationId}`)
}

export function getUnreadCount() {
  return request.get<{count: number}>('/chat/unread-count')
}

export function uploadChatFile(file: File, conversationId: number) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post<UploadResult>('/chat/files', formData, {
    params: { conversationId },
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300000,
  })
}
