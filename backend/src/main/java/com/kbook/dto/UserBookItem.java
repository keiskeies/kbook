package com.kbook.dto;

import lombok.Data;

/**
 * 用户图书项数据传输对象
 * 用于展示用户书架中的图书简要信息及阅读进度
 */
@Data
public class UserBookItem {
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
}
