package com.kbook.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 书架项数据传输对象
 * 用于展示用户书架中的图书信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookshelfItem {
    /** 书架记录ID */
    private Long bookshelfId;
    
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
    
    /** 格式标签（JSON数组字符串） */
    private String formatTags;
    
    /** 文件大小（字节） */
    private Long fileSize;
    
    /** 阅读进度（0-100） */
    private Double progress;
    
    /** 当前位置标识 */
    private String currentPosition;
    
    /** 最后阅读时间 */
    private LocalDateTime lastReadAt;
    
    /** 加入书架时间 */
    private LocalDateTime addedAt;
}
