import request from '@/utils/request'

/** 带权重的条目 */
export interface WeightedItem {
  tag: string
  weight: number
}

/** 行为画像 VO */
export interface BehaviorProfileVO {
  interestTags: WeightedItem[]
  readingMotivations: WeightedItem[]
  knowledgeGaps: string[]
  valueOrientation: string[]
  cognitiveDepth: string | null
  cognitiveDepthLabel: string | null
  emotionalTone: string | null
  emotionalToneLabel: string | null
  personalityTraits: WeightedItem[]
  thinkingStyle: string | null
  thinkingStyleLabel: string | null
  readerArchetype: string | null
  readerArchetypeLabel: string | null
  confusions: string[]
  lifeContext: string | null
  totalSignals: number
  lastInferredAt: string | null
  recentSignals: string[]
}

/** 获取当前用户行为画像 */
export function getBehaviorProfile() {
  return request.get<BehaviorProfileVO>('/user/behavior-profile')
}
