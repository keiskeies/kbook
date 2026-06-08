package com.kbook.dto.recommend;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐项数据传输对象
 * 包含推荐图书的完整信息，含各推荐维度的评分和召回路径
 */
@Data
@Builder
public class RecommendedItem {
    /** 图书ID */
    private Long bookId;
    /** 书名 */
    private String title;
    /** 作者 */
    private String author;
    /** 封面URL */
    private String coverUrl;
    /** 图书格式：EPUB/PDF/TXT */
    private String format;
    /** 评分 */
    private Double rating;
    /** 图书简介 */
    private String description;
    /** 匹配度评分（0-1） */
    private Double matchScore;
    /** 阅读次数 */
    private Long readCount;
    /** 格式标签（JSON数组字符串） */
    private String formatTags;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 规则评分 */
    private Double ruleScore;
    /** 向量评分 */
    private Double vectorScore;
    /** 协同过滤评分 */
    private Double collabScore;
    /** 召回路径（记录推荐来源） */
    private String recallPaths;
    /** 推荐时间 */
    private LocalDateTime recommendedAt;
}
