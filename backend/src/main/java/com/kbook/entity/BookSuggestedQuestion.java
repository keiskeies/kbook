package com.kbook.entity;

import com.kbook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 图书预设问题表 — 存储 AI 为每本书生成的推荐提问
 */
@Getter
@Setter
@ToString
@Entity
@Table(name = "book_suggested_questions", indexes = {
        @Index(name = "idx_book_id", columnList = "book_id")
})
public class BookSuggestedQuestion extends BaseEntity {

    /** 主键 ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联图书 ID */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 预设问题内容 */
    @Column(name = "question", nullable = false, length = 500)
    private String question;

    @Override
    public Long getId() {
        return id;
    }
}
