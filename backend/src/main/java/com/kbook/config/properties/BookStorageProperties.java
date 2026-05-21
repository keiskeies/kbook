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
        private String chatDir = "./uploads/chat";
        private String chatUrlPrefix = "/api/uploads/chat";
    }

    @Data
    public static class ScanConfig {
    }
}
