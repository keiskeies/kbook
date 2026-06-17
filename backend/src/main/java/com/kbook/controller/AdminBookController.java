package com.kbook.controller;

import com.kbook.common.api.Result;
import com.kbook.common.util.CommonUtils;
import com.kbook.dto.book.BookProjection;
import com.kbook.dto.user.UpdateTagsRequest;
import com.kbook.entity.AiConversation;
import com.kbook.entity.AiSession;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.ai.BookAdminChatService;
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
 * 管理员图书管理控制器 — 扫描、上传、图书 CRUD、AI 对话
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/books")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "图书管理")
public class AdminBookController extends BaseController {

    private final BookScanService bookScanService;
    private final BookService bookService;
    private final BookRepository bookRepository;
    private final EmbeddingService embeddingService;
    private final BookSearchService bookSearchService;
    private final BookStorageProperties storageProps;
    private final BookAdminChatService adminChatService;

    // ==================== 扫描图书 ====================

    @Operation(summary = "扫描图书")
    @GetMapping(value = "/scan", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter scanBooks(@RequestParam(value = "skipBeforeId", required = false) Long skipBeforeId) {
        return bookScanService.scanAllWithProgress(skipBeforeId);
    }

    @Operation(summary = "获取扫描状态")
    @GetMapping("/scan/status")
    public Result<Map<String, Object>> scanStatus() {
        return Result.ok(bookScanService.getScanProgress());
    }

    @Operation(summary = "重置扫描状态")
    @PostMapping("/scan/reset")
    public Result<Void> resetScanStatus() {
        bookScanService.resetScanState();
        return Result.ok(null);
    }

    // ==================== 上传图书 ====================

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
            bookService.updateBookAll(book.getId(), book);

            log.info("上传图书成功: {} [{}]", title, extension);
            return Result.ok(book);

        } catch (Exception e) {
            log.error("上传图书失败", e);
            return Result.fail("上传失败: " + e.getMessage());
        }
    }

    // ==================== 封面 ====================

//    @Operation(summary = "获取封面图片")
    @GetMapping(value = "/cover/{filename:.+}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<Resource> getCover(@PathVariable String filename) {
        Path coverDir = Paths.get(storageProps.getCoverPath());
        Path imagePath = CommonUtils.safeResolvePath(coverDir, filename);

        if (imagePath == null || !Files.exists(imagePath)) {
            return ResponseEntity.notFound().build();
        }

        return CommonUtils.buildImageResponse(imagePath, filename);
    }

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

    // ==================== 图书 CRUD ====================

    @Operation(summary = "更新图书书名")
    @PutMapping("/{id}/title")
    public Result<BookProjection> updateBookTitle(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return Result.fail("书名不能为空");
        }
        bookService.updateBook(id, Book.builder().title(title.trim()).build());
        return Result.ok(bookService.getBookProjectionById(id));
    }

    @Operation(summary = "更新图书作者")
    @PutMapping("/{id}/author")
    public Result<BookProjection> updateBookAuthor(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String author = body.get("author");
        bookService.updateBook(id, Book.builder().author(author != null ? author.trim() : null).build());
        return Result.ok(bookService.getBookProjectionById(id));
    }

    @Operation(summary = "更新图书简介")
    @PutMapping("/{id}/description")
    public Result<BookProjection> updateBookDescription(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String description = body.get("description");
        bookService.updateDescription(id, description != null ? description.trim() : null);
        return Result.ok(bookService.getBookProjectionById(id));
    }

    @Operation(summary = "更新格式标签")
    @PutMapping("/{id}/tags")
    public Result<Book> updateFormatTags(@PathVariable Long id, @RequestBody UpdateTagsRequest req) {
        return Result.ok(bookService.updateFormatTags(id, req.getTags()));
    }

    // ==================== 内容向量管理 ====================

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

    @Operation(summary = "重建ES索引(非流式)")
    @PostMapping("/reindex")
    public Result<Long> rebuildIndex() {
        return Result.ok(bookSearchService.rebuildIndex());
    }

    @Operation(summary = "重建ES索引(流式)")
    @GetMapping(value = "/es/reindex", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter rebuildEsIndex() {
        SseEmitter emitter = new SseEmitter(600_000L);

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

    // ==================== AI 管理员对话 ====================

    @Operation(summary = "创建AI管理员会话")
    @PostMapping("/ai/sessions")
    public Result<Map<String, String>> createSession() {
        Long userId = extractUserId();
        String sessionId = adminChatService.createSession(userId);
        return Result.ok(Map.of("sessionId", sessionId));
    }

    @Operation(summary = "AI管理员流式对话")
    @PostMapping(value = "/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody Map<String, String> body) {
        Long userId = extractUserId();
        String sessionId = body.get("sessionId");
        String message = body.get("message");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = adminChatService.createSession(userId);
        }
        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new IllegalArgumentException("消息不能为空"));
            return emitter;
        }

        return adminChatService.streamChat(userId, sessionId, message);
    }

    @Operation(summary = "AI管理员非流式对话")
    @PostMapping("/ai/chat")
    public Result<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        Long userId = extractUserId();
        String sessionId = body.get("sessionId");
        String message = body.get("message");

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = adminChatService.createSession(userId);
        }
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }

        String response = adminChatService.chat(userId, sessionId, message);
        return Result.ok(Map.of("sessionId", sessionId, "response", response));
    }

    @Operation(summary = "获取AI管理员对话历史")
    @GetMapping("/ai/history")
    public Result<List<AiConversation>> getHistory(@RequestParam String sessionId) {
        Long userId = extractUserId();
        return Result.ok(adminChatService.getHistory(userId, sessionId));
    }

    @Operation(summary = "获取AI管理员会话列表")
    @GetMapping("/ai/sessions")
    public Result<List<AiSession>> getSessions() {
        Long userId = extractUserId();
        return Result.ok(adminChatService.getSessions(userId));
    }

    @Operation(summary = "删除AI管理员会话")
    @DeleteMapping("/ai/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = extractUserId();
        adminChatService.deleteSession(userId, sessionId);
        return Result.ok();
    }
}
