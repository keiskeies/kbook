package com.kbook.dto;

import lombok.Data;

/**
 * 文本信息响应
 * 用于返回TXT格式图书的文件信息
 */
@Data
public class TextInfoResponse {
    /** 文件大小（字节） */
    private long fileSize;
    /** 文件访问URL */
    private String fileUrl;
}
