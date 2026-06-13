package com.kbook.dto.debate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 辩论发言请求 — 前端指定某个辩手发言，后端调用 AI 生成发言内容
 * <p>
 * 包含轮次类型和轮次号，用于构建正确的提示词和发言顺序。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateSpeakRequest {

    /** 发言角色键名 */
    @NotBlank(message = "角色键名不能为空")
    private String roleKey;

    /** 会话 ID */
    @NotBlank(message = "会话ID不能为空")
    private String sessionId;

    /** 轮次类型：OPENING / ATTACK / FREE / CLOSING */
    @NotBlank(message = "轮次类型不能为空")
    private String roundType;

    /** 轮次号 1-5 */
    @NotNull(message = "轮次号不能为空")
    private Integer roundNumber;

    /** 质询角色：QUESTIONER / ANSWERER（仅 CROSS_EXAM 轮使用） */
    private String examRole;
}
