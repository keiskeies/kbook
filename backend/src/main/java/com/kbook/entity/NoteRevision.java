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
 * 笔记历史版本 — 每次编辑前，把被覆盖的旧内容存一份，供「回看某个时间点的自己」。
 * <p>
 * revisionNo 对应保存那一刻笔记的 version（旧值），笔记当前 version 始终 = 最大 revisionNo + 1。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "note_revisions", indexes = {
        @Index(name = "idx_revision_note", columnList = "note_id,revision_no")
})
public class NoteRevision extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    /** 这份快照对应的旧版本号 */
    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo;

    @Column(length = 100)
    private String title;

    @Column(name = "content_md", columnDefinition = "TEXT")
    private String contentMd;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Override
    public Long getId() {
        return id;
    }
}
