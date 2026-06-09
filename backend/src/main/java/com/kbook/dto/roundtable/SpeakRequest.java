package com.kbook.dto.roundtable;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单角色发言请求 — 前端指定某个角色发言，后端从 DB 加载历史并调用 AI 生成发言内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpeakRequest {

    /** 发言角色键名（如 HOST、PHILOSOPHER） */
    @NotBlank(message = "角色键名不能为空")
    private String roleKey;

    /** 会话 ID（服务端根据此 ID 从数据库加载对话历史） */
    @NotBlank(message = "会话ID不能为空")
    private String sessionId;

    /** 话题方向（可选，仅 HOST 使用，用于指定新的讨论方向） */
    private String topic;
}
