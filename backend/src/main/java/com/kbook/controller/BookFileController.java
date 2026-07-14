package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.exception.BusinessException;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.dto.book.BookProjection;
import com.kbook.service.book.BookService;
import com.kbook.service.book.BookshelfService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 图书文件服务控制器 - 提供图书文件的流式读取
 */
@Slf4j
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
@Tag(name = "图书文件")
public class BookFileController {

    private final BookService bookService;
    private final BookStorageProperties storageProps;
    private final BookshelfService bookshelfService;

    /**
     * 流式获取图书文件（支持 Range 请求）
     * 用于 PDF/EPUB 等大文件的分块加载
     * <p>
     * 权限校验（P3 #3 IDOR 修复）：
     * - 管理员可下载任意书籍
     * - 普通用户只能下载已加入书架的书籍
     */
    @GetMapping(value = "/{id}/file", produces = {"application/pdf", "application/epub+zip", "text/plain", "application/octet-stream"})
    public ResponseEntity<Resource> getBookFile(
            @PathVariable Long id,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            Authentication authentication) {

        // 权限校验：管理员放行，普通用户必须先将书加入书架
        Long userId = (Long) authentication.getPrincipal();
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
        if (!isAdmin && !bookshelfService.isInBookshelf(userId, id)) {
            log.warn("书籍下载越权拦截: userId={}, bookId={}", userId, id);
            throw new BusinessException(403, "请先将书籍加入书架再下载");
        }

        BookProjection book = bookService.getBookProjectionById(id);

        if (book.getFileUrl() == null || book.getFileUrl().isEmpty()) {
            throw new BusinessException("图书文件不存在");
        }

        Path filePath = storageProps.resolveBookPath(book.getFileUrl(), book.getFormat());
        File file = filePath.toFile();

        if (!file.exists()) {
            throw new BusinessException("图书文件未找到");
        }

        FileSystemResource resource = new FileSystemResource(file);
        long fileSize = file.length();

        // 处理 Range 请求（支持断点续传）
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] ranges = rangeHeader.substring(6).split("-");
            long start = Long.parseLong(ranges[0]);
            long end = ranges.length > 1 && !ranges[1].isEmpty()
                    ? Long.parseLong(ranges[1])
                    : fileSize - 1;

            long contentLength = end - start + 1;

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_TYPE, getContentType(book.getFormat()))
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + fileSize)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                    .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                    .body(resource);
        }

        // 完整文件返回
        String filename = URLEncoder.encode(
                book.getTitle() + "." + book.getFormat().toLowerCase(),
                StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, getContentType(book.getFormat()))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileSize))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + filename)
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                .body(resource);
    }

    /**
     * 根据图书格式返回对应的 Content-Type
     * @param format 图书格式（PDF/EPUB/TXT）
     * @return MIME 类型字符串
     */
    private String getContentType(String format) {
        return switch (format) {
            case "PDF" -> "application/pdf";
            case "EPUB" -> "application/epub+zip";
            case "TXT" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

}
