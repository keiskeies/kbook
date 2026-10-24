import { useNoteComposerStore } from '@/store/noteComposer'
import { NoteComposer } from './NoteComposer'

export function GlobalNoteComposer() {
  const open = useNoteComposerStore((s) => s.open)
  const seedContent = useNoteComposerStore((s) => s.seedContent)
  const close = useNoteComposerStore((s) => s.close)

  return (
    <NoteComposer
      open={open}
      onOpenChange={(next) => { if (!next) close() }}
      seedContent={seedContent}
    />
  )
}
