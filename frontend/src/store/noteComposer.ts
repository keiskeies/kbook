import { create } from 'zustand'

interface NoteComposerState {
  open: boolean
  seedContent: string
  openComposer: (seed?: string) => void
  close: () => void
}

/** 全局「记一笔」入口 — 任意页面都能唤起同一个编辑器 */
export const useNoteComposerStore = create<NoteComposerState>((set) => ({
  open: false,
  seedContent: '',
  openComposer: (seed = '') => set({ open: true, seedContent: seed }),
  close: () => set({ open: false }),
}))
