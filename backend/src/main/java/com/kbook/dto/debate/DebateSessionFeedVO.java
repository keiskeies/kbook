package com.kbook.dto.debate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 辩论会话 Feed 视图对象 — 用于发现页全局列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebateSessionFeedVO {
    private Long id;
    private String sessionId;
    private Long bookId;
    private String bookTitle;
    private String bookCoverUrl;
    private String topic;
    private String proRoleKeys;
    private String conRoleKeys;
    private Integer currentRound;
    private String currentPhase;
    private String status;
    /** 可见性：PUBLIC / PRIVATE */
    private String visibility;
    /** 是否是当前用户的会话 */
    private Boolean isOwner;
    /** 平均评分（所有消息的平均分） */
    private Double avgScore;
    /** 热度分数（用于排序） */
    private Double hotScore;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
