package com.kbook.service.tts;

import com.kbook.config.properties.BookStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Component
public class TtsCache {

    private final Path cacheDir;

    public TtsCache(BookStorageProperties properties) {
        this.cacheDir = Path.of(properties.getTtsCacheDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(cacheDir);
            log.info("TTS cache directory: {}", cacheDir);
        } catch (IOException e) {
            log.warn("Failed to create TTS cache directory: {}", cacheDir, e);
        }
    }

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

    private Path resolveFile(Long configId, String text) {
        String hash = sha256(configId + ":" + text);
        return cacheDir.resolve(hash + ".wav");
    }

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
