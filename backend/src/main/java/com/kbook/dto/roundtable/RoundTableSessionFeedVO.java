package com.kbook.dto.roundtable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 圆桌派会话 Feed 视图对象 — 用于发现页全局列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoundTableSessionFeedVO {
    private Long id;
    private String sessionId;
    private Long bookId;
    private String bookTitle;
    private String bookCoverUrl;
    private String title;
    private String roleKeys;
    private String status;
    /** 可见性：PUBLIC / PRIVATE */
    private String visibility;
    /** 是否是当前用户的会话 */
    private Boolean isOwner;
    /** 覆盖度评分 */
    private Double coverageScore;
    /** 热度分数（用于排序） */
    private Double hotScore;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
