package com.kbook.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 通用工具类 - 图片处理、文件服务等
 */
@Slf4j
public class CommonUtils {

    private CommonUtils() {
        // 防止实例化
    }

    /**
     * 估算文本的 token 数量
     * 粗略估算：中文约1字符=1token，英文约4字符=1token
     *
     * @param text 待估算的文本
     * @return 估算的token数量
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= '一' && c <= '鿿') {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        // 中文字符按1:1估算，其他字符按4:1估算
        return chineseChars + (otherChars / 4);
    }

    /**
     * 等比例压缩图片，限制最大宽度
     *
     * @param srcImage 源图片
     * @param format   图片格式（png/jpg/gif/webp）
     * @param maxWidth 最大宽度（像素）
     * @return 压缩后的图片
     */
    public static BufferedImage compressImage(BufferedImage srcImage, String format, int maxWidth) {
        int srcWidth = srcImage.getWidth();
        int srcHeight = srcImage.getHeight();

        if (srcWidth <= maxWidth) {
            return srcImage; // 不需要压缩
        }

        double scale = (double) maxWidth / srcWidth;
        int newHeight = (int) Math.round(srcHeight * scale);

        BufferedImage resized = new BufferedImage(maxWidth, newHeight,
                format.equalsIgnoreCase("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(srcImage, 0, 0, maxWidth, newHeight, null);
        g2d.dispose();

        log.debug("图片压缩: {}x{} -> {}x{}", srcWidth, srcHeight, maxWidth, newHeight);
        return resized;
    }

    /**
     * 等比例压缩封面图片（兼容性方法）
     *
     * @param srcImage 源图片
     * @param format   图片格式
     * @param maxWidth 最大宽度
     * @return 压缩后的图片
     * @deprecated 使用 {@link #compressImage(BufferedImage, String, int)}
     */
    @Deprecated
    public static BufferedImage compressCover(BufferedImage srcImage, String format, int maxWidth) {
        return compressImage(srcImage, format, maxWidth);
    }

    /**
     * 根据文件名获取 MediaType
     *
     * @param filename 文件名
     * @return MediaType
     */
    public static MediaType getMediaTypeByFilename(String filename) {
        if (filename == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String name = filename.toLowerCase();
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG_VALUE.equals(MediaType.IMAGE_PNG.toString()) 
                    ? MediaType.IMAGE_PNG 
                    : MediaType.parseMediaType("image/png");
        } else if (name.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        } else if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.IMAGE_JPEG; // 默认返回 JPEG
    }

    /**
     * 构建图片响应实体
     *
     * @param imagePath 图片路径
     * @param filename  文件名
     * @return ResponseEntity
     */
    public static ResponseEntity<Resource> buildImageResponse(Path imagePath, String filename) {
        MediaType contentType = getMediaTypeByFilename(filename);
        FileSystemResource resource = new FileSystemResource(imagePath);
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .body(resource);
    }

    /**
     * 安全地解析文件路径，防止路径穿越攻击
     *
     * @param basePath   基础路径
     * @param targetPath 目标路径
     * @return 规范化后的路径，如果存在路径穿越则返回 null
     */
    public static Path safeResolvePath(Path basePath, String targetPath) {
        if (basePath == null || targetPath == null) {
            return null;
        }
        Path resolved = basePath.resolve(targetPath).normalize();
        if (!resolved.startsWith(basePath.normalize())) {
            log.warn("检测到潜在的路径穿越攻击: {}", targetPath);
            return null;
        }
        return resolved;
    }

    /**
     * 截断文本到指定长度
     *
     * @param text   原始文本
     * @param maxLen 最大长度
     * @return 截断后的文本
     */
    public static String truncateText(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 格式化 AI 调用日志
     *
     * @param operation      操作名称（如"标签生成"、"评分生成"）
     * @param elapsed        耗时（毫秒）
     * @param inputTokens    输入 token 数
     * @param outputTokens   输出 token 数
     * @param result         结果摘要
     */
    public static void logAiCall(String operation, long elapsed, int inputTokens, int outputTokens, String result) {
        double totalTokens = inputTokens + outputTokens;
        double tokensPerSecond = (elapsed > 0) ? (totalTokens / (elapsed / 1000.0)) : 0;

        log.info("========== AI {} ==========", operation);
        log.info("结果: {}", result);
        log.info("耗时: {}ms | 输入tokens: {} | 输出tokens: {} | 总tokens: {} | 速度: {} tokens/s",
                elapsed, inputTokens, outputTokens, (int) totalTokens, String.format("%.2f", tokensPerSecond));
        log.info("====================================\n");
    }
}
