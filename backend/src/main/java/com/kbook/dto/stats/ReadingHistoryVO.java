package com.kbook.dto.stats;

import com.kbook.dto.book.BookProjection;
import com.kbook.entity.Book;
import com.kbook.entity.ReadingProgress;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 阅读历史视图对象
 * 用于展示用户的阅读历史记录，包含进度信息和图书基本信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadingHistoryVO {
    /** 阅读进度记录ID */
    private Long progressId;
    /** 图书ID */
    private Long bookId;
    /** 阅读进度（0-100） */
    private Double progress;
    /** 当前位置标识 */
    private String currentPosition;
    /** 更新时间 */
    private String updatedAt;

    /** 书名 */
    private String title;
    /** 作者 */
    private String author;
    /** 封面URL */
    private String coverUrl;
    /** 图书格式：EPUB/PDF/TXT */
    private String format;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 评分 */
    private Double rating;
    /** 阅读次数 */
    private Long readCount;
    /** 图书简介 */
    private String description;

    /**
     * 从阅读进度实体和图书实体构建视图对象
     * @param rp 阅读进度实体
     * @param book 图书实体
     * @return 阅读历史视图对象
     */
    public static ReadingHistoryVO from(ReadingProgress rp, Book book) {
        ReadingHistoryVO vo = ReadingHistoryVO.builder()
                .progressId(rp.getId())
                .bookId(rp.getBookId())
                .progress(rp.getProgress())
                .currentPosition(rp.getCurrentPosition())
                .updatedAt(rp.getUpdatedAt() != null ? rp.getUpdatedAt().toString() : null)
                .build();
        // 图书信息可能为空（图书已被删除的情况）
        if (book != null) {
            vo.setTitle(book.getTitle());
            vo.setAuthor(book.getAuthor());
            vo.setCoverUrl(book.getCoverUrl());
            vo.setFormat(book.getFormat());
            vo.setFileSize(book.getFileSize());
            vo.setRating(book.getRating());
            vo.setReadCount(book.getReadCount());
            vo.setDescription(book.getDescription());
        }
        return vo;
    }

    /**
     * 从阅读进度实体和图书投影构建视图对象（批量查询场景，避免 N+1）
     * @param rp 阅读进度实体
     * @param book 图书投影（仅常用字段）
     * @return 阅读历史视图对象
     */
    public static ReadingHistoryVO from(ReadingProgress rp, BookProjection book) {
        ReadingHistoryVO vo = ReadingHistoryVO.builder()
                .progressId(rp.getId())
                .bookId(rp.getBookId())
                .progress(rp.getProgress())
                .currentPosition(rp.getCurrentPosition())
                .updatedAt(rp.getUpdatedAt() != null ? rp.getUpdatedAt().toString() : null)
                .build();
        if (book != null) {
            vo.setTitle(book.getTitle());
            vo.setAuthor(book.getAuthor());
            vo.setCoverUrl(book.getCoverUrl());
            vo.setFormat(book.getFormat());
            vo.setFileSize(book.getFileSize());
            vo.setRating(book.getRating());
            vo.setReadCount(book.getReadCount());
            vo.setDescription(book.getDescription());
        }
        return vo;
    }
}
