/** 圆桌派角色 */
export interface RoundTableRole {
  key: string
  name: string
  title: string
  color: string
  icon: string
  roleGroup: string
  grabWeight: number
  verbosity: number
  opinionated: number
  challenge: number
  empathy: number
  humor: number
  domainRelevance: number
  languageStyle: string
  selected?: boolean
}

/** 圆桌派讨论消息 */
export interface RoundTableMessage {
  id: number
  userId: number
  sessionId: string
  bookId: number
  roleKey: string
  roleName: string
  content: string
  compressedContent: string | null
  round: number | null
  createdAt: string
}

/** 圆桌派会话 */
export interface RoundTableSession {
  id: number
  userId: number
  bookId: number
  sessionId: string
  title: string
  roleKeys: string
  roleConfigs: string
  status: string
  createdAt: string
  updatedAt: string
}

/** 圆桌派覆盖度报告 */
export interface RoundTableCoverage {
  id: number
  sessionId: string
  bookId: number
  totalBlocks: number | null
  coveredBlocks: number | null
  deepBlocks: number | null
  blockCoverageScore: number | null
  blocksJson: string | null
  blockDetailsJson: string | null
  totalConcepts: number | null
  coveredConceptsCount: number | null
  conceptCoverageScore: number | null
  coveredConceptsJson: string | null
  missedConceptsJson: string | null
  llmDimensionsJson: string | null
  llmStrengthsJson: string | null
  llmWeaknessesJson: string | null
  llmSuggestionsJson: string | null
  llmAssessmentScore: number | null
  overallScore: number | null
  grade: string | null
  totalChunks: number | null
  processedMessageCount: number | null
}

/** 圆桌派解读报告 */
export interface RoundTableReport {
  id: number
  sessionId: string
  userId: number
  bookId: number
  content: string | null
  status: 'PENDING' | 'GENERATING' | 'COMPLETED' | 'FAILED'
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

/** 内容块覆盖详情 */
export interface BlockCoverageDetail {
  title: string
  coverageLevel: number  // 0=未覆盖, 1=提及, 2=部分讨论, 3=深入讨论
  score: number
  keywordOverlap: number
  judgeMethod: string
  evidence: string
}

/** 角色颜色映射 — 学术沙龙暖色调 */
export const ROLE_COLORS: Record<string, string> = {
  HOST: '#8B6914',           /* 古铜金 — 主持人 */
  PHILOSOPHER: '#5B4A3A',    /* 深褐 — 哲学家 */
  PSYCHOLOGIST: '#6B8E6B',   /* 灰绿 — 心理学家 */
  SOCIOLOGIST: '#A0522D',    /* 赭石 — 社会学家 */
  SCIENTIST: '#4A6741',      /* 墨绿 — 科学家 */
  HISTORIAN: '#8B4513',      /*  saddle brown — 历史学家 */
  CRITIC: '#6B5B73',         /* 灰紫 — 评论家 */
  EDUCATOR: '#2E7D5A',       /* 深青绿 — 教育家 */
  STUDENT: '#7B8FA1',        /* 灰蓝 — 学生 */
  WRITER: '#5D4E37',         /* 棕褐 — 作家 */
  COMEDIAN: '#C17817',       /* 琥珀 — 喜剧演员 */
  JOURNALIST: '#4A5568',     /* 石板灰 — 记者 */
  ACTOR: '#9B6B4A',          /* 暖棕 — 演员 */
  DIRECTOR: '#5C4D3C',       /* 深棕 — 导演 */
  ARTIST: '#A85D7B',         /* 玫瑰褐 — 艺术家 */
  MUSICIAN: '#6B5B8A',       /* 薰衣草灰 — 音乐家 */
  POET: '#8B5A6B',           /* 暗玫瑰 — 诗人 */
  TRANSLATOR: '#5A7A6A',     /* 灰绿 — 译者 */
  ENTREPRENEUR: '#B85450',   /* 砖红 — 企业家 */
  INVESTOR: '#8B7355',       /* 暖灰褐 — 投资人 */
  ECONOMIST: '#4A6B5A',      /* 深绿灰 — 经济学家 */
  STRATEGIST: '#5A4A3A',     /* 深褐 — 战略家 */
  LAWYER: '#4A5568',         /* 石板灰 — 律师 */
  DOCTOR: '#4A7C6F',         /* 青灰绿 — 医者 */
  FARMER: '#8B7355',         /* 暖褐 — 农民 */
  FIREFIGHTER: '#A04030',    /* 暗红 — 消防员 */
  NURSE: '#7BA3A8',          /* 灰青 — 护士 */
  MEDITATION_TEACHER: '#6B5B8A', /* 紫灰 — 冥想导师 */
  PARENT: '#A06050',         /* 暖赭 — 家长 */
  TRAVELER: '#5A7A8A',       /* 灰蓝 — 旅行家 */
  TECH_EXPERT: '#5A6B7A',    /* 冷灰 — 技术专家 */
  ENGINEER: '#4A6B6B',       /* 深青 — 工程师 */
  EDITOR: '#7A6B4A',         /* 橄榄褐 — 出版人 */
  BOOK_REVIEWER: '#8B6B4A',  /* 暖棕 — 书评人 */
  DIPLOMAT: '#6B7A6B',       /* 灰绿 — 外交官 */
  LIBRARIAN: '#5A5A8A',      /* 灰紫 — 图书管理员 */
  SOCIAL_WORKER: '#6B8A6B',  /* 柔和绿 — 社工 */
  SPORTS_COACH: '#8A6B4A',   /* 暖褐 — 体育教练 */
  ANTHROPOLOGIST: '#5A4A6B', /* 深紫灰 — 人类学家 */
  FEMINIST: '#8B5A6B',       /* 玫瑰褐 — 女性主义者 */
  ECOLOGIST: '#4A7A5A',      /* 森林绿 — 生态学家 */
}

/** 角色中文名映射 */
export const ROLE_NAMES: Record<string, string> = {
  HOST: '主持人',
  PHILOSOPHER: '哲学家',
  PSYCHOLOGIST: '心理学家',
  SOCIOLOGIST: '社会学家',
  SCIENTIST: '科学家',
  HISTORIAN: '历史学家',
  CRITIC: '文学评论家',
  EDUCATOR: '教育家',
  STUDENT: '大学生',
  WRITER: '作家',
  COMEDIAN: '喜剧演员',
  JOURNALIST: '记者',
  ACTOR: '演员',
  DIRECTOR: '导演',
  ARTIST: '艺术家',
  MUSICIAN: '音乐家',
  POET: '诗人',
  TRANSLATOR: '译者',
  ENTREPRENEUR: '企业家',
  INVESTOR: '投资人',
  ECONOMIST: '经济学家',
  STRATEGIST: '军事战略家',
  LAWYER: '律师',
  DOCTOR: '医者',
  FARMER: '农民',
  FIREFIGHTER: '消防员',
  NURSE: '护士',
  MEDITATION_TEACHER: '冥想导师',
  PARENT: '家长',
  TRAVELER: '旅行家',
  TECH_EXPERT: '技术专家',
  ENGINEER: '工程师',
  EDITOR: '出版人',
  BOOK_REVIEWER: '书评人',
  DIPLOMAT: '外交官',
  LIBRARIAN: '图书管理员',
  SOCIAL_WORKER: '社工',
  SPORTS_COACH: '体育教练',
  ANTHROPOLOGIST: '人类学家',
  FEMINIST: '女性主义者',
  ECOLOGIST: '生态学家',
}

/** 角色称号映射 */
export const ROLE_TITLES: Record<string, string> = {
  HOST: '圆桌派主持人',
  PHILOSOPHER: '思辨者',
  PSYCHOLOGIST: '心灵解读师',
  SOCIOLOGIST: '结构审视者',
  SCIENTIST: '实证主义者',
  HISTORIAN: '时空穿越者',
  CRITIC: '审美鉴赏者',
  EDUCATOR: '启蒙者',
  STUDENT: '求知者',
  WRITER: '文字匠人',
  COMEDIAN: '段子手',
  JOURNALIST: '真相追踪者',
  ACTOR: '戏中人',
  DIRECTOR: '画面构建者',
  ARTIST: '感知探索者',
  MUSICIAN: '韵律感知者',
  POET: '意象编织者',
  TRANSLATOR: '文化摆渡人',
  ENTREPRENEUR: '破局者',
  INVESTOR: '价值发现者',
  ECONOMIST: '理性算盘',
  STRATEGIST: '博弈推演者',
  LAWYER: '逻辑辩手',
  DOCTOR: '生命观察者',
  FARMER: '大地智者',
  FIREFIGHTER: '危机应对者',
  NURSE: '关怀天使',
  MEDITATION_TEACHER: '内心探索者',
  PARENT: '代际守望者',
  TRAVELER: '世界观察者',
  TECH_EXPERT: '系统极客',
  ENGINEER: '效率偏执狂',
  EDITOR: '文本守门人',
  BOOK_REVIEWER: '阅读品鉴师',
  DIPLOMAT: '平衡术士',
  LIBRARIAN: '知识守门人',
  SOCIAL_WORKER: '弱势守护者',
  SPORTS_COACH: '意志锻造者',
  ANTHROPOLOGIST: '文化解码者',
  FEMINIST: '性别审视者',
  ECOLOGIST: '自然代言人',
}

/** 角色图标映射 */
export const ROLE_ICONS: Record<string, string> = {
  HOST: '🎙️',
  PHILOSOPHER: '🦉',
  PSYCHOLOGIST: '🧠',
  SOCIOLOGIST: '🏛️',
  SCIENTIST: '🔬',
  HISTORIAN: '📜',
  CRITIC: '🎭',
  EDUCATOR: '🏫',
  STUDENT: '📚',
  WRITER: '✍️',
  COMEDIAN: '😄',
  JOURNALIST: '📰',
  ACTOR: '🎬',
  DIRECTOR: '🎥',
  ARTIST: '🎨',
  MUSICIAN: '🎵',
  POET: '🕊️',
  TRANSLATOR: '🔀',
  ENTREPRENEUR: '🚀',
  INVESTOR: '💰',
  ECONOMIST: '📈',
  STRATEGIST: '♟️',
  LAWYER: '⚖️',
  DOCTOR: '🩺',
  FARMER: '🌾',
  FIREFIGHTER: '🚒',
  NURSE: '🩹',
  MEDITATION_TEACHER: '🧘',
  PARENT: '👨‍👩‍👧',
  TRAVELER: '🌍',
  TECH_EXPERT: '💻',
  ENGINEER: '⚙️',
  EDITOR: '📝',
  BOOK_REVIEWER: '⭐',
  DIPLOMAT: '🤝',
  LIBRARIAN: '📖',
  SOCIAL_WORKER: '🤲',
  SPORTS_COACH: '🏋️',
  ANTHROPOLOGIST: '🏺',
  FEMINIST: '✊',
  ECOLOGIST: '🌿',
}

/** TTS 音色配置 */
export const ROLE_TTS_CONFIG: Record<string, { pitch: number; rate: number }> = {
  HOST: { pitch: 1.0, rate: 1.0 },
  PHILOSOPHER: { pitch: 0.8, rate: 1.0 },
  PSYCHOLOGIST: { pitch: 1.1, rate: 1.05 },
  SOCIOLOGIST: { pitch: 0.9, rate: 1.05 },
  SCIENTIST: { pitch: 0.85, rate: 1.05 },
  HISTORIAN: { pitch: 0.8, rate: 1.0 },
  CRITIC: { pitch: 1.15, rate: 1.0 },
  EDUCATOR: { pitch: 0.95, rate: 1.05 },
  STUDENT: { pitch: 1.2, rate: 1.1 },
  WRITER: { pitch: 0.85, rate: 1.0 },
  COMEDIAN: { pitch: 1.15, rate: 1.1 },
  JOURNALIST: { pitch: 1.0, rate: 1.1 },
  ACTOR: { pitch: 1.05, rate: 1.05 },
  DIRECTOR: { pitch: 0.85, rate: 1.0 },
  ARTIST: { pitch: 1.1, rate: 1.0 },
  MUSICIAN: { pitch: 1.1, rate: 1.0 },
  POET: { pitch: 1.05, rate: 1.0 },
  TRANSLATOR: { pitch: 1.0, rate: 1.05 },
  ENTREPRENEUR: { pitch: 0.75, rate: 1.1 },
  INVESTOR: { pitch: 0.8, rate: 1.05 },
  ECONOMIST: { pitch: 0.9, rate: 1.05 },
  STRATEGIST: { pitch: 0.8, rate: 1.0 },
  LAWYER: { pitch: 0.75, rate: 1.05 },
  DOCTOR: { pitch: 0.9, rate: 1.0 },
  FARMER: { pitch: 0.8, rate: 1.0 },
  FIREFIGHTER: { pitch: 0.85, rate: 1.1 },
  NURSE: { pitch: 1.1, rate: 1.05 },
  MEDITATION_TEACHER: { pitch: 0.85, rate: 1.0 },
  PARENT: { pitch: 1.0, rate: 1.0 },
  TRAVELER: { pitch: 1.05, rate: 1.05 },
  TECH_EXPERT: { pitch: 0.8, rate: 1.1 },
  ENGINEER: { pitch: 0.85, rate: 1.05 },
  EDITOR: { pitch: 0.9, rate: 1.05 },
  BOOK_REVIEWER: { pitch: 1.0, rate: 1.1 },
  DIPLOMAT: { pitch: 1.0, rate: 1.0 },
  LIBRARIAN: { pitch: 0.9, rate: 1.0 },
  SOCIAL_WORKER: { pitch: 1.0, rate: 1.05 },
  SPORTS_COACH: { pitch: 0.9, rate: 1.1 },
  ANTHROPOLOGIST: { pitch: 0.9, rate: 1.05 },
  FEMINIST: { pitch: 1.0, rate: 1.05 },
  ECOLOGIST: { pitch: 0.85, rate: 1.0 },
}

/** 性格维度描述 */
export function describePersonality(role: RoundTableRole): string[] {
  const traits: string[] = []
  if (role.grabWeight >= 8) traits.push('爱抢话')
  else if (role.grabWeight <= 3) traits.push('沉默寡言')

  if (role.challenge >= 4) traits.push('挑战型')
  else if (role.empathy >= 4) traits.push('共情型')

  if (role.opinionated >= 4) traits.push('有主见')
  else if (role.opinionated <= 2) traits.push('立场灵活')

  if (role.humor >= 4) traits.push('幽默达人')

  if (role.domainRelevance >= 7) traits.push('专业主场')
  else if (role.domainRelevance >= 4) traits.push('领域相关')

  return traits
}

/** 将 HEX 颜色转为 RGBA 字符串 */
export function hexToRgba(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}
