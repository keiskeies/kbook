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

    /** TTS 音频缓存目录 */
    private String ttsCacheDir = "./.tts-cache";

    /** 扫描配置 */
    private ScanConfig scan = new ScanConfig();

    /** 视频处理配置 */
    private VideoConfig video = new VideoConfig();

    /** 图书文件存储路径 */
    @Data
    public static class BookPaths {
        /** EPUB 文件存储路径 */
        private String epub = "";
        /** PDF 文件存储路径 */
        private String pdf = "";
        /** TXT 文件存储路径 */
        private String txt = "";
    }

    /** 上传文件配置 */
    @Data
    public static class UploadConfig {
        /** 头像文件存储目录 */
        private String avatarDir = "./uploads/avatars";
        /** 头像访问 URL 前缀 */
        private String avatarUrlPrefix = "/api/user/avatar";
        /** 聊天文件存储目录 */
        private String chatDir = "./uploads/chat";
        /** 聊天文件访问 URL 前缀 */
        private String chatUrlPrefix = "/api/chat/files";
    }

    /** 扫描配置（预留扩展） */
    @Data
    public static class ScanConfig {
    }

    /** 视频处理配置 */
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
