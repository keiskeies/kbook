package com.kbook.service.ai.core.model;

/**
 * 服务商模型列表里的一个条目。
 *
 * @param id            模型 ID，填进 {@code ai_provider_config.model_name} 的值
 * @param label         人类可读名，可能为空（前端回退显示 id）
 * @param contextWindow 上下文长度 token 数，多数 OpenAI 兼容接口不返回，故常为 null
 * @param createdAt     ISO-8601 创建时间，未知为 null
 */
public record ModelSummary(String id, String label, Integer contextWindow, String createdAt) {
}
