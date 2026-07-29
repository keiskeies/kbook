import { useQuery } from '@tanstack/react-query'
import { Bot, Sparkles, Loader2, User, HelpCircle, Compass } from 'lucide-react'
import { Card } from '@/components/ui/card'
import {
  getBehaviorProfile,
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

/** 只读标签芯片（无删除按钮——画像由 AI 全权维护，用户不可手动干预） */
function Chip({ text, weight }: { text: string; weight?: number }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-primary/20 bg-primary/5 py-1 px-2.5 text-xs text-foreground">
      <span>{text}</span>
      {weight != null && <WeightDots weight={weight} />}
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
  const { data: profile, isLoading } = useQuery<BehaviorProfileVO>({
    queryKey: ['profile', 'behavior'],
    queryFn: getBehaviorProfile,
  })

  const isEmpty =
    !profile ||
    (profile.interestTags.length === 0 &&
      profile.readingMotivations.length === 0 &&
      profile.knowledgeGaps.length === 0 &&
      profile.valueOrientation.length === 0 &&
      profile.personalityTraits.length === 0 &&
      profile.confusions.length === 0 &&
      !profile.cognitiveDepth &&
      !profile.emotionalTone &&
      !profile.thinkingStyle &&
      !profile.readerArchetype &&
      !profile.lifeContext)

  const fmtTime = (iso: string | null) => {
    if (!iso) return null
    const d = new Date(iso)
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }

  return (
    <Card padding="none" className="overflow-hidden">
      {/* 头部 */}
      <div className="flex items-center gap-2 px-4 pt-3 pb-2">
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
            {/* 读者人格——最显眼的"你是什么样的人" */}
            {profile!.readerArchetypeLabel && (
              <div className="flex items-center gap-3 rounded-xl bg-gradient-to-br from-primary/10 to-primary/5 p-3">
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/15">
                  <User className="h-4 w-4 text-primary" />
                </div>
                <div className="min-w-0">
                  <p className="text-xs text-muted-foreground">读者人格</p>
                  <p className="text-sm font-semibold text-foreground">
                    {profile!.readerArchetypeLabel}
                  </p>
                </div>
              </div>
            )}

            {/* 当前处境——一句话概括你处在什么人生阶段 */}
            {profile!.lifeContext && (
              <div className="flex items-start gap-2.5 rounded-lg border border-amber-500/20 bg-amber-50/50 dark:bg-amber-950/10 p-2.5">
                <Compass className="h-3.5 w-3.5 shrink-0 mt-0.5 text-amber-600 dark:text-amber-400" />
                <p className="text-xs leading-relaxed text-foreground/80 italic">
                  {profile!.lifeContext}
                </p>
              </div>
            )}

            {/* 人生困惑——你想从书中找答案的问题 */}
            {profile!.confusions.length > 0 && (
              <Section title="人生困惑">
                {profile!.confusions.map((c) => (
                  <span
                    key={c}
                    className="inline-flex items-center gap-1 rounded-full border border-amber-500/30 bg-amber-50/70 dark:bg-amber-950/20 py-1 px-2.5 text-xs text-foreground"
                  >
                    <HelpCircle className="h-2.5 w-2.5 text-amber-600 dark:text-amber-400" />
                    <span>{c}</span>
                  </span>
                ))}
              </Section>
            )}

            {/* 性格特质——"你是什么性格" */}
            {profile!.personalityTraits.length > 0 && (
              <Section title="性格特质">
                {profile!.personalityTraits.map((t: WeightedItem) => (
                  <Chip key={t.tag} text={t.tag} weight={t.weight} />
                ))}
              </Section>
            )}

            {/* 三个枚举维度合并一行 */}
            {(profile!.thinkingStyleLabel || profile!.cognitiveDepthLabel || profile!.emotionalToneLabel) && (
              <div className="flex flex-wrap gap-2">
                {profile!.thinkingStyleLabel && (
                  <span className="rounded-lg bg-muted px-2.5 py-1 text-xs text-muted-foreground">
                    思维：{profile!.thinkingStyleLabel}
                  </span>
                )}
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

            {profile!.interestTags.length > 0 && (
              <Section title="近期关注">
                {profile!.interestTags.map((t: WeightedItem) => (
                  <Chip key={t.tag} text={t.tag} weight={t.weight} />
                ))}
              </Section>
            )}

            {profile!.readingMotivations.length > 0 && (
              <Section title="阅读动机">
                {profile!.readingMotivations.map((t: WeightedItem) => (
                  <Chip key={t.tag} text={t.tag} weight={t.weight} />
                ))}
              </Section>
            )}

            {profile!.knowledgeGaps.length > 0 && (
              <Section title="知识盲区">
                {profile!.knowledgeGaps.map((g) => (
                  <Chip key={g} text={g} />
                ))}
              </Section>
            )}

            {profile!.valueOrientation.length > 0 && (
              <Section title="价值观倾向">
                {profile!.valueOrientation.map((v) => (
                  <Chip key={v} text={v} />
                ))}
              </Section>
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
