package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.BookParserService;
import com.kbook.service.BookScanService;
import com.kbook.service.BookService;
import com.kbook.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final BookRepository bookRepository;
    private final EmbeddingService embeddingService;

    @Value("${kbook.book-paths.epub}")
    private String epubPath;

    @Value("${kbook.book-paths.pdf}")
    private String pdfPath;

    @Value("${kbook.book-paths.txt}")
    private String txtPath;

    @Value("${kbook.cover-path:./covers}")
    private String coverPath;

    @Value("${kbook.scan.content-embed-min-rating:3.5}")
    private double contentEmbedMinRating;

    @Value("${kbook.scan.content-embed-max-size-mb:10}")
    private double contentEmbedMaxSizeMb;

    /**
     * 判断书籍是否应该存储内容向量（评分达标 + 文件大小未超限）
     */
    private boolean shouldEmbedContent(Book book) {
        if (book.getRating() == null || book.getRating() < contentEmbedMinRating) {
            return false;
        }
        if (book.getFileSize() != null && book.getFileSize() > contentEmbedMaxSizeMb * 1024 * 1024) {
            return false;
        }
        return true;
    }

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

            String fileUrl = targetPath.toAbsolutePath().toString();
            Optional<Book> existing = bookRepository.findByFileUrl(fileUrl);

            if (existing.isPresent()) {
                // 文件已存在 → 按更新流程处理（与扫描的 handleExistingBook 一致）
                Book book = existing.get();
                long fileSize = Files.size(targetPath);
                book.setFileSize(fileSize);

                log.info("上传文件已存在，按更新处理: bookId={}, title={}", book.getId(), title);
                bookParserService.parseAndFill(book, targetPath);
                bookParserService.finalizeCover(book);
                bookService.updateBook(book.getId(), book);
                bookParserService.generateAllAiData(book.getId(), true);
                // 根据评分和文件大小决定是否存储内容向量
                Book updatedBook = bookService.getBookById(book.getId());
                if (shouldEmbedContent(updatedBook)) {
                    bookParserService.generateContentEmbedding(book.getId());
                    updatedBook.setContentEmbedded(true);
                    bookService.updateBook(updatedBook.getId(), updatedBook);
                }
                return Result.ok(updatedBook);
            }

            // 新文件 → 按新增流程处理（与扫描的 handleNewBook 一致）
            Book newBook = Book.builder()
                    .title(title)
                    .format(extension)
                    .fileUrl(fileUrl)
                    .fileSize(Files.size(targetPath))
                    .build();

            // 解析元数据
            bookParserService.parseAndFill(newBook, targetPath);

            // 入库（JPA + ES 双写）
            Book saved = bookService.createBook(newBook);

            // 修复封面URL
            if (saved.getCoverUrl() != null) {
                String oldCoverUrl = saved.getCoverUrl();
                bookParserService.finalizeCover(saved);
                if (!saved.getCoverUrl().equals(oldCoverUrl)) {
                    bookService.updateBook(saved.getId(), saved);
                }
            }

            // 生成 AI 数据（标签 + 评分 + 相关度 + 元数据向量，合并一次调用）
            bookParserService.generateAllAiData(saved.getId(), true);
            // 根据评分和文件大小决定是否存储内容向量
            Book bookWithRating = bookService.getBookById(saved.getId());
            if (shouldEmbedContent(bookWithRating)) {
                bookParserService.generateContentEmbedding(saved.getId());
                bookWithRating.setContentEmbedded(true);
                bookService.updateBook(bookWithRating.getId(), bookWithRating);
            }

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
        Path coverDir = Paths.get(coverPath);
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
        // 重新生成 AI 数据（标签 + 评分 + 相关度，合并一次调用）
        bookParserService.generateAllAiData(saved.getId(), true);
        // 根据评分和文件大小决定是否存储内容向量
        if (shouldEmbedContent(saved)) {
            bookParserService.generateContentEmbedding(saved.getId());
            saved.setContentEmbedded(true);
            bookService.updateBook(saved.getId(), saved);
        }
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

        // 按评分区间统计
        List<Book> allBooks = bookRepository.findAll();
        long highRatedNotEmbedded = allBooks.stream()
                        .filter(b -> b.getRating() != null && b.getRating() >= contentEmbedMinRating && !Boolean.TRUE.equals(b.getContentEmbedded()))
                        .count();
                long lowRatedEmbedded = allBooks.stream()
                        .filter(b -> b.getRating() != null && b.getRating() < contentEmbedMinRating && Boolean.TRUE.equals(b.getContentEmbedded()))
                .count();

        return Result.ok(Map.of(
                "totalBooks", totalBooks,
                "embeddedBooks", embeddedBooks,
                "notEmbeddedBooks", notEmbeddedBooks,
                "totalContentVectors", totalContentVectors,
                "highRatedNotEmbedded", highRatedNotEmbedded,
                "lowRatedEmbedded", lowRatedEmbedded
        ));
    }

    /**
     * 清理低评分书籍的内容向量（释放 Qdrant 内存）
     * @param maxRating 评分上限，低于此值的书籍内容向量将被删除（默认 3.5）
     */
    @PostMapping("/embeddings/cleanup")
    public Result<Map<String, Object>> cleanupEmbeddings(
            @RequestParam(value = "maxRating", defaultValue = "3.5") double maxRating) {
        log.info("开始清理低评分书籍内容向量: maxRating={}", maxRating);
        int cleaned = embeddingService.cleanupLowRatedContentEmbeddings(maxRating);
        return Result.ok(Map.of("cleanedCount", cleaned, "maxRating", maxRating));
    }

    /**
     * 重建高评分书籍的内容向量（评分达标但未存内容向量的书籍）
     * @param minRating 评分下限（默认 3.5）
     */
    @PostMapping("/embeddings/rebuild")
    public Result<Map<String, Object>> rebuildEmbeddings(
            @RequestParam(value = "minRating", defaultValue = "3.5") double minRating) {
        log.info("开始重建高评分书籍内容向量: minRating={}", minRating);
        List<Book> allBooks = bookRepository.findAll();
        int rebuilt = 0;
        int skipped = 0;
        for (Book book : allBooks) {
            if (book.getRating() != null && book.getRating() >= minRating
                    && !Boolean.TRUE.equals(book.getContentEmbedded())) {
                try {
                    bookParserService.generateContentEmbedding(book.getId());
                    book.setContentEmbedded(true);
                    bookRepository.save(book);
                    rebuilt++;
                    if (rebuilt % 10 == 0) {
                        log.info("重建进度: 已重建 {} 本高评分书籍的内容向量", rebuilt);
                    }
                } catch (Exception e) {
                    log.warn("重建内容向量失败: bookId={} - {}", book.getId(), e.getMessage());
                }
            } else {
                skipped++;
            }
        }
        log.info("重建完成: rebuilt={}, skipped={}", rebuilt, skipped);
        return Result.ok(Map.of("rebuiltCount", rebuilt, "skippedCount", skipped, "minRating", minRating));
    }

    /**
     * 强制重建指定书籍的内容向量（不管 contentEmbedded 状态，用当前 embedding 模型重新生成）
     * 用于修复因 embedding 模型更换导致旧向量 score 极低的问题
     */
    @PostMapping("/embeddings/force-rebuild/{bookId}")
    public Result<Map<String, Object>> forceRebuildEmbedding(@PathVariable Long bookId) {
        log.info("强制重建书籍内容向量: bookId={}", bookId);
        try {
            bookParserService.generateContentEmbedding(bookId);
            return Result.ok(Map.of("bookId", bookId, "status", "completed"));
        } catch (Exception e) {
            log.error("强制重建内容向量失败: bookId={} - {}", bookId, e.getMessage());
            return Result.fail("重建失败: " + e.getMessage());
        }
    }

    /**
     * 批量强制重建所有已嵌入但 score 极低的书籍内容向量
     * 检测方式：对每本 contentEmbedded=true 的书做一次低阈值向量搜索，score 过低则重建
     */
    @PostMapping("/embeddings/force-rebuild-stale")
    public Result<Map<String, Object>> forceRebuildStaleEmbeddings(
            @RequestParam(value = "scoreThreshold", defaultValue = "0.1") double scoreThreshold) {
        log.info("开始批量检测并重建低 score 书籍内容向量: scoreThreshold={}", scoreThreshold);
        List<Book> allBooks = bookRepository.findAll();
        int rebuilt = 0;
        int skipped = 0;
        int noContent = 0;

        for (Book book : allBooks) {
            if (!Boolean.TRUE.equals(book.getContentEmbedded())) {
                noContent++;
                continue;
            }
            try {
                // 用一个通用问题测试 score
                var results = embeddingService.searchContent("这本书的主要内容", 1, book.getId());
                if (results.isEmpty() || results.get(0).score() < scoreThreshold) {
                    log.info("检测到低 score 书籍: bookId={}, title={}, score={} — 开始重建",
                            book.getId(), book.getTitle(),
                            results.isEmpty() ? "empty" : String.format("%.4f", results.get(0).score()));
                    bookParserService.generateContentEmbedding(book.getId());
                    rebuilt++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("检测失败: bookId={} - {}", book.getId(), e.getMessage());
            }
        }

        log.info("批量检测重建完成: rebuilt={}, skipped={}, noContent={}", rebuilt, skipped, noContent);
        return Result.ok(Map.of("rebuiltCount", rebuilt, "skippedCount", skipped, "noContentCount", noContent));
    }

    /**
     * 重新评分所有书籍（使用新的评分策略重新生成评分，并自动调整内容向量）
     * @param minRating 内容向量存储的最低评分阈值（默认 3.5）
     */
    @PostMapping("/rerate")
    public Result<Map<String, Object>> rerateAllBooks(
            @RequestParam(value = "minRating", defaultValue = "3.5") double minRating) {
        log.info("开始重新评分所有书籍，内容向量阈值: {}", minRating);
        List<Book> allBooks = bookRepository.findAll();
        int rerated = 0;
        int embeddedNow = 0;
        int removedEmbedding = 0;

        for (Book book : allBooks) {
            try {
                // 强制重新生成评分
                bookParserService.generateRating(book.getId(), true);
                // 重新获取更新后的评分
                Book updated = bookService.getBookById(book.getId());

                if (updated.getRating() != null && updated.getRating() >= minRating) {
                    // 评分达标，存储内容向量
                    if (!Boolean.TRUE.equals(updated.getContentEmbedded())) {
                        bookParserService.generateContentEmbedding(updated.getId());
                        updated.setContentEmbedded(true);
                        bookRepository.save(updated);
                        embeddedNow++;
                    }
                } else {
                    // 评分不达标，删除内容向量
                    if (Boolean.TRUE.equals(updated.getContentEmbedded())) {
                        embeddingService.removeContentEmbedding(updated.getId());
                        updated.setContentEmbedded(false);
                        bookRepository.save(updated);
                        removedEmbedding++;
                    }
                }
                rerated++;
                if (rerated % 10 == 0) {
                    log.info("重新评分进度: {}/{}, 新增嵌入={}, 移除嵌入={}",
                            rerated, allBooks.size(), embeddedNow, removedEmbedding);
                }
                // 避免 API 限流
                Thread.sleep(200);
            } catch (Exception e) {
                log.warn("重新评分失败: bookId={} - {}", book.getId(), e.getMessage());
            }
        }
        log.info("重新评分完成: rerated={}, embeddedNow={}, removedEmbedding={}", rerated, embeddedNow, removedEmbedding);
        return Result.ok(Map.of(
                "reratedCount", rerated,
                "newlyEmbedded", embeddedNow,
                "removedEmbedding", removedEmbedding,
                "minRating", minRating));
    }
}
