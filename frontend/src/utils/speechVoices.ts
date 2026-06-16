/**
 * Azure / 讯飞 中文语音名称映射
 *
 * Azure 使用 SSML voiceName 格式：zh-CN-{Name}Neural
 * 讯飞使用简称：xiaoyan, aisjiuxu 等
 */

// ─── Azure 中文神经语音 ──────────────────────────────────────────

export const AZURE_VOICES = {
  XIAOXIAO: 'zh-CN-XiaoxiaoNeural',    // 晓晓 - 女，亲切
  YUNXI: 'zh-CN-YunxiNeural',          // 云希 - 男，阳光
  YUNYANG: 'zh-CN-YunyangNeural',      // 云扬 - 男，沉稳
  XIAOXUAN: 'zh-CN-XiaoxuanNeural',    // 晓萱 - 女，温暖
  YUNJIAN: 'zh-CN-YunjianNeural',      // 云健 - 男，深沉
  XIAOMENG: 'zh-CN-XiaomengNeural',    // 晓梦 - 女，娓娓
  XIAOHAN: 'zh-CN-XiaohanNeural',      // 晓涵 - 女，专业
  YUNJIE: 'zh-CN-YunjieNeural',        // 云杰 - 男，冷静
  YUNFENG: 'zh-CN-YunfengNeural',      // 云枫 - 男，气势
  XIAOZHEN: 'zh-CN-XiaozhenNeural',    // 晓臻 - 女，活泼
  YIXUAN: 'zh-CN-YixuanNeural',        // 逸轩 - 男，随和
  XIAOYOU: 'zh-CN-XiaoyouNeural',      // 晓友 - 男，热情
} as const

// ─── 圆桌派角色 → Azure 语音 ────────────────────────────────────

export const ROUNDTABLE_AZURE_VOICE: Record<string, string> = {
  HOST: AZURE_VOICES.XIAOXIAO,
  PHILOSOPHER: AZURE_VOICES.YUNJIAN,
  PSYCHOLOGIST: AZURE_VOICES.XIAOXUAN,
  SOCIOLOGIST: AZURE_VOICES.YUNYANG,
  SCIENTIST: AZURE_VOICES.XIAOHAN,
  HISTORIAN: AZURE_VOICES.YUNFENG,
  CRITIC: AZURE_VOICES.YUNJIE,
  EDUCATOR: AZURE_VOICES.XIAOMENG,
  STUDENT: AZURE_VOICES.YUNXI,
  WRITER: AZURE_VOICES.XIAOZHEN,
  COMEDIAN: AZURE_VOICES.XIAOYOU,
  JOURNALIST: AZURE_VOICES.XIAOHAN,
  ACTOR: AZURE_VOICES.XIAOXUAN,
  DIRECTOR: AZURE_VOICES.YUNYANG,
  ARTIST: AZURE_VOICES.YIXUAN,
  MUSICIAN: AZURE_VOICES.XIAOMENG,
  POET: AZURE_VOICES.XIAOZHEN,
  ENTREPRENEUR: AZURE_VOICES.YUNFENG,
  LAWYER: AZURE_VOICES.YUNJIE,
  DOCTOR: AZURE_VOICES.XIAOHAN,
}

// ─── 辩论赛位置 → Azure 语音 ────────────────────────────────────

export const DEBATE_AZURE_VOICE: Record<string, string> = {
  HOST: AZURE_VOICES.XIAOXIAO,
  PRO_1: AZURE_VOICES.YUNXI,
  PRO_2: AZURE_VOICES.YUNJIAN,
  PRO_3: AZURE_VOICES.XIAOXUAN,
  PRO_4: AZURE_VOICES.YUNYANG,
  CON_1: AZURE_VOICES.YUNJIE,
  CON_2: AZURE_VOICES.XIAOHAN,
  CON_3: AZURE_VOICES.YUNFENG,
  CON_4: AZURE_VOICES.XIAOMENG,
}

/**
 * 获取角色对应的 Azure 语音名称，未映射时用 hash 选一个
 */
export function getAzureVoiceForRole(
  roleKey: string,
  voiceMap: Record<string, string>,
): string {
  if (voiceMap[roleKey]) return voiceMap[roleKey]

  // hash 回退：从 AZURE_VOICES 值列表中选
  const voices = Object.values(AZURE_VOICES) as readonly string[]
  let hash = 0
  for (let i = 0; i < roleKey.length; i++)
    hash = ((hash << 5) - hash + roleKey.charCodeAt(i)) | 0
  return voices[Math.abs(hash) % voices.length]
}
