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
  totalSignals: number
  lastInferredAt: string | null
  recentSignals: string[]
}

/** 获取当前用户行为画像 */
export function getBehaviorProfile() {
  return request.get<BehaviorProfileVO>('/user/behavior-profile')
}

/** 删除单条画像信号（加入 suppressedSignals，下次抽取不再加强） */
export function suppressBehaviorSignal(field: string, value: string) {
  return request.delete<boolean>('/user/behavior-profile/signal', {
    params: { field, value },
  })
}

/** 重置整个行为画像（保留 suppressedSignals） */
export function resetBehaviorProfile() {
  return request.delete<boolean>('/user/behavior-profile')
}
