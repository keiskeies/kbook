package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 书评实体 — 一个用户对同一本书可以写多篇，按时间序列排列。
 * <p>
 * 与 {@link Comment}（书籍/章节短评）区分：书评是长文表达，带评分、标题、剧透标记，
 * 并记录写作时的阅读进度，用于后续「同一个人不同阶段的想法」对比。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "book_reviews", indexes = {
        @Index(name = "idx_review_book_created", columnList = "book_id,created_at"),
        @Index(name = "idx_review_user_created", columnList = "user_id,created_at")
})
public class BookReview extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 作者用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 图书 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 评分 1-5，可为空（纯感悟可以不打分） */
    @Column
    private Integer rating;

    /** 标题，可为空 */
    @Column(length = 100)
    private String title;

    /** 正文 Markdown */
    @Column(name = "content_md", columnDefinition = "TEXT", nullable = false)
    private String contentMd;

    /** 是否含剧透 */
    @Column(name = "is_spoiler")
    @Builder.Default
    private Boolean isSpoiler = false;

    /** 被收录为笔记的次数 */
    @Column(name = "capture_count")
    @Builder.Default
    private Integer captureCount = 0;

    /** 写作时的阅读进度百分比 0-100，快照，不随后续阅读变化 */
    @Column(name = "progress_percent")
    private Integer progressPercent;

    /** 写作时所在章节，可为空 */
    @Column(name = "chapter_id", length = 200)
    private String chapterId;

    @Override
    public Long getId() {
        return id;
    }
}
