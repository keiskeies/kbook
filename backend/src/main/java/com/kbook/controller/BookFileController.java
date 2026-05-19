package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.exception.BusinessException;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.dto.TextInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图书文件服务控制器 - 提供图书文件的流式读取
 */
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookFileController {

    private final BookRepository bookRepository;

    /**
     * 流式获取图书文件（支持 Range 请求）
     * 用于 PDF/EPUB 等大文件的分块加载
     */
    @GetMapping(value = "/{id}/file", produces = {"application/pdf", "application/epub+zip", "text/plain", "application/octet-stream"})
    public ResponseEntity<Resource> getBookFile(
            @PathVariable Long id,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {

        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new BusinessException("图书不存在"));

        if (book.getFileUrl() == null || book.getFileUrl().isEmpty()) {
            throw new BusinessException("图书文件不存在");
        }

        Path filePath = Paths.get(book.getFileUrl());
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
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
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
                .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                .body(resource);
    }

    /**
     * 获取 TXT 文件元数据（分块信息）
     */
    @GetMapping(value = "/{id}/text-info", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<TextInfoResponse> getBookTextInfo(@PathVariable Long id) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new BusinessException("图书不存在"));

        if (!"TXT".equals(book.getFormat())) {
            throw new BusinessException("仅支持 TXT 格式");
        }

        Path filePath = Paths.get(book.getFileUrl());
        File file = filePath.toFile();

        if (!file.exists()) {
            throw new BusinessException("图书文件未找到");
        }

        TextInfoResponse resp = new TextInfoResponse();
        resp.setFileSize(file.length());
        resp.setFileUrl("/api/books/" + id + "/file");
        return Result.ok(resp);
    }

    private String getContentType(String format) {
        return switch (format) {
            case "PDF" -> "application/pdf";
            case "EPUB" -> "application/epub+zip";
            case "TXT" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

}
