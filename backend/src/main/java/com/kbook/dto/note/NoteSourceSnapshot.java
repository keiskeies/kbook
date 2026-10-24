package com.kbook.dto.note;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 来源快照 — 收录那一刻的来源完整副本，序列化为 JSON 存 notes.source_snapshot。
 * 来源后续被编辑或删除都不影响这份副本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteSourceSnapshot {

    /** 来源作者 ID */
    private Long authorId;
    private String authorName;
    private String authorAvatar;

    /** 来源标题（书评标题） */
    private String sourceTitle;
    /** 来源全文 */
    private String sourceContent;
    private Integer rating;
    private Boolean isSpoiler;

    /** EXCERPT 模式下我截取保留的片段 */
    private String excerpt;

    private LocalDateTime sourceCreatedAt;
}
