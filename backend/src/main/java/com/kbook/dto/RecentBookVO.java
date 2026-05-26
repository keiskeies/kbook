package com.kbook.dto;

import lombok.*;
import java.time.LocalDateTime;

/**
 * 最近阅读图书视图对象
 * 用于展示用户最近阅读过的图书信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentBookVO {
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
    /** 阅读进度（0-100） */
    private Double progress;
    /** 最后阅读时间 */
    private LocalDateTime lastReadAt;

    /**
     * 从书架项构建最近阅读视图对象
     * @param item 书架项数据
     * @return 最近阅读图书视图对象
     */
    public static RecentBookVO from(BookshelfItem item) {
        return RecentBookVO.builder()
                .bookId(item.getBookId())
                .title(item.getTitle())
                .author(item.getAuthor())
                .coverUrl(item.getCoverUrl())
                .format(item.getFormat())
                .progress(item.getProgress())
                .lastReadAt(item.getLastReadAt())
                .build();
    }
}
