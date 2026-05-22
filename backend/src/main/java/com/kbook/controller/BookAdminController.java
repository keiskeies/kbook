package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookParserService;
import com.kbook.service.BookScanService;
import com.kbook.service.BookSearchService;
import com.kbook.service.BookService;
import com.kbook.service.EmbeddingService;
import com.kbook.service.RagHitStatisticsService;
import com.kbook.config.properties.BookStorageProperties;
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
public class BookAdminController {

    private final BookScanService bookScanService;
    private final BookService bookService;
    private final BookParserService bookParserService;
    private final BookRepository bookRepository;
    private final EmbeddingService embeddingService;
    private final BookSearchService bookSearchService;
    private final RagHitStatisticsService ragHitStatisticsService;
    private final BookStorageProperties storageProps;

    /**
     * 刷新图书 — SSE 流式扫描，实时推送进度
     * @param skipBeforeId 跳过 ID 小于此值的已有图书（断点续扫，默认不跳过）
     */
    @GetMapping(value = "/scan", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scanBooks(@RequestParam(value = "skipBeforeId", required = false) Long skipBeforeId) {
        return bookScanService.scanAllWithProgress(skipBeforeId);
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
     * 完整流程与扫描一致：解析 → 入库/更新 → 封面 → AI标签/评分/相关度 → ES索引 → 向量库
     */
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
    @GetMapping(value = "/cover/{filename}", produces = MediaType.IMAGE_JPEG_VALUE)
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
    @DeleteMapping("/delete-by-author")
    public Result<Map<String, Object>> deleteBooksByAuthor(@RequestParam String author) {
        int count = bookService.deleteBooksByAuthor(author);
        return Result.ok(Map.of("deletedCount", count, "author", author));
    }

    /**
     * 合并同名书籍（以 EPUB 为主，其他格式的关联数据迁移后删除）
     */
    @PostMapping("/merge-by-title")
    public Result<Map<String, Object>> mergeBooksByTitle(@RequestParam String title) {
        String result = bookService.mergeBooksByTitle(title);
        return Result.ok(Map.of("message", result, "title", title));
    }

    /**
     * 更新图书封面（管理员）
     * 上传新封面图片，自动压缩至最大宽度 300px
     */
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
    @PutMapping("/{id}/description")
    public Result<Book> updateBookDescription(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String description = body.get("description");
        bookService.updateDescription(id, description != null ? description.trim() : null);
        return Result.ok(bookService.getBookById(id));
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
        bookParserService.generateAllAiData(book);
        bookParserService.generateContentEmbedding(book.getId());
        book.setContentEmbedded(true);
        bookService.updateBook(book.getId(), book);
        CompletableFuture.runAsync(() -> embeddingService.generateBookEmbedding(book.getId()));
        return Result.ok(book);
    }

    // ==================== 内容向量管理 ====================

    /**
     * 获取内容向量统计信息
     */
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
     * 重建所有书籍的基础信息向量（kbook_books 集合）— SSE 流式推送进度
     */
    @GetMapping(value = "/vector/rebuild-book", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter rebuildAllBookEmbeddings() {
        SseEmitter emitter = new SseEmitter(600_000L);

        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                embeddingService.rebuildAllBookEmbeddingsWithProgress((processed, total) -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("progress")
                                .data(Map.of(
                                        "current", processed,
                                        "total", total,
                                        "status", "processing"
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
                log.error("重建基础信息向量失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", e.getMessage() != null ? e.getMessage() : "重建失败")));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 清空内容向量库（kbook_content 集合）
     */
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

    // ==================== RAG 向量命中统计 ====================

    /**
     * 获取单本书的向量命中统计
     */
    @GetMapping("/rag-stats/{bookId}")
    public Result<Map<String, Object>> getRagStats(@PathVariable Long bookId) {
        return Result.ok(ragHitStatisticsService.getStatistics(bookId));
    }

    /**
     * 获取未命中率最高的书籍列表（topN）
     */
    @GetMapping("/rag-stats/low-hit")
    public Result<List<Map<String, Object>>> getLowHitBooks(
            @RequestParam(value = "topN", defaultValue = "20") int topN) {
        return Result.ok(ragHitStatisticsService.getLowHitBooks(topN));
    }

    /**
     * 手动清除某本书的命中统计
     */
    @PostMapping("/rag-stats/{bookId}/clear")
    public Result<Void> clearRagStats(@PathVariable Long bookId) {
        ragHitStatisticsService.clearStatistics(bookId);
        return Result.ok(null);
    }

    /**
     * 手动触发单本图书全文重新向量化
     * 完成后清除命中统计
     */
    @PostMapping("/rag-stats/{bookId}/re-embed")
    public Result<Map<String, Object>> reEmbedBook(@PathVariable Long bookId) {
        int chunkCount = bookParserService.generateContentEmbeddingWithCount(bookId);
        ragHitStatisticsService.clearStatistics(bookId);
        return Result.ok(Map.of(
                "bookId", bookId,
                "chunks", chunkCount,
                "status", chunkCount > 0 ? "completed" : "failed"
        ));
    }
}
