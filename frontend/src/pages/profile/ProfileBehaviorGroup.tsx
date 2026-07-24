import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Bot, X, RefreshCw, Sparkles, Loader2 } from 'lucide-react'
import { Card } from '@/components/ui/card'
import {
  AlertDialog,
  AlertDialogTrigger,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogCancel,
  AlertDialogAction,
} from '@/components/ui/alert-dialog'
import { toast } from 'sonner'
import {
  getBehaviorProfile,
  suppressBehaviorSignal,
  resetBehaviorProfile,
  type BehaviorProfileVO,
  type WeightedItem,
} from '@/api/behaviorProfile'

/** 权重可视化：0-1 映射到 1-5 个点 */
function WeightDots({ weight }: { weight: number }) {
  const level = Math.max(1, Math.min(5, Math.round(weight * 5)))
  return (
    <span className="inline-flex gap-0.5">
      {Array.from({ length: 5 }).map((_, i) => (
        <span
          key={i}
          className={`h-1 w-1 rounded-full ${i < level ? 'bg-primary' : 'bg-muted-foreground/20'}`}
        />
      ))}
    </span>
  )
}

/** 可删除的标签芯片 */
function DeletableChip({
  text,
  weight,
  onDelete,
  deleting,
}: {
  text: string
  weight?: number
  onDelete: () => void
  deleting: boolean
}) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-primary/20 bg-primary/5 py-1 pl-2.5 pr-1.5 text-xs text-foreground">
      <span>{text}</span>
      {weight != null && <WeightDots weight={weight} />}
      <button
        onClick={onDelete}
        disabled={deleting}
        className="flex h-4 w-4 items-center justify-center rounded-full text-muted-foreground hover:bg-destructive/10 hover:text-destructive disabled:opacity-40 transition-colors"
        title="删除此信号"
      >
        {deleting ? <Loader2 className="h-2.5 w-2.5 animate-spin" /> : <X className="h-2.5 w-2.5" />}
      </button>
    </span>
  )
}

/** 一个分组段落 */
function Section({
  title,
  children,
}: {
  title: string
  children: React.ReactNode
}) {
  return (
    <div>
      <p className="mb-1.5 text-xs font-medium text-muted-foreground">{title}</p>
      <div className="flex flex-wrap gap-1.5">{children}</div>
    </div>
  )
}

export default function ProfileBehaviorGroup() {
  const queryClient = useQueryClient()
  const [deletingKey, setDeletingKey] = useState<string | null>(null)

  const { data: profile, isLoading } = useQuery<BehaviorProfileVO>({
    queryKey: ['profile', 'behavior'],
    queryFn: getBehaviorProfile,
  })

  const handleDelete = async (field: string, value: string) => {
    const key = `${field}:${value}`
    setDeletingKey(key)
    try {
      await suppressBehaviorSignal(field, value)
      await queryClient.invalidateQueries({ queryKey: ['profile', 'behavior'] })
      toast.success('已删除')
    } catch {
      toast.error('删除失败')
    } finally {
      setDeletingKey(null)
    }
  }

  const handleReset = async () => {
    try {
      await resetBehaviorProfile()
      await queryClient.invalidateQueries({ queryKey: ['profile', 'behavior'] })
      toast.success('已重置行为画像')
    } catch {
      toast.error('重置失败')
    }
  }

  const isEmpty =
    !profile ||
    (profile.interestTags.length === 0 &&
      profile.readingMotivations.length === 0 &&
      profile.knowledgeGaps.length === 0 &&
      profile.valueOrientation.length === 0 &&
      !profile.cognitiveDepth &&
      !profile.emotionalTone)

  const fmtTime = (iso: string | null) => {
    if (!iso) return null
    const d = new Date(iso)
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }

  return (
    <Card padding="none" className="overflow-hidden">
      {/* 头部 */}
      <div className="flex items-center justify-between px-4 pt-3 pb-2">
        <div className="flex items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary/10">
            <Bot className="h-3.5 w-3.5 text-primary" />
          </div>
          <div>
            <h3 className="text-sm font-semibold">AI 眼中的你</h3>
            <p className="text-xs text-muted-foreground">
              {profile && profile.totalSignals > 0
                ? `基于 ${profile.totalSignals} 次提问`
                : '从你的提问中学习'}
            </p>
          </div>
        </div>
        {!isEmpty && (
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <button
                className="flex h-7 items-center gap-1 rounded-lg px-2 text-xs text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                title="重置画像"
              >
                <RefreshCw className="h-3 w-3" />
                <span className="hidden sm:inline">重置</span>
              </button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>重置行为画像？</AlertDialogTitle>
                <AlertDialogDescription>
                  将清空 AI 从你的提问中总结的所有画像。已删除的信号会保留在禁止列表中，不会被重新抽取。重置后需要重新积累提问才会生成新画像。
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>取消</AlertDialogCancel>
                <AlertDialogAction onClick={handleReset}>确认重置</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        )}
      </div>

      {/* 内容 */}
      <div className="px-4 pb-4 space-y-3">
        {isLoading ? (
          <div className="flex items-center justify-center py-6 text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
          </div>
        ) : isEmpty ? (
          <div className="flex flex-col items-center py-6 text-center">
            <div className="mb-2 flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
              <Sparkles className="h-5 w-5 text-primary/60" />
            </div>
            <p className="text-sm text-muted-foreground">
              AI 还在了解你
            </p>
            <p className="mt-1 text-xs text-muted-foreground/70">
              在图书问答或 AI 助理中多提几个问题，画像会逐渐清晰
            </p>
          </div>
        ) : (
          <>
            {profile!.interestTags.length > 0 && (
              <Section title="近期关注">
                {profile!.interestTags.map((t: WeightedItem) => (
                  <DeletableChip
                    key={t.tag}
                    text={t.tag}
                    weight={t.weight}
                    deleting={deletingKey === `interestTags:${t.tag}`}
                    onDelete={() => handleDelete('interestTags', t.tag)}
                  />
                ))}
              </Section>
            )}

            {profile!.readingMotivations.length > 0 && (
              <Section title="阅读动机">
                {profile!.readingMotivations.map((t: WeightedItem) => (
                  <DeletableChip
                    key={t.tag}
                    text={t.tag}
                    weight={t.weight}
                    deleting={deletingKey === `readingMotivations:${t.tag}`}
                    onDelete={() => handleDelete('readingMotivations', t.tag)}
                  />
                ))}
              </Section>
            )}

            {profile!.knowledgeGaps.length > 0 && (
              <Section title="知识盲区">
                {profile!.knowledgeGaps.map((g) => (
                  <DeletableChip
                    key={g}
                    text={g}
                    deleting={deletingKey === `knowledgeGaps:${g}`}
                    onDelete={() => handleDelete('knowledgeGaps', g)}
                  />
                ))}
              </Section>
            )}

            {profile!.valueOrientation.length > 0 && (
              <Section title="价值观倾向">
                {profile!.valueOrientation.map((v) => (
                  <DeletableChip
                    key={v}
                    text={v}
                    deleting={deletingKey === `valueOrientation:${v}`}
                    onDelete={() => handleDelete('valueOrientation', v)}
                  />
                ))}
              </Section>
            )}

            {(profile!.cognitiveDepthLabel || profile!.emotionalToneLabel) && (
              <div className="flex flex-wrap gap-2 pt-1">
                {profile!.cognitiveDepthLabel && (
                  <span className="rounded-lg bg-muted px-2.5 py-1 text-xs text-muted-foreground">
                    认知：{profile!.cognitiveDepthLabel}
                  </span>
                )}
                {profile!.emotionalToneLabel && (
                  <span className="rounded-lg bg-muted px-2.5 py-1 text-xs text-muted-foreground">
                    情绪：{profile!.emotionalToneLabel}
                  </span>
                )}
              </div>
            )}

            {fmtTime(profile!.lastInferredAt) && (
              <p className="pt-1 text-xs text-muted-foreground/60">
                上次更新于 {fmtTime(profile!.lastInferredAt)}
              </p>
            )}
          </>
        )}
      </div>
    </Card>
  )
}
