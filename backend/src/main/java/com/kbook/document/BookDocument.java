package com.kbook.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * 图书 ES 文档 — 用于全文检索
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "kbook-books")
@Setting(replicas = 0)
public class BookDocument {

    /** 图书 ID */
    @Id
    private Long id;

    /** 书名 — 中文分词 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    /** 作者 — 中文分词 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String author;

    /** 简介 — 中文分词 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String description;

    /** 格式：TXT / EPUB / PDF */
    @Field(type = FieldType.Keyword)
    private String format;

    /** 格式标签 JSON */
    @Field(type = FieldType.Keyword)
    private String formatTags;

    /** 封面 URL */
    @Field(type = FieldType.Keyword, index = false)
    private String coverUrl;

    /** 文件大小 */
    @Field(type = FieldType.Long, index = false)
    private Long fileSize;

    /** 阅读次数 */
    @Field(type = FieldType.Long)
    private Long readCount;

    /** 评分 */
    @Field(type = FieldType.Double)
    private Double rating;

    /** 总字符数/页数 */
    @Field(type = FieldType.Long, index = false)
    private Long totalUnits;

    /** 文件路径 */
    @Field(type = FieldType.Keyword, index = false)
    private String fileUrl;

    /** 创建时间（时间戳） */
    @Field(type = FieldType.Long)
    private Long createdAt;
}
