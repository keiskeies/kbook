package com.kbook.dto.book;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建图书请求
 * 用于管理员手动创建或上传图书
 */
@Data
public class CreateBookRequest {
    /** 书名 */
    @NotBlank(message = "书名不能为空")
    private String title;
    
    /** 作者 */
    private String author;
    
    /** 封面URL */
    private String coverUrl;
    
    /** 图书简介 */
    private String description;
    
    /** 图书格式：EPUB/PDF/TXT */
    @NotBlank(message = "图书格式不能为空")
    private String format;
    
    /** 文件路径 */
    private String fileUrl;
    
    /** 文件大小（字节） */
    private Long fileSize;
    
    /** 格式标签（JSON数组字符串） */
    private String formatTags;
    
    /** 总单元数（章节数或页数） */
    private Long totalUnits;
}
