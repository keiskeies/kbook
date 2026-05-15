package com.kbook.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图书存储与扫描配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "kbook")
public class BookStorageProperties {

    /** 图书文件存储路径 */
    private BookPaths bookPaths = new BookPaths();

    /** 封面图片存储路径 */
    private String coverPath = "./covers";

    /** 上传文件配置 */
    private UploadConfig upload = new UploadConfig();

    /** 扫描配置 */
    private ScanConfig scan = new ScanConfig();

    @Data
    public static class BookPaths {
        private String epub = "";
        private String pdf = "";
        private String txt = "";
    }

    @Data
    public static class UploadConfig {
        private String avatarDir = "./uploads/avatars";
        private String avatarUrlPrefix = "/api/uploads/avatars";
    }

    @Data
    public static class ScanConfig {
        private boolean forceUpdate = false;
        /** 内容向量存储的最低评分阈值 */
        private double contentEmbedMinRating = 0.0;
        /** 内容向量存储的最大文件大小（MB，0 表示不限制） */
        private double contentEmbedMaxSizeMb = 0;
        /** 合集类图书检测关键词（逗号分隔） */
        private String compilationKeywords = "合集,全集,作品集,选集,集锦,大全,丛书,套装,汇编";
        /** 合集类图书文件大小阈值（MB，0 表示不按大小判断） */
        private double compilationMaxSizeMb = 30;
    }
}
