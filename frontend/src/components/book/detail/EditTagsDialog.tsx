import { useState, useEffect } from 'react'
import { X, Plus } from 'lucide-react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { toast } from 'sonner'

interface EditTagsDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  tags: string[]
  onSubmit: (tags: string[]) => Promise<void>
}

export function EditTagsDialog({
  open,
  onOpenChange,
  tags,
  onSubmit,
}: EditTagsDialogProps) {
  const [editTags, setEditTags] = useState<string[]>(tags)
  const [newTag, setNewTag] = useState('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (open) setEditTags(tags)
  }, [open, tags])

  const addTag = () => {
    const trimmed = newTag.trim()
    if (trimmed && !editTags.includes(trimmed)) {
      setEditTags([...editTags, trimmed])
      setNewTag('')
    }
  }

  const removeTag = (tag: string) => {
    setEditTags(editTags.filter(t => t !== tag))
  }

  const handleSubmit = async () => {
    setSubmitting(true)
    try {
      await onSubmit(editTags)
      onOpenChange(false)
    } catch (err: unknown) {
      toast.error((err as Error)?.message || '修改失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>修改标签</DialogTitle>
        </DialogHeader>
        <div className="flex flex-wrap gap-2 min-h-8">
          {editTags.map((tag) => (
            <span
              key={tag}
              className="inline-flex items-center gap-1 rounded-full bg-primary/8 px-3 py-1 text-xs font-semibold text-primary border border-primary/10"
            >
              {tag}
              <button onClick={() => removeTag(tag)} className="hover:text-destructive transition-colors">
                <X className="h-3 w-3" />
              </button>
            </span>
          ))}
        </div>
        <div className="flex gap-2">
          <Input
            value={newTag}
            onChange={(e) => setNewTag(e.target.value)}
            placeholder="输入新标签"
            onKeyDown={(e) => { if (e.key === 'Enter') addTag() }}
          />
          <button
            onClick={addTag}
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            <Plus className="h-4 w-4" />
          </button>
        </div>
        <DialogFooter>
          <button
            onClick={() => onOpenChange(false)}
            className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-muted transition-colors"
          >
            取消
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          >
            {submitting ? '保存中...' : '保存'}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
