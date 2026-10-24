package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 笔记实体 — 用户的私人知识资产，生命周期独立于任何来源内容。
 * <p>
 * 与来源（书评等）的关系是「快照 + 弱引用」：source_snapshot 存来源的完整副本，
 * source_id 只是可丢失的指针。来源被删除时仅把 source_id 置空、source_status 置 DELETED，
 * 笔记本身与快照不受影响；book_id 等书籍信息冗余存储，保证按书聚合永远可用。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notes", indexes = {
        @Index(name = "idx_note_user_occurred", columnList = "user_id,occurred_at"),
        @Index(name = "idx_note_user_book", columnList = "user_id,book_id"),
        @Index(name = "idx_note_user_source", columnList = "user_id,source_type,source_id")
})
public class Note extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 笔记标题，可为空 */
    @Column(length = 100)
    private String title;

    /** 用户自己写的内容（批注/想法），Markdown */
    @Column(name = "content_md", columnDefinition = "TEXT")
    private String contentMd;

    /** 笔记类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "note_type", length = 30, nullable = false)
    private NoteType noteType;

    /** 来源类型；MANUAL 表示无来源的纯想法 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 30, nullable = false)
    private NoteSourceType sourceType;

    /** 来源 ID 弱引用，来源删除后置空 */
    @Column(name = "source_id")
    private Long sourceId;

    /** 来源存活状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_status", length = 20, nullable = false)
    private SourceStatus sourceStatus;

    /** 来源完整快照 JSON（NoteSourceSnapshot 序列化），与来源生死无关 */
    @Column(name = "source_snapshot", columnDefinition = "TEXT")
    private String sourceSnapshot;

    /** 收录时的展示方式：全文 / 摘录 / 仅引用 */
    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", length = 20)
    private CaptureMode captureMode;

    /** 冗余书籍信息，书评删除后仍可按书聚合 */
    @Column(name = "book_id")
    private Long bookId;

    @Column(name = "book_title", length = 200)
    private String bookTitle;

    @Column(name = "book_cover", length = 500)
    private String bookCover;

    /** 想法发生的时间，可回填；时间轴按此排序 */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 标签，逗号分隔且带首尾逗号（如 ,读书,认知,），便于 LIKE 精确匹配 */
    @Column(length = 500)
    private String tags;

    /** 置顶时间，非空即置顶；时间轴中置顶笔记始终排在最前 */
    @Column(name = "pinned_at")
    private LocalDateTime pinnedAt;

    /** 收藏时间，非空即收藏 */
    @Column(name = "favorited_at")
    private LocalDateTime favoritedAt;

    /** 编辑版本号，Phase 3 起配合 note_revisions 使用 */
    @Column
    @Builder.Default
    private Integer version = 1;

    /** 软删时间，非空即已删除 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Override
    public Long getId() {
        return id;
    }
}
