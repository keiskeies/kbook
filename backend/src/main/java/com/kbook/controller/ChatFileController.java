package com.kbook.controller;

import com.kbook.common.util.CommonUtils;
import com.kbook.config.properties.BookStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 聊天文件服务控制器
 * 处理缩略图自动生成和文件访问
 * URL 格式: /api/uploads/chat/{conversationId}/{filename}
 * <p>
 * 图片缩略图: lazy 生成，首次请求时由 CommonUtils.generateThumbnail 创建
 * 视频缩略图: 上传时由 VideoService.extractThumbnail 预生成，此处仅 serve
 */
@Slf4j
@RestController
@RequestMapping("/api/uploads/chat")
@RequiredArgsConstructor
public class ChatFileController {

    private final BookStorageProperties storageProps;

    @GetMapping("/{conversationId}/{filename:.+}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable Long conversationId,
            @PathVariable String filename) throws IOException {

        Path chatDir = Paths.get(storageProps.getUpload().getChatDir());
        Path convDir = chatDir.resolve(conversationId.toString());
        Path filePath = CommonUtils.safeResolvePath(convDir, filename);
        if (filePath == null) {
            return ResponseEntity.notFound().build();
        }

        boolean isImageThumb = isImageThumbRequest(filename);
        boolean isVideoThumb = isVideoThumbRequest(filename);

        // 文件已存在 → 直接返回
        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            if (isImageThumb || isVideoThumb) {
                return buildJpegResponse(filePath);
            }
            return CommonUtils.buildImageResponse(filePath, filename);
        }

        // 图片缩略图不存在 → lazy 生成（仅对 _thumbnail 后缀）
        if (isImageThumb) {
            String originalFilename = getOriginalFilename(filename, "_thumbnail");
            Path originalPath = CommonUtils.safeResolvePath(convDir, originalFilename);
            if (originalPath == null || !Files.exists(originalPath) || !Files.isRegularFile(originalPath)) {
                return ResponseEntity.notFound().build();
            }

            log.info("生成图片缩略图: {}/{} -> {}", conversationId, originalFilename, filename);
            CommonUtils.generateThumbnail(originalPath, filePath, 100, 100);
            return buildJpegResponse(filePath);
        }

        // 视频缩略图不存在 → 404（应在上传时预生成）
        if (isVideoThumb) {
            return ResponseEntity.notFound().build();
        }

        // 非缩略图且文件不存在 → 404
        return ResponseEntity.notFound().build();
    }

    /**
     * 构建 JPEG 格式的响应（用于缩略图）
     */
    private ResponseEntity<Resource> buildJpegResponse(Path filePath) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .body(new FileSystemResource(filePath));
    }

    /**
     * 判断是否为图片缩略图请求（文件名以 _thumbnail 结尾）
     */
    private boolean isImageThumbRequest(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) return false;
        return filename.substring(0, dotIndex).endsWith("_thumbnail");
    }

    /**
     * 判断是否为视频缩略图请求（文件名以 _thumb 结尾，但不是 _thumbnail）
     */
    private boolean isVideoThumbRequest(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) return false;
        String nameWithoutExt = filename.substring(0, dotIndex);
        return nameWithoutExt.endsWith("_thumb") && !nameWithoutExt.endsWith("_thumbnail");
    }

    /**
     * 从缩略图文件名还原原始文件名
     * 例: abc_thumbnail.png → abc.png
     */
    private String getOriginalFilename(String thumbFilename, String suffix) {
        int dotIndex = thumbFilename.lastIndexOf('.');
        if (dotIndex < 0) return thumbFilename;
        String nameWithoutExt = thumbFilename.substring(0, dotIndex);
        String ext = thumbFilename.substring(dotIndex);
        String originalName = nameWithoutExt.substring(0, nameWithoutExt.length() - suffix.length());
        return originalName + ext;
    }
}
