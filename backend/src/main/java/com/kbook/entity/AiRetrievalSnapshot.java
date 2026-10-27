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
 * 单次回答的检索依据快照：记录"这条回答实际看到了哪些 chunk"。
 * <p>
 * 与 {@code ai_conversations} 一行 assistant 对应，用于评测归因、引用展示与失败复盘。
 * 只存片段引用与预览，不存完整正文（正文仍在 Qdrant / books.full_text）。
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_retrieval_snapshots", indexes = {
        @Index(name = "idx_snapshot_conv", columnList = "conversation_id"),
        @Index(name = "idx_snapshot_book_chunk", columnList = "book_id, chunk_index")
})
public class AiRetrievalSnapshot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对应 ai_conversations.id（assistant 行） */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 合并块的跨度终点；单块时等于 chunkIndex */
    @Column(name = "chunk_end_index")
    private Integer chunkEndIndex;

    /** 向量相似度分（合并块取组内最高分） */
    @Column(name = "score")
    private Double score;

    /** 预留：rerank 分 */
    @Column(name = "rerank_score")
    private Double rerankScore;

    /** 来源：context / neighbor / anchor / llm_refine / list */
    @Column(name = "source", length = 20)
    private String source;

    /** 片段文本 SHA-256，便于去重与一致性校验 */
    @Column(name = "text_hash", length = 64)
    private String textHash;

    /** 片段前 200 字预览，便于直接查看依据 */
    @Column(name = "text_preview", length = 200)
    private String textPreview;

    /** 生成该回答时使用的系统提示词版本 */
    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Override
    public Long getId() {
        return id;
    }
}
