/**
 * AI 供应商配置管理 API
 */

import request from '@/utils/request'

/** 供应商预设 */
export interface AiProviderPreset {
  /** 显示名称 */
  name: string
  /** 供应商 ID */
  id: string
  /** 供应商类型 */
  provider: 'OLLAMA' | 'OPENAI'
  /** 默认 API 地址 */
  baseUrl: string
  /** 推荐模型列表 */
  models: { name: string; label: string; free?: boolean }[]
  /** API Key 注册地址 */
  apiKeyUrl?: string
  /** Token Plan / 充值地址 */
  tokenPlanUrl?: string
  /** Coding Plan 地址 */
  codingPlanUrl?: string
  /** 官网地址 */
  websiteUrl?: string
  /** 国家/地区标签 */
  region: 'CN' | 'GLOBAL'
  /** 简短说明 */
  description: string
}

/**
 * 国内外 AI 供应商预设列表
 * 数据截至 2026-05-15，各厂商定价可能调整，请以官网为准
 */
export const AI_PROVIDER_PRESETS: AiProviderPreset[] = [
  // ===== 本地部署 =====
  {
    name: 'Ollama (本地)',
    id: 'ollama',
    provider: 'OLLAMA',
    baseUrl: 'http://localhost:11434',
    models: [
      { name: 'qwen3:8b', label: 'Qwen3 8B', free: true },
      { name: 'qwen3:14b', label: 'Qwen3 14B', free: true },
      { name: 'deepseek-r1:8b', label: 'DeepSeek R1 8B', free: true },
      { name: 'llama3.3:8b', label: 'Llama3.3 8B', free: true },
      { name: 'gemma3:4b', label: 'Gemma3 4B', free: true },
      { name: 'mistral:7b', label: 'Mistral 7B', free: true },
    ],
    websiteUrl: 'https://ollama.com',
    region: 'GLOBAL',
    description: '本地部署，无需 API Key，完全免费',
  },

  // ===== 国内云服务 =====
  {
    name: 'DeepSeek 深度求索',
    id: 'deepseek',
    provider: 'OPENAI',
    baseUrl: 'https://api.deepseek.com/v1',
    models: [
      { name: 'deepseek-v4-flash', label: 'V4 Flash 极速 (推荐)' },
      { name: 'deepseek-v4-pro', label: 'V4 Pro 旗舰' },
    ],
    apiKeyUrl: 'https://platform.deepseek.com/api_keys',
    tokenPlanUrl: 'https://platform.deepseek.com/usage',
    websiteUrl: 'https://platform.deepseek.com',
    region: 'CN',
    description: 'V4 Flash 输入¥1/输出¥2/M，V4 Pro 2.5折中(¥3/¥6)，1M上下文384K输出',
  },
  {
    name: '通义千问 Qwen',
    id: 'qwen',
    provider: 'OPENAI',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    models: [
      { name: 'qwen3.6-max-preview', label: 'Qwen3.6 Max 旗舰 (推荐)' },
      { name: 'qwen3.6-plus', label: 'Qwen3.6 Plus 均衡' },
      { name: 'qwen3.6-flash', label: 'Qwen3.6 Flash 极速' },
      { name: 'qwq-plus', label: 'QwQ Plus 推理' },
      { name: 'qwen-coder-turbo', label: 'Qwen Coder 代码' },
    ],
    apiKeyUrl: 'https://bailian.console.aliyun.com/',
    tokenPlanUrl: 'https://bailian.console.aliyun.com/',
    codingPlanUrl: 'https://tongyi.aliyun.com/pricing',
    websiteUrl: 'https://tongyi.aliyun.com',
    region: 'CN',
    description: 'Qwen3.6 最新一代，Flash 输入 ¥0.15/M 起，100万tokens免费',
  },
  {
    name: '智谱 GLM',
    id: 'zhipu',
    provider: 'OPENAI',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    models: [
      { name: 'glm-5.1', label: 'GLM-5.1 最新旗舰 (推荐)' },
      { name: 'glm-5', label: 'GLM-5 745B' },
      { name: 'glm-5-turbo', label: 'GLM-5 Turbo 快速' },
      { name: 'glm-4.7', label: 'GLM-4.7' },
      { name: 'glm-4-flash', label: 'GLM-4 Flash 免费', free: true },
    ],
    apiKeyUrl: 'https://open.bigmodel.cn/usercenter/apikeys',
    tokenPlanUrl: 'https://open.bigmodel.cn/usercenter/overview',
    codingPlanUrl: 'https://open.bigmodel.cn/pricing',
    websiteUrl: 'https://open.bigmodel.cn',
    region: 'CN',
    description: 'GLM-5.1 最新旗舰 200K 上下文，Flash 免费，Coding ¥49/月',
  },
  {
    name: 'Kimi 月之暗面',
    id: 'moonshot',
    provider: 'OPENAI',
    baseUrl: 'https://api.moonshot.cn/v1',
    models: [
      { name: 'k2.6', label: 'Kimi K2.6 最新 (推荐)' },
      { name: 'k2.5', label: 'Kimi K2.5 旗舰' },
      { name: 'k2', label: 'Kimi K2' },
      { name: 'moonshot-v1-128k', label: 'Moonshot V1 128K' },
    ],
    apiKeyUrl: 'https://platform.moonshot.cn/console/api-keys',
    tokenPlanUrl: 'https://platform.moonshot.cn/console/usage',
    codingPlanUrl: 'https://platform.moonshot.cn/code',
    websiteUrl: 'https://platform.moonshot.cn',
    region: 'CN',
    description: 'K2.6 开源 1T 参数，原生视觉+Agent 集群，长文本最强',
  },
  {
    name: '豆包 火山引擎',
    id: 'doubao',
    provider: 'OPENAI',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    models: [
      { name: 'doubao-seed-2.0-pro', label: 'Seed 2.0 Pro 旗舰 (推荐)' },
      { name: 'doubao-seed-2.0-code', label: 'Seed 2.0 Code 代码' },
      { name: 'doubao-seed-2.0-lite', label: 'Seed 2.0 Lite 轻量' },
      { name: 'doubao-seed-2.0-mini', label: 'Seed 2.0 Mini 最经济' },
    ],
    apiKeyUrl: 'https://www.volcengine.com/product/doubao',
    tokenPlanUrl: 'https://console.volcengine.com/ark',
    codingPlanUrl: 'https://www.volcengine.com/product/doubao',
    websiteUrl: 'https://www.volcengine.com/product/doubao',
    region: 'CN',
    description: 'Seed 2.0 全系 256K 上下文，Pro 接近 Claude Sonnet 但更便宜',
  },
  {
    name: '小米 MiMo',
    id: 'mimo',
    provider: 'OPENAI',
    baseUrl: 'https://api.xiaomimimo.com/v1',
    models: [
      { name: 'mimo-v2.5-pro', label: 'MiMo V2.5 Pro (推荐)' },
      { name: 'mimo-v2.5-flash', label: 'MiMo V2.5 Flash 极速' },
      { name: 'mimo-v2-pro', label: 'MiMo V2 Pro' },
      { name: 'mimo-v2-flash', label: 'MiMo V2 Flash 免费', free: true },
    ],
    apiKeyUrl: 'https://platform.xiaomimimo.com/',
    tokenPlanUrl: 'https://platform.xiaomimimo.com/',
    codingPlanUrl: 'https://platform.xiaomimimo.com/',
    websiteUrl: 'https://platform.xiaomimimo.com',
    region: 'CN',
    description: 'V2.5 Flash 输入 $0.1/M 全网最低，Pro 1T参数 1M上下文',
  },
  {
    name: 'MiniMax',
    id: 'minimax',
    provider: 'OPENAI',
    baseUrl: 'https://api.minimax.chat/v1',
    models: [
      { name: 'MiniMax-M2.7', label: 'M2.7 最新旗舰 (推荐)' },
      { name: 'MiniMax-M2.7-Highspeed', label: 'M2.7 高速版' },
      { name: 'MiniMax-M2.5', label: 'M2.5 开源经济' },
    ],
    apiKeyUrl: 'https://platform.minimaxi.com/',
    tokenPlanUrl: 'https://platform.minimaxi.com/',
    codingPlanUrl: 'https://platform.minimaxi.com/pricing',
    websiteUrl: 'https://platform.minimaxi.com',
    region: 'CN',
    description: 'M2.7 230B 接近 Claude Opus 4.6，入门 ¥29/月',
  },
  {
    name: '硅基流动 SiliconFlow',
    id: 'siliconflow',
    provider: 'OPENAI',
    baseUrl: 'https://api.siliconflow.cn/v1',
    models: [
      { name: 'deepseek-ai/DeepSeek-V4-Flash', label: 'DeepSeek V4 Flash', free: true },
      { name: 'Qwen/Qwen3-8B', label: 'Qwen3 8B 免费', free: true },
      { name: 'deepseek-ai/DeepSeek-V4-Pro', label: 'DeepSeek V4 Pro' },
      { name: 'Pro/deepseek-ai/DeepSeek-R1', label: 'DeepSeek R1' },
    ],
    apiKeyUrl: 'https://cloud.siliconflow.cn/account/ak',
    tokenPlanUrl: 'https://cloud.siliconflow.cn/account/usage',
    websiteUrl: 'https://siliconflow.cn',
    region: 'CN',
    description: 'API 聚合平台，DeepSeek V4 Flash 等模型免费',
  },
  {
    name: '阶跃星辰 StepFun',
    id: 'stepfun',
    provider: 'OPENAI',
    baseUrl: 'https://api.stepfun.com/v1',
    models: [
      { name: 'step-3.5-flash', label: 'Step 3.5 Flash (推荐)' },
      { name: 'step-3.5-flash-2603', label: 'Step 3.5 Flash 2603 最新' },
      { name: 'step-2', label: 'Step 2 旗舰' },
    ],
    apiKeyUrl: 'https://platform.stepfun.com/',
    tokenPlanUrl: 'https://platform.stepfun.com/pricing',
    codingPlanUrl: 'https://platform.stepfun.com/step_plan',
    websiteUrl: 'https://www.stepfun.com',
    region: 'CN',
    description: 'Step 3.5 Flash 专为 Agent 设计 350TPS，Coding ¥25/月半价中',
  },
  {
    name: '百川 Baichuan',
    id: 'baichuan',
    provider: 'OPENAI',
    baseUrl: 'https://api.baichuan-ai.com/v1',
    models: [
      { name: 'Baichuan4', label: 'Baichuan4 旗舰' },
      { name: 'Baichuan3-Turbo', label: 'Baichuan3 Turbo' },
    ],
    apiKeyUrl: 'https://platform.baichuan-ai.com/console/apikey',
    tokenPlanUrl: 'https://platform.baichuan-ai.com/console/usage',
    websiteUrl: 'https://www.baichuan-ai.com',
    region: 'CN',
    description: '百川智能，中文理解能力强',
  },
  {
    name: '讯飞星火 Spark',
    id: 'spark',
    provider: 'OPENAI',
    baseUrl: 'https://spark-api.xf-yun.com/v1',
    models: [
      { name: '4.0Ultra', label: 'Spark 4.0 Ultra (推荐)' },
      { name: 'max-3', label: 'Spark Max' },
      { name: 'lite', label: 'Spark Lite 免费', free: true },
    ],
    apiKeyUrl: 'https://xinghuo.xfyun.cn/sparkapi',
    tokenPlanUrl: 'https://xinghuo.xfyun.cn/sparkapi',
    websiteUrl: 'https://xinghuo.xfyun.cn',
    region: 'CN',
    description: 'Spark Lite 永久免费，新用户送 200万 tokens',
  },
  {
    name: '腾讯混元 Hunyuan',
    id: 'hunyuan',
    provider: 'OPENAI',
    baseUrl: 'https://hunyuan.tencentcloudapi.com/v1',
    models: [
      { name: 'hunyuan-turbo-s', label: '混元 Turbo S 最新 (推荐)' },
      { name: 'hunyuan-turbo', label: '混元 Turbo' },
      { name: 'hunyuan-lite', label: '混元 Lite 免费', free: true },
    ],
    apiKeyUrl: 'https://cloud.tencent.com/product/hunyuan',
    tokenPlanUrl: 'https://console.cloud.tencent.com/hunyuan',
    websiteUrl: 'https://cloud.tencent.com/product/hunyuan',
    region: 'CN',
    description: '混元 Lite 完全免费，Turbo S 最新旗舰，腾讯云旗下',
  },

  // ===== 国际云服务 =====
  {
    name: 'OpenAI',
    id: 'openai',
    provider: 'OPENAI',
    baseUrl: 'https://api.openai.com/v1',
    models: [
      { name: 'gpt-5.5', label: 'GPT-5.5 最新旗舰 (推荐)' },
      { name: 'gpt-5', label: 'GPT-5' },
      { name: 'gpt-4o', label: 'GPT-4o' },
      { name: 'gpt-4o-mini', label: 'GPT-4o Mini 经济' },
      { name: 'o3', label: 'o3 推理' },
      { name: 'o3-mini', label: 'o3-mini 推理经济' },
    ],
    apiKeyUrl: 'https://platform.openai.com/api-keys',
    tokenPlanUrl: 'https://platform.openai.com/usage',
    codingPlanUrl: 'https://openai.com/pricing',
    websiteUrl: 'https://platform.openai.com',
    region: 'GLOBAL',
    description: 'GPT-5.5 输入 $5/M / 输出 $30/M，1.05M 上下文',
  },
  {
    name: 'Anthropic Claude',
    id: 'anthropic',
    provider: 'OPENAI',
    baseUrl: 'https://api.anthropic.com/v1',
    models: [
      { name: 'claude-opus-4-6-20260401', label: 'Claude Opus 4.6 旗舰 (推荐)' },
      { name: 'claude-sonnet-4-6-20260401', label: 'Claude Sonnet 4.6 均衡' },
      { name: 'claude-haiku-4-5-20250514', label: 'Claude Haiku 4.5 经济' },
    ],
    apiKeyUrl: 'https://console.anthropic.com/settings/keys',
    tokenPlanUrl: 'https://console.anthropic.com/settings/cost',
    codingPlanUrl: 'https://claude.com/pricing',
    websiteUrl: 'https://www.anthropic.com',
    region: 'GLOBAL',
    description: 'Opus 4.6 输入 $5/M / Sonnet 4.6 $3/M，Prompt缓存降90%',
  },
  {
    name: 'Google Gemini',
    id: 'gemini',
    provider: 'OPENAI',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    models: [
      { name: 'gemini-3.1-pro', label: 'Gemini 3.1 Pro 最新 (推荐)' },
      { name: 'gemini-2.5-pro', label: 'Gemini 2.5 Pro' },
      { name: 'gemini-2.5-flash', label: 'Gemini 2.5 Flash' },
      { name: 'gemini-2.5-flash-lite', label: 'Gemini 2.5 Flash Lite 最经济' },
    ],
    apiKeyUrl: 'https://aistudio.google.com/apikey',
    tokenPlanUrl: 'https://aistudio.google.com/apikey',
    websiteUrl: 'https://aistudio.google.com',
    region: 'GLOBAL',
    description: '3.1 Pro $2/M输入，Flash-Lite $0.1/M，2M上下文',
  },
  {
    name: 'xAI Grok',
    id: 'xai',
    provider: 'OPENAI',
    baseUrl: 'https://api.x.ai/v1',
    models: [
      { name: 'grok-4', label: 'Grok 4 旗舰 (推荐)' },
      { name: 'grok-4-fast', label: 'Grok 4 Fast 快速' },
      { name: 'grok-3', label: 'Grok 3' },
    ],
    apiKeyUrl: 'https://console.x.ai/',
    tokenPlanUrl: 'https://console.x.ai/usage',
    websiteUrl: 'https://x.ai',
    region: 'GLOBAL',
    description: 'Grok 4 Fast 输入 $0.2/M 输出 $0.5/M，2M上下文',
  },
  {
    name: 'Mistral AI',
    id: 'mistral',
    provider: 'OPENAI',
    baseUrl: 'https://api.mistral.ai/v1',
    models: [
      { name: 'mistral-large-3', label: 'Mistral Large 3 (推荐)' },
      { name: 'mistral-small-3.1', label: 'Mistral Small 3.1 经济' },
      { name: 'codestral-latest', label: 'Codestral 代码' },
    ],
    apiKeyUrl: 'https://console.mistral.ai/api-keys',
    tokenPlanUrl: 'https://console.mistral.ai/usage',
    websiteUrl: 'https://mistral.ai',
    region: 'GLOBAL',
    description: 'Large 3 $2/M / Small 3.1 $0.2/M，Apache 2.0 开源',
  },
  {
    name: 'OpenRouter',
    id: 'openrouter',
    provider: 'OPENAI',
    baseUrl: 'https://openrouter.ai/api/v1',
    models: [
      { name: 'deepseek/deepseek-v4-flash', label: 'DeepSeek V4 Flash' },
      { name: 'anthropic/claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
      { name: 'google/gemini-3.1-pro', label: 'Gemini 3.1 Pro' },
      { name: 'openai/gpt-5.5', label: 'GPT-5.5' },
    ],
    apiKeyUrl: 'https://openrouter.ai/settings/keys',
    tokenPlanUrl: 'https://openrouter.ai/settings/credits',
    websiteUrl: 'https://openrouter.ai',
    region: 'GLOBAL',
    description: 'API 聚合平台，一个 Key 调用 400+ 模型，按需付费',
  },
  {
    name: 'Groq',
    id: 'groq',
    provider: 'OPENAI',
    baseUrl: 'https://api.groq.com/openai/v1',
    models: [
      { name: 'llama-3.3-70b-versatile', label: 'Llama 3.3 70B (推荐)' },
      { name: 'llama-3.1-8b-instant', label: 'Llama 3.1 8B 快速' },
      { name: 'mixtral-8x7b-32768', label: 'Mixtral 8x7B' },
    ],
    apiKeyUrl: 'https://console.groq.com/keys',
    tokenPlanUrl: 'https://console.groq.com/usage',
    websiteUrl: 'https://groq.com',
    region: 'GLOBAL',
    description: '超低延迟推理，Llama 3.3 70B 免费额度',
  },
]

export interface AiProviderConfig {
  id?: number
  /** 配置名称（用于多配置时区分显示） */
  name: string
  /** 配置用途：CHAT=对话, TAG=标签评分, EMBEDDING=向量, VISION=OCR */
  purpose: string
  /** 供应商类型：OLLAMA 或 OPENAI */
  provider: string
  /** API 基础地址 */
  baseUrl: string
  /** 模型名称 */
  modelName: string
  /** API Key（OpenAI 兼容接口需要，Ollama 可为空） */
  apiKey?: string
  /** 温度参数 */
  temperature?: number
  /** 超时时间（秒） */
  timeout?: number
  /** 是否支持 Tool Calling（null=自动检测） */
  toolsEnabled?: boolean | null
  /** 是否启用 */
  enabled?: boolean
  /** 是否为当前 purpose 的默认（激活）配置 */
  isDefault?: boolean
  createdAt?: string
  updatedAt?: string
}

/** 获取所有 AI 配置 */
export function listAiConfigs() {
  return request.get<AiProviderConfig[]>('/admin/ai-config')
}

/** 按用途获取所有配置列表 */
export function listAiConfigsByPurpose(purpose: string) {
  return request.get<AiProviderConfig[]>(`/admin/ai-config/purpose/${purpose}`)
}

/** 按用途获取默认（激活）配置 */
export function getDefaultAiConfig(purpose: string) {
  return request.get<AiProviderConfig>(`/admin/ai-config/${purpose}/default`)
}

/** 创建 AI 配置 */
export function createAiConfig(data: AiProviderConfig) {
  return request.post<AiProviderConfig>('/admin/ai-config', data)
}

/** 更新 AI 配置 */
export function updateAiConfig(id: number, data: AiProviderConfig) {
  return request.put<AiProviderConfig>(`/admin/ai-config/${id}`, data)
}

/** 删除 AI 配置 */
export function deleteAiConfig(id: number) {
  return request.delete(`/admin/ai-config/${id}`)
}

/** 切换默认（激活）配置 */
export function switchDefaultConfig(id: number) {
  return request.post<AiProviderConfig>(`/admin/ai-config/${id}/switch-default`)
}

/** 测试 AI 配置连接 */
export function testAiConfig(id: number) {
  return request.post<string>(`/admin/ai-config/${id}/test`)
}
