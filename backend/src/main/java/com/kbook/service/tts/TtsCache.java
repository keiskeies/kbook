package com.kbook.service.tts;

import com.kbook.config.properties.BookStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * TTS 音频缓存服务
 * <p>
 * 基于本地文件系统的音频缓存，使用「配置ID + 音频格式 + 文本」的 SHA-256 哈希作为文件名。
 * 避免相同文本重复调用 TTS API，提升响应速度并降低 API 调用成本。
 * <p>
 * 支持多容器格式：非流式合成产出 {@code .wav}，流式播放产出 {@code .mp3}。
 * 格式参与哈希计算，因此同一段文本的不同格式缓存互不覆盖。
 */
@Slf4j
@Component
public class TtsCache {

    /** 默认音频扩展名（非流式合成产物） */
    public static final String EXT_WAV = "wav";

    /** 流式播放产物扩展名 */
    public static final String EXT_MP3 = "mp3";

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
     * 获取缓存的音频数据（wav）
     *
     * @param configId TTS 配置ID
     * @param text     合成文本
     * @return 缓存的音频字节数组，未命中返回 null
     */
    public byte[] get(Long configId, String text) {
        return get(configId, text, EXT_WAV);
    }

    /**
     * 获取缓存的音频数据
     *
     * @param configId TTS 配置ID
     * @param text     合成文本
     * @param ext      音频扩展名（wav / mp3）
     * @return 缓存的音频字节数组，未命中返回 null
     */
    public byte[] get(Long configId, String text, String ext) {
        Path file = resolveFile(configId, text, ext);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            byte[] data = Files.readAllBytes(file);
            log.debug("TTS cache hit: configId={}, ext={}, textLength={}, fileSize={}",
                    configId, ext, text.length(), data.length);
            return data;
        } catch (IOException e) {
            log.warn("TTS cache read failed: {}", file, e);
            return null;
        }
    }

    /**
     * 存储音频数据到缓存（wav）
     *
     * @param configId  TTS 配置ID
     * @param text      合成文本
     * @param audioData 音频字节数组
     */
    public void put(Long configId, String text, byte[] audioData) {
        put(configId, text, EXT_WAV, audioData);
    }

    /**
     * 存储音频数据到缓存
     *
     * @param configId  TTS 配置ID
     * @param text      合成文本
     * @param ext       音频扩展名（wav / mp3）
     * @param audioData 音频字节数组
     */
    public void put(Long configId, String text, String ext, byte[] audioData) {
        Path file = resolveFile(configId, text, ext);
        if (file == null) return;
        Path tmp = null;
        try {
            // 先写临时文件再原子替换，避免并发/中断留下半截文件被误当作完整缓存
            tmp = Files.createTempFile(cacheDir, "tts-", ".part");
            Files.write(tmp, audioData);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debug("TTS cache saved: configId={}, ext={}, textLength={}, fileSize={}",
                    configId, ext, text.length(), audioData.length);
        } catch (IOException e) {
            log.warn("TTS cache write failed: {}", file, e);
            deleteQuietly(tmp);
        }
    }

    /**
     * 解析缓存文件路径，供需要按文件流式回放的调用方使用
     *
     * @param configId TTS 配置ID
     * @param text     合成文本
     * @param ext      音频扩展名
     * @return 缓存文件路径；缓存目录不可用时返回 null
     */
    public Path findFile(Long configId, String text, String ext) {
        Path file = resolveFile(configId, text, ext);
        return (file != null && Files.isRegularFile(file)) ? file : null;
    }

    /**
     * 在缓存目录内创建一个临时文件，用于「边合成边写盘」
     * <p>
     * 调用方负责在完成后通过 {@link #commit(Path, Long, String, String)} 转正，
     * 或通过 {@link #deleteQuietly(Path)} 丢弃。
     *
     * @return 临时文件路径；缓存目录不可用或创建失败时返回 null
     */
    public Path createTempFile() {
        if (!Files.isDirectory(cacheDir)) return null;
        try {
            return Files.createTempFile(cacheDir, "tts-", ".part");
        } catch (IOException e) {
            log.warn("TTS temp file create failed in {}", cacheDir, e);
            return null;
        }
    }

    /**
     * 把临时文件转正为正式缓存
     *
     * @param tmp      临时文件
     * @param configId TTS 配置ID
     * @param text     合成文本
     * @param ext      音频扩展名
     */
    public void commit(Path tmp, Long configId, String text, String ext) {
        Path file = resolveFile(configId, text, ext);
        if (tmp == null || file == null) return;
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("TTS cache commit failed: {}", file, e);
            deleteQuietly(tmp);
        }
    }

    /**
     * 删除文件，忽略异常
     *
     * @param path 待删除路径，可为 null
     */
    public void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    /**
     * 根据配置ID、文本和格式生成缓存文件路径
     * 文件名格式：SHA-256("{configId}:{ext}:{text}").{ext}
     * 格式参与哈希，保证 wav / mp3 缓存互不覆盖。
     */
    private Path resolveFile(Long configId, String text, String ext) {
        if (!Files.isDirectory(cacheDir)) return null;
        String safeExt = (ext == null || ext.isBlank()) ? EXT_WAV : ext;
        String hash = sha256(configId + ":" + safeExt + ":" + text);
        return cacheDir.resolve(hash + "." + safeExt);
    }

    /**
     * 计算字符串的 SHA-256 哈希值
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}