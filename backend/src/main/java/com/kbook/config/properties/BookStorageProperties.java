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

    /** 视频处理配置 */
    private VideoConfig video = new VideoConfig();

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

    @Data
    public static class VideoConfig {
        /** FFmpeg 可执行文件路径 */
        private String ffmpegPath = "ffmpeg";

        /** 缩略图配置 */
        private ThumbnailConfig thumbnail = new ThumbnailConfig();

        /** 转码配置 */
        private TranscodeConfig transcode = new TranscodeConfig();

        @Data
        public static class ThumbnailConfig {
            /** 是否启用视频缩略图生成 */
            private boolean enabled = false;
            /** 缩略图宽度（像素），高度自动等比缩放 */
            private int width = 320;
            /** JPEG 质量 (FFmpeg -q:v, 2-31, 越小越好) */
            private int quality = 3;
        }

        @Data
        public static class TranscodeConfig {
            /** 是否启用视频转码 */
            private boolean enabled = false;
            /** 最大宽度（像素），超过则等比缩放 */
            private int maxWidth = 1280;
            /** H.264 CRF 质量 (0-51, 越小越好, 23 为视觉无损) */
            private int crf = 23;
            /** 音频编码器 */
            private String audioCodec = "aac";
        }
    }
}
