import { useState } from 'react'
import { BlinkingBot } from '@/components/BlinkingBot'
import DraggableFab from '@/components/DraggableFab'
import AiChatSheet from '@/components/ai/AiChatSheet'

export function AiFab() {
  const [open, setOpen] = useState(false)

  return (
    <>
      <DraggableFab
        onClick={() => setOpen(true)}
        size={56}
        edgePadding={16}
        snapEdges={['left', 'right', 'top', 'bottom']}
        autoHide={true}
        storageKey="ai-fab-pos"
        className="bg-gradient-to-br from-primary to-primary/80 text-primary-foreground shadow-lg shadow-primary/30"
        title="AI 助手"
      >
        <BlinkingBot className="h-6 w-6" />
      </DraggableFab>
      <AiChatSheet open={open} onOpenChange={setOpen} />
    </>
  )
}
