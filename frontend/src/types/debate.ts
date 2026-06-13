/** 辩论性格（纯人格标识，与立场/位置无关） */
export interface DebateRole {
  key: string
  name: string
  title: string
  roleGroup: 'HOST_GROUP' | 'PERSONALITY'
  side: 'NEUTRAL' | 'PRO' | 'CON'
  catchphrase: string
  grabWeight: number
  verbosity: number
  opinionated: number
  challenge: number
  empathy: number
  humor: number
}

/** 辩论消息 */
export interface DebateMessage {
  id: number
  sessionId: string
  roleKey: string       // 性格键（如 LOGICAL）
  roleName: string      // 性格中文名（如 逻辑严谨型）
  positionKey: string   // 位置键（如 PRO_1、CON_2、HOST）
  side: string
  content: string
  roundNumber: number
  roundType: 'OPENING' | 'CROSS_EXAM' | 'REBUTTAL' | 'FREE' | 'CLOSING' | 'ATTACK'
  examRole?: string
  phaseOrder: number
  createdAt: string
}

/** 辩论会话 */
export interface DebateSession {
  id: number
  sessionId: string
  bookId: number
  topic: string
  topicSource: string
  proRoleKeys: string
  conRoleKeys: string
  currentRound: number
  currentPhase: 'OPENING' | 'CROSS_EXAM' | 'REBUTTAL' | 'FREE' | 'CLOSING'
  status: string
  createdAt: string
  updatedAt: string
}

/** 辩论评分 */
export interface DebateScore {
  id: number
  sessionId: string
  messageId: number
  roleKey: string
  positionKey: string
  side: string
  roundNumber: number
  roundType: string
  logicScore: number | null
  evidenceScore: number | null
  rebuttalScore: number | null
  impactScore: number | null
  humorScore: number | null
  clarityScore: number | null
  noveltyScore: number | null
  averageScore: number | null
}

/** 辩论报告 */
export interface DebateReport {
  id: number
  sessionId: string
  bookId: number
  topic: string
  content: string | null
  summaryJson: string | null
  bestDebater?: string
  status: 'PENDING' | 'GENERATING' | 'COMPLETED' | 'FAILED'
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

/** 辩论辩题 */
export interface DebateTopic {
  topic: string
  source: string
  proArgument: string
  conArgument: string
}

// ==================== 位置常量（PRO_1/CON_2/HOST — 用于颜色/图标/排序） ====================

/** 位置 → 颜色映射 — 正方蓝/反方红/主持人金 */
export const DEBATE_ROLE_COLORS: Record<string, string> = {
  HOST: '#D4A843',      /* 金色 */
  PRO_1: '#3B82F6',     /* 蓝色 — 正方一辩 */
  PRO_2: '#2563EB',     /* 深蓝 — 正方二辩 */
  PRO_3: '#1D4ED8',     /* 藏蓝 — 正方三辩 */
  PRO_4: '#1E40AF',     /* 暗蓝 — 正方四辩 */
  CON_1: '#EF4444',     /* 红色 — 反方一辩 */
  CON_2: '#DC2626',     /* 深红 — 反方二辩 */
  CON_3: '#B91C1C',     /* 暗红 — 反方三辩 */
  CON_4: '#991B1B',     /* 棕红 — 反方四辩 */
}

/** 位置 → 名称映射 */
export const DEBATE_ROLE_NAMES: Record<string, string> = {
  HOST: '主持人',
  PRO_1: '正方一辩',
  PRO_2: '正方二辩',
  PRO_3: '正方三辩',
  PRO_4: '正方四辩',
  CON_1: '反方一辩',
  CON_2: '反方二辩',
  CON_3: '反方三辩',
  CON_4: '反方四辩',
}

/** 位置 → 图标映射 */
export const DEBATE_ROLE_ICONS: Record<string, string> = {
  HOST: '🎙️',
  PRO_1: '👤',
  PRO_2: '👤',
  PRO_3: '👤',
  PRO_4: '👤',
  CON_1: '👤',
  CON_2: '👤',
  CON_3: '👤',
  CON_4: '👤',
}

// ==================== 人格常量（LOGICAL/SHARP/HUMOROUS/... — 用于性格展示） ====================

/** 人格 → 颜色映射 */
export const DEBATE_PERSONALITY_COLORS: Record<string, string> = {
  HOST: '#D4A843',
  LOGICAL: '#3B82F6',
  SHARP: '#EF4444',
  HUMOROUS: '#F59E0B',
  EMPATHETIC: '#8B5CF6',
  SENSITIVE: '#EC4899',
  DOMINEERING: '#DC2626',
  ERUDITE: '#6366F1',
  PRACTICAL: '#84CC16',
  SKEPTICAL: '#06B6D4',
  PASSIONATE: '#F97316',
  WITTY: '#14B8A6',
  DEEP: '#1E293B',
  STORYTELLER: '#A855F7',
  ANALYTICAL: '#0EA5E9',
  DIPLOMATIC: '#10B981',
  REBEL: '#E11D48',
}

/** 人格 → 名称映射 */
export const DEBATE_PERSONALITY_NAMES: Record<string, string> = {
  HOST: '主持人',
  LOGICAL: '逻辑严谨型',
  SHARP: '犀利毒舌型',
  HUMOROUS: '机智幽默型',
  EMPATHETIC: '共情升华型',
  SENSITIVE: '感性温情型',
  DOMINEERING: '强势暴躁型',
  ERUDITE: '博学引经型',
  PRACTICAL: '务实接地气型',
  SKEPTICAL: '质疑批判型',
  PASSIONATE: '慷慨激昂型',
  WITTY: '机敏风趣型',
  DEEP: '哲学思辨型',
  STORYTELLER: '叙事故事型',
  ANALYTICAL: '数据分析型',
  DIPLOMATIC: '圆融调和型',
  REBEL: '反叛颠覆型',
}

/** 人格 → 称号/标签映射 */
export const DEBATE_PERSONALITY_TITLES: Record<string, string> = {
  HOST: '控场大师',
  LOGICAL: '冷静理性·层层推演',
  SHARP: '一针见血·言辞犀利',
  HUMOROUS: '妙语连珠·举重若轻',
  EMPATHETIC: '感同身受·升华主题',
  SENSITIVE: '情感丰富·以情动人',
  DOMINEERING: '气势凌人·言辞激烈',
  ERUDITE: '旁征博引·以理服人',
  PRACTICAL: '脚踏实地·生活智慧',
  SKEPTICAL: '打破砂锅·刨根问底',
  PASSIONATE: '热血沸腾·振臂高呼',
  WITTY: '金句频出·妙趣横生',
  DEEP: '由表及里·层层追问',
  STORYTELLER: '以小见大·娓娓道来',
  ANALYTICAL: '数字说话·证据为凭',
  DIPLOMATIC: '兼容并包·化解冲突',
  REBEL: '另辟蹊径·打破常规',
}

/** 人格 → 图标映射 */
export const DEBATE_PERSONALITY_ICONS: Record<string, string> = {
  HOST: '🎙️',
  LOGICAL: '🧠',
  SHARP: '⚡',
  HUMOROUS: '😄',
  EMPATHETIC: '💖',
  SENSITIVE: '🥺',
  DOMINEERING: '😤',
  ERUDITE: '📚',
  PRACTICAL: '🔧',
  SKEPTICAL: '❓',
  PASSIONATE: '🔥',
  WITTY: '🤣',
  DEEP: '🔮',
  STORYTELLER: '📖',
  ANALYTICAL: '📊',
  DIPLOMATIC: '🤝',
  REBEL: '💥',
}

// ==================== 兼容映射（两个体系都能查） ====================

/** 兼容 — 取人格称号，找不到则取位置称号 */
export function getPersonalityTitle(key: string): string {
  return DEBATE_PERSONALITY_TITLES[key] || DEBATE_ROLE_NAMES[key] || key
}

/** 兼容 — 取人格名称，找不到则取位置名称 */
export function getPersonalityName(key: string): string {
  return DEBATE_PERSONALITY_NAMES[key] || DEBATE_ROLE_NAMES[key] || key
}

/** 兼容 — 取人格图标，找不到则取位置图标 */
export function getPersonalityIcon(key: string): string {
  return DEBATE_PERSONALITY_ICONS[key] || DEBATE_ROLE_ICONS[key] || '👤'
}

/** 兼容 — 取颜色（优先按位置键取） */
export function getDebateColor(positionKey: string, personalityKey?: string): string {
  return DEBATE_ROLE_COLORS[positionKey] || DEBATE_PERSONALITY_COLORS[personalityKey || ''] || '#888'
}

/** 兼容 — 消息角色名显示：有 positionKey 用位置名，否则用性格名 */
export function getMessageRoleName(msg: DebateMessage): string {
  if (msg.positionKey && DEBATE_ROLE_NAMES[msg.positionKey]) {
    return DEBATE_ROLE_NAMES[msg.positionKey]
  }
  return getPersonalityName(msg.roleKey)
}

/** 兼容 — 消息颜色 */
export function getMessageColor(msg: DebateMessage): string {
  if (msg.positionKey && DEBATE_ROLE_COLORS[msg.positionKey]) {
    return DEBATE_ROLE_COLORS[msg.positionKey]
  }
  return DEBATE_PERSONALITY_COLORS[msg.roleKey] || '#888'
}

/** 兼容 — 消息图标 */
export function getMessageIcon(msg: DebateMessage): string {
  if (msg.positionKey && DEBATE_ROLE_ICONS[msg.positionKey]) {
    return DEBATE_ROLE_ICONS[msg.positionKey]
  }
  return getPersonalityIcon(msg.roleKey)
}

/** 兼容 — 消息角色称号（用在 Name 下方的个性标签） */
export function getMessageTitle(msg: DebateMessage): string {
  if (msg.roleKey && DEBATE_PERSONALITY_TITLES[msg.roleKey]) {
    return DEBATE_PERSONALITY_TITLES[msg.roleKey]
  }
  return msg.positionKey && DEBATE_PERSONALITY_TITLES[msg.positionKey] || ''
}

// ==================== 旧映射（保留引用，内部委托给兼容函数） ====================

/** @deprecated 使用 getPersonalityTitle/DESATE_PERSONALITY_TITLES */
export const DEBATE_ROLE_TITLES: Record<string, string> = new Proxy({} as Record<string, string>, {
  get: (_, key: string) => getPersonalityTitle(key),
})

/** @deprecated 使用 getMessageRoleName */
export { getMessageRoleName as formatRoleName }

/** TTS 音色配置（按位置键） */
export const DEBATE_TTS_CONFIG: Record<string, { rate: number; pitch: number }> = {
  HOST: { rate: 1.0, pitch: 1.1 },
  PRO_1: { rate: 0.9, pitch: 0.9 },
  PRO_2: { rate: 1.2, pitch: 0.8 },
  PRO_3: { rate: 1.1, pitch: 1.2 },
  PRO_4: { rate: 0.8, pitch: 1.0 },
  CON_1: { rate: 0.9, pitch: 0.9 },
  CON_2: { rate: 1.2, pitch: 0.8 },
  CON_3: { rate: 1.1, pitch: 1.2 },
  CON_4: { rate: 0.8, pitch: 1.0 },
}

/** 立场标签 */
export const DEBATE_SIDE_LABELS: Record<string, string> = {
  NEUTRAL: '主持人',
  PRO: '正方',
  CON: '反方',
}

/** 轮次标签 */
export const DEBATE_ROUND_LABELS: Record<string, string> = {
  OPENING: '开篇立论',
  CROSS_EXAM: '交叉质询',
  REBUTTAL: '驳论',
  FREE: '自由辩论',
  CLOSING: '总结陈词',
  ATTACK: '奇袭攻辩',
}

/** 7维度评分定义 */
export const DEBATE_SCORE_DIMENSIONS = [
  { key: 'logicScore', label: '逻辑性', color: '#3B82F6' },
  { key: 'evidenceScore', label: '论据丰富度', color: '#10B981' },
  { key: 'rebuttalScore', label: '反驳力', color: '#F59E0B' },
  { key: 'impactScore', label: '感染力', color: '#EF4444' },
  { key: 'humorScore', label: '幽默感', color: '#8B5CF6' },
  { key: 'clarityScore', label: '表达清晰度', color: '#06B6D4' },
  { key: 'noveltyScore', label: '观点新颖度', color: '#F97316' },
] as const
