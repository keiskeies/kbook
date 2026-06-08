package com.kbook.service.tts;

import com.kbook.config.properties.BookStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * TTS 音频缓存服务
 * <p>
 * 基于本地文件系统的音频缓存，使用配置ID+文本的 SHA-256 哈希作为文件名。
 * 避免相同文本重复调用 TTS API，提升响应速度并降低 API 调用成本。
 */
@Slf4j
@Component
public class TtsCache {

    /** 缓存目录路径 */
    private final Path cacheDir;

    /**
     * 初始化缓存目录
     *
     * @param properties 存储配置属性
     */
    public TtsCache(BookStorageProperties properties) {
        this.cacheDir = Path.of(properties.getTtsCacheDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(cacheDir);
            log.info("TTS cache directory: {}", cacheDir);
        } catch (IOException e) {
            log.warn("Failed to create TTS cache directory: {}", cacheDir, e);
        }
    }

    /**
     * 获取缓存的音频数据
     *
     * @param configId TTS 配置ID
     * @param text     合成文本
     * @return 缓存的音频字节数组，未命中返回 null
     */
    public byte[] get(Long configId, String text) {
        if (!Files.isDirectory(cacheDir)) return null;
        Path file = resolveFile(configId, text);
        if (Files.isRegularFile(file)) {
            try {
                byte[] data = Files.readAllBytes(file);
                log.debug("TTS cache hit: configId={}, textLength={}, fileSize={}", configId, text.length(), data.length);
                return data;
            } catch (IOException e) {
                log.warn("TTS cache read failed: {}", file, e);
                return null;
            }
        }
        return null;
    }

    /**
     * 存储音频数据到缓存
     *
     * @param configId  TTS 配置ID
     * @param text      合成文本
     * @param audioData 音频字节数组
     */
    public void put(Long configId, String text, byte[] audioData) {
        if (!Files.isDirectory(cacheDir)) return;
        Path file = resolveFile(configId, text);
        try {
            Files.write(file, audioData);
            log.debug("TTS cache saved: configId={}, textLength={}, fileSize={}", configId, text.length(), audioData.length);
        } catch (IOException e) {
            log.warn("TTS cache write failed: {}", file, e);
        }
    }

    /**
     * 根据配置ID和文本生成缓存文件路径
     * 文件名格式：SHA-256("{configId}:{text}").wav
     */
    private Path resolveFile(Long configId, String text) {
        String hash = sha256(configId + ":" + text);
        return cacheDir.resolve(hash + ".wav");
    }

    /**
     * 计算字符串的 SHA-256 哈希值
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
