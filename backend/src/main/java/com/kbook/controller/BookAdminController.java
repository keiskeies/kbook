package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookParserService;
import com.kbook.service.BookScanService;
import com.kbook.service.BookService;
import com.kbook.service.EmbeddingService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            case "EPUB" -> storageProps.getBookPaths().getEpub();
            case "PDF" -> storageProps.getBookPaths().getPdf();
            case "TXT" -> storageProps.getBookPaths().getTxt();
            default -> storageProps.getBookPaths().getEpub();
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

            String fileUrl = targetPath.toAbsolutePath().toString();
            Optional<Book> existing = bookRepository.findByFileUrl(fileUrl);

            if (existing.isPresent()) {
                // 文件已存在 → 按更新流程处理
                Book book = existing.get();
                log.info("上传文件已存在，按更新处理: bookId={}, title={}", book.getId(), title);
                book.setFileSize(Files.size(targetPath));
                bookParserService.parseAndFill(book, targetPath);
                bookParserService.finalizeCover(book);
                bookService.updateBook(book.getId(), book);

                // AI数据生成 和 内容向量生成 并行执行
                CompletableFuture<Void> aiDataFuture = CompletableFuture.runAsync(() -> bookParserService.generateAllAiData(book.getId(), true));
                CompletableFuture<Void> contentEmbedFuture = CompletableFuture.runAsync(() -> bookParserService.generateContentEmbedding(book.getId()));
                try {
                    CompletableFuture.allOf(aiDataFuture, contentEmbedFuture).join();
                } catch (Exception e) {
                    log.warn("并行任务异常: bookId={} - {}", book.getId(), e.getMessage());
                }
                book.setContentEmbedded(true);
                bookService.updateBook(book.getId(), book);
                return Result.ok(book);
            }

            // 新文件 → 按新增流程处理
            Book newBook = Book.builder()
                    .title(title)
                    .format(extension)
                    .fileUrl(fileUrl)
                    .fileSize(Files.size(targetPath))
                    .build();

            bookParserService.parseAndFill(newBook, targetPath);
            Book saved = bookService.createBook(newBook);
            bookParserService.finalizeCover(saved);
            bookService.updateBook(saved.getId(), saved);

            // AI数据生成 和 内容向量生成 并行执行
            CompletableFuture<Void> aiDataFuture = CompletableFuture.runAsync(() -> bookParserService.generateAllAiData(saved.getId(), true));
            CompletableFuture<Void> contentEmbedFuture = CompletableFuture.runAsync(() -> bookParserService.generateContentEmbedding(saved.getId()));
            try {
                CompletableFuture.allOf(aiDataFuture, contentEmbedFuture).join();
            } catch (Exception e) {
                log.warn("并行任务异常: bookId={} - {}", saved.getId(), e.getMessage());
            }
            saved.setContentEmbedded(true);
            bookService.updateBook(saved.getId(), saved);

            log.info("上传图书成功: {} [{}]", title, extension);
            return Result.ok(saved);

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
        bookParserService.generateAllAiData(saved.getId(), true);
        bookParserService.generateContentEmbedding(saved.getId());
        saved.setContentEmbedded(true);
        bookService.updateBook(saved.getId(), saved);
        return Result.ok(saved);
    }

    // ==================== 内容向量管理 ====================

    /**
     * 获取内容向量统计信息
     */
    @GetMapping("/embeddings/stats")
    public Result<Map<String, Object>> embeddingStats() {
        long totalBooks = bookRepository.count();
        long embeddedBooks = bookRepository.findAll().stream()
                .filter(b -> Boolean.TRUE.equals(b.getContentEmbedded()))
                .count();
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
     * 重新评分所有书籍（完整覆盖：元数据/AI数据/向量数据）
     * 与管理员图书刷新逻辑一致，不做评分判断，直接覆盖写入
     */
    @PostMapping("/rerate")
    public Result<Map<String, Object>> rerateAllBooks(
            @RequestParam(value = "bookId", required = false) Long bookId) {
        if (bookId != null) {
            log.info("开始重建指定书籍: bookId={}", bookId);
            try {
                Book book = bookService.getBookById(bookId);
                if (book.getFileUrl() == null) {
                    return Result.fail("图书文件路径为空");
                }
                Path filePath = Paths.get(book.getFileUrl());
                if (!Files.exists(filePath)) {
                    return Result.fail("图书文件不存在");
                }
                book.setFileSize(Files.size(filePath));
                bookParserService.parseAndFill(book, filePath);
                bookParserService.finalizeCover(book);
                bookService.updateBook(book.getId(), book);

                if (book.getRagContent() != null && !book.getRagContent().isBlank()) {
                    CompletableFuture<Void> aiDataFuture = CompletableFuture.runAsync(() -> bookParserService.generateAllAiData(book.getId(), true));
                    CompletableFuture<Void> contentEmbedFuture = CompletableFuture.runAsync(() -> bookParserService.generateContentEmbedding(book.getId(), book.getRagContent()));
                    try {
                        CompletableFuture.allOf(aiDataFuture, contentEmbedFuture).join();
                    } catch (Exception e) {
                        log.warn("并行任务异常: bookId={} - {}", book.getId(), e.getMessage());
                    }
                } else {
                    bookParserService.generateAllAiData(book.getId(), true);
                    bookParserService.generateContentEmbedding(book.getId(), book.getRagContent());
                }
                book.setContentEmbedded(true);
                book.setDescription(null);
                book.setFormatTags(null);
                book.setRating(null);
                book.setRelevanceScores(null);
                bookService.updateBook(book.getId(), book);
                return Result.ok(Map.of("reratedCount", 1, "status", "completed"));
            } catch (Exception e) {
                log.error("重建失败: bookId={} - {}", bookId, e.getMessage());
                return Result.fail("重建失败: " + e.getMessage());
            }
        }

        log.info("开始重新评分所有书籍");
        List<Book> allBooks = bookRepository.findAll();
        int rerated = 0;

        for (Book book : allBooks) {
            try {
                if (book.getFileUrl() == null || !Files.exists(Paths.get(book.getFileUrl()))) {
                    continue;
                }
                Path filePath = Paths.get(book.getFileUrl());
                book.setFileSize(Files.size(filePath));
                bookParserService.parseAndFill(book, filePath);
                bookParserService.finalizeCover(book);
                bookService.updateBook(book.getId(), book);

                if (book.getRagContent() != null && !book.getRagContent().isBlank()) {
                    CompletableFuture<Void> aiDataFuture = CompletableFuture.runAsync(() -> bookParserService.generateAllAiData(book.getId(), true));
                    CompletableFuture<Void> contentEmbedFuture = CompletableFuture.runAsync(() -> bookParserService.generateContentEmbedding(book.getId(), book.getRagContent()));
                    try {
                        CompletableFuture.allOf(aiDataFuture, contentEmbedFuture).join();
                    } catch (Exception e) {
                        log.warn("并行任务异常: bookId={} - {}", book.getId(), e.getMessage());
                    }
                } else {
                    bookParserService.generateAllAiData(book.getId(), true);
                    bookParserService.generateContentEmbedding(book.getId(), book.getRagContent());
                }
                book.setContentEmbedded(true);
                book.setDescription(null);
                book.setFormatTags(null);
                book.setRating(null);
                book.setRelevanceScores(null);
                bookService.updateBook(book.getId(), book);
                rerated++;
                if (rerated % 10 == 0) {
                    log.info("重建进度: {}/{}", rerated, allBooks.size());
                }
                Thread.sleep(200);
            } catch (Exception e) {
                log.warn("重建失败: bookId={} - {}", book.getId(), e.getMessage());
            }
        }
        log.info("重建完成: rerated={}", rerated);
        return Result.ok(Map.of("reratedCount", rerated, "status", "completed"));
    }
}
