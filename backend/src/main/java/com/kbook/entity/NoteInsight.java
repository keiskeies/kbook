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
 * 笔记成长总结 — 按时间窗把用户的私人笔记交给大模型归纳出的阶段性回顾。
 * <p>
 * 只属于本人（user_id），生成后即持久化，用户可随时回看历史上每一份总结。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "note_insights", indexes = {
        @Index(name = "idx_insight_user_created", columnList = "user_id,created_at")
})
public class NoteInsight extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 总结覆盖的时间窗起点 */
    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    /** 总结覆盖的时间窗终点 */
    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(length = 100)
    private String title;

    /** AI 生成的 Markdown 总结 */
    @Column(name = "content_md", columnDefinition = "TEXT")
    private String contentMd;

    /** 参与总结的笔记条数 */
    @Column(name = "note_count")
    private Integer noteCount;

    @Override
    public Long getId() {
        return id;
    }
}
