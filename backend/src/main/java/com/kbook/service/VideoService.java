package com.kbook.service;

import com.kbook.config.properties.BookStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 视频处理服务 — FFmpeg 封装
 * 提供缩略图提取和视频转码功能，均可通过配置开关控制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    private final BookStorageProperties storageProps;

    /**
     * 从视频中提取一帧作为缩略图
     *
     * @param videoPath     视频文件路径
     * @param thumbnailPath 缩略图输出路径 (.jpg)
     * @return true 表示成功
     */
    public boolean extractThumbnail(Path videoPath, Path thumbnailPath) {
        BookStorageProperties.VideoConfig.ThumbnailConfig cfg = storageProps.getVideo().getThumbnail();
        if (!cfg.isEnabled()) return false;

        List<String> cmd = new ArrayList<>();
        cmd.add(storageProps.getVideo().getFfmpegPath());
        cmd.add("-i");
        cmd.add(videoPath.toString());
        cmd.add("-vframes");
        cmd.add("1");
        cmd.add("-vf");
        cmd.add("scale=" + cfg.getWidth() + ":-2");
        cmd.add("-q:v");
        cmd.add(String.valueOf(cfg.getQuality()));
        cmd.add("-y");  // 覆盖已存在文件
        cmd.add(thumbnailPath.toString());

        return runFfmpeg(cmd, "视频缩略图");
    }

    /**
     * 转码视频为 H.264 + AAC，限制分辨率
     *
     * @param inputPath  原始视频路径
     * @param outputPath 转码输出路径
     * @return true 表示成功
     */
    public boolean transcode(Path inputPath, Path outputPath) {
        BookStorageProperties.VideoConfig.TranscodeConfig cfg = storageProps.getVideo().getTranscode();
        if (!cfg.isEnabled()) return false;

        List<String> cmd = new ArrayList<>();
        cmd.add(storageProps.getVideo().getFfmpegPath());
        cmd.add("-i");
        cmd.add(inputPath.toString());
        cmd.add("-vf");
        cmd.add("scale=" + cfg.getMaxWidth() + ":-2");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-crf");
        cmd.add(String.valueOf(cfg.getCrf()));
        cmd.add("-c:a");
        cmd.add(cfg.getAudioCodec());
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add("-y");
        cmd.add(outputPath.toString());

        return runFfmpeg(cmd, "视频转码");
    }

    /**
     * 执行 FFmpeg 命令，等待完成
     */
    private boolean runFfmpeg(List<String> cmd, String operation) {
        try {
            log.info("{}: {}", operation, String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出（避免进程阻塞）
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("{} 超时，已强制终止", operation);
                return false;
            }

            if (process.exitValue() != 0) {
                log.error("{} 失败 (exit={}): {}", operation, process.exitValue(), output.toString().trim());
                return false;
            }

            log.info("{} 成功", operation);
            return true;
        } catch (IOException e) {
            log.error("{} 失败: FFmpeg 未找到或无法执行 ({})", operation, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} 被中断", operation);
            return false;
        }
    }
}
