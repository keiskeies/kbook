package com.kbook.dto;

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
        }
        return vo;
    }
}
