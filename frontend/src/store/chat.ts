import { create } from 'zustand'
import type { ConversationVO, ChatMessageVO } from '@/api/chat'

interface ChatStore {
  conversations: ConversationVO[]
  currentConversationId: number | null
  currentRecipientId: number | null
  messages: Record<number, ChatMessageVO[]>
  unreadCount: number
  isConnected: boolean
  searchKeyword: string

  setConversations: (conversations: ConversationVO[]) => void
  addConversation: (conversation: ConversationVO) => void
  updateConversation: (id: number, updates: Partial<ConversationVO>) => void
  removeConversation: (id: number) => void
  setCurrentConversationId: (id: number | null) => void
  setCurrentRecipientId: (id: number | null) => void
  setMessages: (conversationId: number, messages: ChatMessageVO[]) => void
  addMessage: (conversationId: number, message: ChatMessageVO) => void
  prependMessages: (conversationId: number, messages: ChatMessageVO[]) => void
  appendMessages: (conversationId: number, messages: ChatMessageVO[]) => void
  addTempMessage: (conversationId: number, message: ChatMessageVO) => void
  replaceTempMessage: (conversationId: number, tempId: string, message: ChatMessageVO) => void
  markTempMessageFailed: (conversationId: number, tempId: string) => void
  markMessagesAsRead: (conversationId: number) => void
  setUnreadCount: (count: number) => void
  incrementUnread: (conversationId: number) => void
  setConnected: (connected: boolean) => void
  setSearchKeyword: (keyword: string) => void
  reset: () => void
}

export const useChatStore = create<ChatStore>((set) => ({
  conversations: [],
  currentConversationId: null,
  currentRecipientId: null,
  messages: {},
  unreadCount: 0,
  isConnected: false,
  searchKeyword: '',

  setConversations: (conversations) => set({ conversations }),

  addConversation: (conversation) => set((state) => ({
    conversations: [conversation, ...state.conversations]
  })),

  updateConversation: (id, updates) => set((state) => ({
    conversations: state.conversations.map(c =>
      c.id === id ? { ...c, ...updates } : c
    )
  })),

  removeConversation: (id) => set((state) => ({
    conversations: state.conversations.filter(c => c.id !== id)
  })),

  setCurrentConversationId: (id) => set({ currentConversationId: id }),

  setCurrentRecipientId: (id) => set({ currentRecipientId: id }),

  setMessages: (conversationId, messages) => set((state) => ({
    messages: { ...state.messages, [conversationId]: messages }
  })),

  addMessage: (conversationId, message) => set((state) => ({
    messages: {
      ...state.messages,
      [conversationId]: [message, ...(state.messages[conversationId] || [])]
    }
  })),

  prependMessages: (conversationId, messages) => set((state) => ({
    messages: {
      ...state.messages,
      [conversationId]: [...messages, ...(state.messages[conversationId] || [])]
    }
  })),

  appendMessages: (conversationId, messages) => set((state) => ({
    messages: {
      ...state.messages,
      [conversationId]: [...(state.messages[conversationId] || []), ...messages]
    }
  })),

  addTempMessage: (conversationId, message) => set((state) => ({
    messages: {
      ...state.messages,
      [conversationId]: [message, ...(state.messages[conversationId] || [])]
    }
  })),

  replaceTempMessage: (conversationId, tempId, message) => set((state) => ({
    messages: {
      ...state.messages,
      [conversationId]: (state.messages[conversationId] || []).map(m =>
        m.tempId === tempId ? { ...message, isPending: false } : m
      )
    }
  })),

  markTempMessageFailed: (conversationId, tempId) => set((state) => ({
    messages: {
      ...state.messages,
      [conversationId]: (state.messages[conversationId] || []).map(m =>
        m.tempId === tempId ? { ...m, isPending: false, isFailed: true } : m
      )
    }
  })),

  markMessagesAsRead: (conversationId) => set((state) => ({
    messages: {
      ...state.messages,
      [conversationId]: (state.messages[conversationId] || []).map(m => ({ ...m, read: true }))
    },
    conversations: state.conversations.map(c =>
      c.id === conversationId ? { ...c, unreadCount: 0 } : c
    ),
    unreadCount: state.conversations.reduce((acc, c) => 
      acc + (c.id === conversationId ? 0 : c.unreadCount), 0
    )
  })),

  setUnreadCount: (count) => set({ unreadCount: count }),

  incrementUnread: (conversationId) => set((state) => ({
    unreadCount: state.unreadCount + 1,
    conversations: state.conversations.map(c =>
      c.id === conversationId ? { ...c, unreadCount: (c.unreadCount || 0) + 1 } : c
    )
  })),

  setConnected: (connected) => set({ isConnected: connected }),

  setSearchKeyword: (keyword) => set({ searchKeyword: keyword }),

  reset: () => set({
    conversations: [],
    currentConversationId: null,
    currentRecipientId: null,
    messages: {},
    unreadCount: 0,
    isConnected: false,
    searchKeyword: ''
  })
}))