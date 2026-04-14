package com.kbook.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 图书实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "books")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 书名 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 作者 */
    @Column(length = 100)
    private String author;

    /** 封面 URL */
    @Column(length = 500)
    private String coverUrl;

    /** 简介 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 格式：TXT / EPUB / PDF */
    @Column(nullable = false, length = 10)
    private String format;

    /** 文件路径/URL */
    @Column(name = "file_url", length = 500)
    private String fileUrl;

    /** 文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 格式标签（JSON 数组） */
    @Column(name = "format_tags", length = 500)
    private String formatTags;

    /** 目录（从 EPUB/PDF 提取的章节标题，每行一个） */
    @Column(name = "toc", columnDefinition = "TEXT")
    private String toc;

    /** 核心章节摘要（从开头几章提取的代表性文本，用于增强向量语义） */
    @Column(name = "chapter_summary", columnDefinition = "TEXT")
    private String chapterSummary;

    /**
     * 8维度相关度得分（JSON对象）
     * 维度：ageGroup(年龄段), male(男性), female(女性), married(已婚),
     *       unmarried(未婚), hasChildren(有孩子), noChildren(无孩子), mbti(MBTI匹配)
     * 格式：{"0-9":0.5,"10-19":0.8,...,"male":0.7,"female":0.3,...,"INTJ":0.9,...}
     */
    @Column(name = "relevance_scores", columnDefinition = "TEXT")
    private String relevanceScores;

    /** 解析内容（不持久化，仅用于 AI 标签生成时传递） */
    @Transient
    private String parsedContent;

    /** 总字符数（TXT/EPUB）或总页数（PDF） */
    @Column(name = "total_units")
    private Long totalUnits;

    /** 阅读次数 */
    @Column(name = "read_count")
    @Builder.Default
    private Long readCount = 0L;

    /** 评分 (1.0-5.0) */
    @Column(columnDefinition = "DECIMAL(2,1) DEFAULT 0.0")
    @Builder.Default
    private Double rating = 0.0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
