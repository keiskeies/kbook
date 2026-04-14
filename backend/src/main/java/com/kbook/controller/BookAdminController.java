package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.entity.Book;
import com.kbook.service.BookParserService;
import com.kbook.service.BookScanService;
import com.kbook.service.BookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 管理员图书管理控制器 — 扫描、上传、封面
 */
@Slf4j
@RestController
@RequestMapping("/api/books/admin")
@RequiredArgsConstructor
public class BookAdminController {

    private final BookScanService bookScanService;
    private final BookService bookService;
    private final BookParserService bookParserService;

    @Value("${kbook.book-paths.epub}")
    private String epubPath;

    @Value("${kbook.book-paths.pdf}")
    private String pdfPath;

    @Value("${kbook.book-paths.txt}")
    private String txtPath;

    @Value("${kbook.cover-path:./covers}")
    private String coverPath;

    /**
     * 刷新图书 — SSE 流式扫描，实时推送进度
     */
    @GetMapping(value = "/scan", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scanBooks() {
        return bookScanService.scanAllWithProgress();
    }

    /**
     * 查询扫描状态及进度
     */
    @GetMapping("/scan/status")
    public Result<Map<String, Object>> scanStatus() {
        return Result.ok(bookScanService.getScanProgress());
    }

    /**
     * 重置扫描状态（异常恢复用）
     */
    @PostMapping("/scan/reset")
    public Result<Void> resetScanStatus() {
        bookScanService.resetScanState();
        return Result.ok(null);
    }

    /**
     * 上传图书文件 — 管理员手动上传
     */
    @PostMapping("/upload")
    public Result<Book> uploadBook(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String customTitle) {

        if (file.isEmpty()) {
            return Result.fail("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            return Result.fail("文件名无效");
        }

        String extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toUpperCase();
        if (!extension.equals("EPUB") && !extension.equals("PDF") && !extension.equals("TXT")) {
            return Result.fail("仅支持 EPUB/PDF/TXT 格式");
        }

        // 确定存储目录
        String targetDir = switch (extension) {
            case "EPUB" -> epubPath;
            case "PDF" -> pdfPath;
            case "TXT" -> txtPath;
            default -> epubPath;
        };

        try {
            // 保存文件到对应目录
            Path dirPath = Paths.get(targetDir);
            Files.createDirectories(dirPath);
            Path targetPath = dirPath.resolve(originalFilename);
            file.transferTo(targetPath.toFile());

            // 文件名（去掉扩展名）作为书名
            String title = customTitle != null && !customTitle.isBlank()
                    ? customTitle
                    : originalFilename.substring(0, originalFilename.lastIndexOf('.'));

            // 构建图书对象
            Book newBook = Book.builder()
                    .title(title)
                    .format(extension)
                    .fileUrl(targetPath.toAbsolutePath().toString())
                    .fileSize(file.getSize())
                    .build();

            // 解析元数据
            bookParserService.parseAndFill(newBook, targetPath);

            // 入库
            Book saved = bookService.createBook(newBook);

            // 修复封面URL
            if (saved.getCoverUrl() != null) {
                String oldCoverUrl = saved.getCoverUrl();
                bookParserService.finalizeCover(saved);
                if (!saved.getCoverUrl().equals(oldCoverUrl)) {
                    bookService.updateBook(saved.getId(), saved);
                }
            }

            // 生成 AI 数据（标签 + 评分 + 相关度，合并一次调用）
            bookParserService.generateAllAiData(saved.getId(), true);
            bookParserService.generateContentEmbedding(saved.getId());

            log.info("上传图书成功: {} [{}]", title, extension);
            return Result.ok(saved);

        } catch (IOException e) {
            log.error("上传图书失败", e);
            return Result.fail("上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取封面图片
     */
    @GetMapping(value = "/cover/{filename}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<Resource> getCover(@PathVariable String filename) {
        // 注意：此接口映射在 /api/books/admin/cover，但前端使用 /api/books/cover
        // 在 BookController 中添加了转发
        Path coverDir = Paths.get(coverPath);
        Path imagePath = CommonUtils.safeResolvePath(coverDir, filename);

        if (imagePath == null || !Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }

        return CommonUtils.buildImageResponse(imagePath, filename);
    }

    /**
     * 重新解析图书元数据
     */
    @PostMapping("/{id}/reparse")
    public Result<Book> reparseBook(@PathVariable Long id) {
        Book book = bookService.getBookById(id);
        if (book.getFileUrl() == null) {
            return Result.fail("图书文件路径为空");
        }

        Path filePath = Paths.get(book.getFileUrl());
        if (!Files.exists(filePath)) {
            return Result.fail("图书文件不存在");
        }

        // 清除旧数据
        book.setAuthor(null);
        book.setDescription(null);
        book.setCoverUrl(null);

        bookParserService.parseAndFill(book, filePath);
        bookParserService.finalizeCover(book);

        Book saved = bookService.updateBook(id, book);
        // 重新生成 AI 数据（标签 + 评分 + 相关度，合并一次调用）
        bookParserService.generateAllAiData(saved.getId(), true);
        bookParserService.generateContentEmbedding(saved.getId());
        return Result.ok(saved);
    }
}
