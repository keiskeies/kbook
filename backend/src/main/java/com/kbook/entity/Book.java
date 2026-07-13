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
 * 图书实体
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "books")
public class Book extends BaseEntity {

    /** 主键 ID */
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

    /** 核心概念标签（JSON 数组） */
    @Column(name = "concept_tags", length = 500)
    private String conceptTags;

    /** 读者需求标签（JSON 数组） */
    @Column(name = "reader_need_tags", length = 500)
    private String readerNeedTags;

    /** 目标读者标签（JSON 数组） */
    @Column(name = "target_reader_tags", length = 500)
    private String targetReaderTags;

    /** 目录（从 EPUB/PDF 提取的章节标题，每行一个） */
    @Column(name = "toc", columnDefinition = "TEXT")
    private String toc;

    /** 核心章节摘要（从开头几章提取的代表性文本，用于增强向量语义） */
    @Column(name = "chapter_summary", columnDefinition = "TEXT")
    private String chapterSummary;

    /** 压缩精炼摘要（LLM 对 chapterSummary+标签+目录 精炼后的结构化摘要，用于问答上下文） */
    @Column(name = "compressed_summary", columnDefinition = "TEXT")
    private String compressedSummary;

    /**
     * TOC 质量评分（0.0~1.0），用于列表型问题 RAG 策略选择。
     * null 表示未评估；toc/chapterSummary 变更时必须重置为 null。
     */
    @Column(name = "toc_quality_score")
    private Float tocQualityScore;

    /**
     * 自定义 setToc：toc 变更时清空质量评分，触发下次评估重新计算。
     * 覆盖 Lombok 自动生成的 setter。
     */
    public void setToc(String toc) {
        this.toc = toc;
        this.tocQualityScore = null;
    }

    /**
     * 自定义 setChapterSummary：chapterSummary 变更时清空 toc 质量评分。
     * 因为 chapterSummary 是 toc 的语义补充（章节摘要），其变更可能影响 toc 评估。
     */
    public void setChapterSummary(String chapterSummary) {
        this.chapterSummary = chapterSummary;
        this.tocQualityScore = null;
    }

    /**
     * 14维度相关度得分（JSON对象）
     * 维度：ageGroup(年龄段), male(男性), female(女性), married(已婚),
     *       unmarried(未婚), hasChildren(有孩子), noChildren(无孩子), mbti(MBTI匹配),
     *       occupation(职业: student/tech/finance/education/medical/arts/management/freelance/retired/other),
     *       education(学历: high_school/college/bachelor/master/doctorate/other_edu),
     *       entrepreneurship(entrepreneur_or_want/notInterested),
     *       income(under_50k/50k_150k/150k_300k/300k_500k/500k_1m/over_1m/prefer_not_to_say),
     *       mood(happy/calm/anxious/sad/motivated/tired/curious)
     * 格式：{"0-9":0.5,"10-19":0.8,...,"male":0.7,...,"entrepreneur":0.6,...,"under_50k":0.3,...,"happy":0.6,...}
     */
    @Column(name = "relevance_scores", columnDefinition = "TEXT")
    private String relevanceScores;

    /** 解析内容（不持久化，仅用于 AI 标签生成时传递） */
    @Transient
    private String parsedContent;

    /** 全书RAG内容（不持久化，仅用于内容向量生成时传递，避免二次文件读取） */
    @Transient
    private String ragContent;

    /** 总字符数（TXT/Epub）或总页数（PDF） */
    @Column(name = "total_units")
    private Long totalUnits;

    /** 阅读次数 */
    @Column(name = "read_count")
    @Builder.Default
    private Long readCount = 0L;

    /** 评分 (1.0-5.0，数据库存储6位小数) */
    @Column(columnDefinition = "DECIMAL(7,6) DEFAULT 0.000000")
    @Builder.Default
    private Double rating = 0.0;

    /** 实际评分人数（AI初评不计入，仅统计用户评分） */
    @Column(name = "rating_count")
    @Builder.Default
    private Long ratingCount = 0L;

    /** 维度打分人数（基准1000 + 实际用户打分人数） */
    @Column(name = "dimension_rating_count")
    @Builder.Default
    private Integer dimensionRatingCount = 0;

    /** 全书内容是否已存储到 Qdrant（用于 RAG 语义检索） */
    @Column(name = "content_embedded")
    @Builder.Default
    private Boolean contentEmbedded = false;

    /**
     * 内容向量生成时使用的 embedding 模型标识（baseUrl + "/" + modelName）
     * 用于一致性校验：当前配置模型与此处不一致时，标记需要重建向量
     */
    @Column(name = "content_embedding_model", length = 255)
    private String contentEmbeddingModel;

    /**
     * 内容向量维度（冗余校验字段，防止同名模型维度变更）
     */
    @Column(name = "content_embedding_dim")
    private Integer contentEmbeddingDim;

    @Override
    public Long getId() {
        return id;
    }
}
