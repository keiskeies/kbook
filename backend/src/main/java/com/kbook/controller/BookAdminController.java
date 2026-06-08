package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.book.BookScanService;
import com.kbook.service.book.BookSearchService;
import com.kbook.service.book.BookService;
import com.kbook.service.embedding.EmbeddingService;
import com.kbook.config.properties.BookStorageProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 管理员图书管理控制器 — 扫描、上传、封面
 */
@Slf4j
@RestController
@RequestMapping("/api/books/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "图书管理")
public class BookAdminController {

    private final BookScanService bookScanService;
    private final BookService bookService;
    private final BookRepository bookRepository;
    private final EmbeddingService embeddingService;
    private final BookSearchService bookSearchService;
    private final BookStorageProperties storageProps;

    /**
     * 刷新图书 — SSE 流式扫描，实时推送进度
     * @param skipBeforeId 跳过 ID 小于此值的已有图书（断点续扫，默认不跳过）
     */
    @Operation(summary = "扫描图书")
    @GetMapping(value = "/scan", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scanBooks(@RequestParam(value = "skipBeforeId", required = false) Long skipBeforeId) {
        return bookScanService.scanAllWithProgress(skipBeforeId);
    }

    /**
     * 查询扫描状态及进度
     */
    @Operation(summary = "获取扫描状态")
    @GetMapping("/scan/status")
    public Result<Map<String, Object>> scanStatus() {
        return Result.ok(bookScanService.getScanProgress());
    }

    /**
     * 重置扫描状态（异常恢复用）
     */
    @Operation(summary = "重置扫描状态")
    @PostMapping("/scan/reset")
    public Result<Void> resetScanStatus() {
        bookScanService.resetScanState();
        return Result.ok(null);
    }

    /**
     * 上传图书文件 — 管理员手动上传
     * 完整流程与扫描一致：解析 → 入库/更新 → 封面 → AI标签/评分/相关度 → ES索引 → 向量库
     */
    @Operation(summary = "上传图书")
    @PostMapping("/upload")
    public Result<Book> uploadBook(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String customTitle) {

        if (file.isEmpty()) return Result.fail("文件不能为空");

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) return Result.fail("文件名无效");

        String extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toUpperCase();
        if (!List.of("EPUB", "PDF", "TXT").contains(extension)) return Result.fail("仅支持 EPUB/PDF/TXT 格式");

        String targetDir = switch (extension) {
            case "EPUB" -> storageProps.getBookPaths().getEpub();
            case "PDF" -> storageProps.getBookPaths().getPdf();
            default -> storageProps.getBookPaths().getTxt();
        };

        try {
            Path dirPath = Paths.get(targetDir);
            Files.createDirectories(dirPath);
            Path targetPath = dirPath.resolve(originalFilename);
            file.transferTo(targetPath.toFile());

            String title = (customTitle != null && !customTitle.isBlank())
                    ? customTitle
                    : originalFilename.substring(0, originalFilename.lastIndexOf('.'));

            Book book = bookScanService.processBookFile(targetPath, extension, title);
            book.setContentEmbedded(false);
            bookService.updateBook(book.getId(), book);

            log.info("上传图书成功: {} [{}]", title, extension);
            return Result.ok(book);

        } catch (Exception e) {
            log.error("上传图书失败", e);
            return Result.fail("上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取封面图片
     */
//    @Operation(summary = "获取封面图片")
    @GetMapping(value = "/cover/{filename:.+}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<Resource> getCover(@PathVariable String filename) {
        // 注意：此接口映射在 /api/books/admin/cover，但前端使用 /api/books/cover
        // 在 BookController 中添加了转发
        Path coverDir = Paths.get(storageProps.getCoverPath());
        Path imagePath = CommonUtils.safeResolvePath(coverDir, filename);

        if (imagePath == null || !Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }

        return CommonUtils.buildImageResponse(imagePath, filename);
    }

    /**
     * 删除指定作者的所有书籍（全链路：JPA + ES + Qdrant + Redis + 封面）
     */
    @Operation(summary = "按作者删除图书")
    @DeleteMapping("/delete-by-author")
    public Result<Map<String, Object>> deleteBooksByAuthor(@RequestParam String author) {
        int count = bookService.deleteBooksByAuthor(author);
        return Result.ok(Map.of("deletedCount", count, "author", author));
    }

    /**
     * 合并同名书籍（以 EPUB 为主，其他格式的关联数据迁移后删除）
     */
    @Operation(summary = "合并同名图书")
    @PostMapping("/merge-by-title")
    public Result<Map<String, Object>> mergeBooksByTitle(@RequestParam String title) {
        String result = bookService.mergeBooksByTitle(title);
        return Result.ok(Map.of("message", result, "title", title));
    }

    /**
     * 更新图书封面（管理员）
     * 上传新封面图片，自动压缩至最大宽度 300px
     */
    @Operation(summary = "更新图书封面")
    @PostMapping("/{id}/cover")
    public Result<Book> updateBookCover(
            @PathVariable Long id,
            @RequestParam("cover") MultipartFile coverFile) {
        if (coverFile.isEmpty()) {
            return Result.fail("封面文件不能为空");
        }

        String contentType = coverFile.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return Result.fail("仅支持图片文件");
        }

        try {
            Book updated = bookService.updateBookCover(id, coverFile);
            return Result.ok(updated);
        } catch (Exception e) {
            log.error("更新封面失败: bookId={}", id, e);
            return Result.fail("更新封面失败: " + e.getMessage());
        }
    }

    /**
     * 更新图书书名（管理员）
     */
    @Operation(summary = "更新图书书名")
    @PutMapping("/{id}/title")
    public Result<Book> updateBookTitle(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return Result.fail("书名不能为空");
        }
        Book book = bookService.getBookById(id);
        book.setTitle(title.trim());
        bookService.updateBook(id, book);
        return Result.ok(bookService.getBookById(id));
    }

    /**
     * 更新图书作者（管理员）
     */
    @Operation(summary = "更新图书作者")
    @PutMapping("/{id}/author")
    public Result<Book> updateBookAuthor(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String author = body.get("author");
        Book book = bookService.getBookById(id);
        book.setAuthor(author != null ? author.trim() : null);
        bookService.updateBook(id, book);
        return Result.ok(bookService.getBookById(id));
    }

    /**
     * 更新图书简介（管理员）
     */
    @Operation(summary = "更新图书简介")
    @PutMapping("/{id}/description")
    public Result<Book> updateBookDescription(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String description = body.get("description");
        bookService.updateDescription(id, description != null ? description.trim() : null);
        return Result.ok(bookService.getBookById(id));
    }


    // ==================== 内容向量管理 ====================

    /**
     * 获取内容向量统计信息
     */
    @Operation(summary = "获取向量统计")
    @GetMapping("/embeddings/stats")
    public Result<Map<String, Object>> embeddingStats() {
        long totalBooks = bookRepository.count();
        long embeddedBooks = bookRepository.countByContentEmbeddedTrue();
        long notEmbeddedBooks = totalBooks - embeddedBooks;
        long totalContentVectors = embeddingService.getTotalContentEmbeddingCount();

        return Result.ok(Map.of(
                "totalBooks", totalBooks,
                "embeddedBooks", embeddedBooks,
                "notEmbeddedBooks", notEmbeddedBooks,
                "totalContentVectors", totalContentVectors
        ));
    }

    /**
     * 清空内容向量库（kbook_content 集合）
     */
    @Operation(summary = "清空内容向量库")
    @PostMapping("/vector/clear-content")
    public Result<Map<String, Object>> clearContentVectors() {
        long deletedCount = embeddingService.clearAllContentEmbeddings();
        return Result.ok(Map.of(
                "deletedCount", deletedCount,
                "message", "内容向量库已清空"
        ));
    }

    // ==================== ES 索引管理 ====================

    /**
     * 全量重建 ES 索引 — SSE 流式推送进度
     */
    @Operation(summary = "重建ES索引")
    @GetMapping(value = "/es/reindex", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter rebuildEsIndex() {
        SseEmitter emitter = new SseEmitter(600_000L); // 10分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                bookSearchService.rebuildIndexWithProgress((processed, total) -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("progress")
                                .data(Map.of(
                                        "current", processed,
                                        "total", total,
                                        "status", "scanning"
                                )));
                    } catch (IOException e) {
                        log.warn("SSE 发送失败: {}", e.getMessage());
                    }
                });
                long elapsed = System.currentTimeMillis() - startTime;
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(Map.of("elapsed", elapsed)));
                emitter.complete();
            } catch (Exception e) {
                log.error("ES 重建索引失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", e.getMessage() != null ? e.getMessage() : "ES 重建失败")));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

}
