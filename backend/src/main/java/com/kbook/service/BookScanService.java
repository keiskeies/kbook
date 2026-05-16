package com.kbook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.ScanStepTimer;
import com.kbook.config.properties.BookStorageProperties;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 图书扫描服务 — 扫描本地目录，自动解析入库（幂等），SSE 流式推送进度
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookScanService {

    private final BookRepository bookRepository;
    private final BookService bookService;
    private final BookParserService bookParserService;
    private final ObjectMapper objectMapper;
    private final BookStorageProperties storageProps;

    /** 扫描进行中标记，防止重复扫描 */
    private volatile boolean scanningInProgress = false;

    /** SSE emitter 是否已完成 */
    private volatile boolean emitterCompleted = false;

    /** 步骤计时器 — 统计各步骤平均耗时 */
    private final ScanStepTimer stepTimer = new ScanStepTimer(
            "文件解析", "数据库保存", "封面处理", "AI数据生成", "内容向量生成"
    );

    /** 当前扫描进度 */
    private int scanTotal = 0;
    private int scanAdded = 0;
    private int scanUpdated = 0;
    private int scanSkipped = 0;
    private int scanFailed = 0;
    private int scanCompleted = 0;
    private volatile String scanCurrentFile = "";
    private final List<Map<String, String>> scanErrors = new ArrayList<>();

    /** 单文件处理结果 */
    enum ScanResultType { ADDED, UPDATED, SKIPPED }

    private static final String EXT_EPUB = ".epub";
    private static final String EXT_PDF = ".pdf";
    private static final String EXT_TXT = ".txt";

    /**
     * SSE 流式扫描 — 逐文件处理，实时推送进度
     * @param skipBeforeId 跳过 ID 小于此值的已有图书（用于断点续扫）
     * @return SseEmitter
     */
    public SseEmitter scanAllWithProgress(Long skipBeforeId) {
        // 防止重复扫描
        if (scanningInProgress) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data("扫描正在进行中，请勿重复操作"));
                emitter.complete();
            } catch (IOException ignored) {}
            return emitter;
        }

        // 重置进度状态
        resetScanProgress();
        scanningInProgress = true;

        SseEmitter emitter = new SseEmitter(600_000L); // 10分钟超时
        emitterCompleted = false;
        emitter.onCompletion(() -> emitterCompleted = true);
        emitter.onTimeout(() -> emitterCompleted = true);
        emitter.onError(e -> emitterCompleted = true);

        try {
            doScanWithProgress(emitter, skipBeforeId);
        } catch (Exception e) {
            log.error("扫描异常", e);
            sendSse(emitter, SseEmitter.event().name("error").data(e.getMessage() != null ? e.getMessage() : "扫描异常"));
            emitterCompleted = true;
            scanningInProgress = false;
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }

        return emitter;
    }

    private void resetScanProgress() {
        scanTotal = 0;
        scanAdded = 0;
        scanUpdated = 0;
        scanSkipped = 0;
        scanFailed = 0;
        scanCompleted = 0;
        scanCurrentFile = "";
        scanErrors.clear();
        stepTimer.reset();
    }

    private void doScanWithProgress(SseEmitter emitter, Long skipBeforeId) {
        log.info("开始扫描图书目录... (skipBeforeId={})", skipBeforeId);
        long startTime = System.currentTimeMillis();

        // 先收集所有待处理文件
        Map<String, String> pathFormatMap = new LinkedHashMap<>();
        pathFormatMap.put(storageProps.getBookPaths().getEpub(), "EPUB");
        pathFormatMap.put(storageProps.getBookPaths().getPdf(), "PDF");
        pathFormatMap.put(storageProps.getBookPaths().getTxt(), "TXT");

        List<ScanItem> allFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : pathFormatMap.entrySet()) {
            String dir = entry.getKey();
            String format = entry.getValue();
            Path dirPath = Paths.get(dir);
            if (!Files.isDirectory(dirPath)) {
                log.warn("图书目录不存在: {}", dir);
                continue;
            }
            String extension = switch (format) {
                case "EPUB" -> EXT_EPUB;
                case "PDF" -> EXT_PDF;
                case "TXT" -> EXT_TXT;
                default -> "." + format.toLowerCase();
            };
            try (Stream<Path> paths = Files.walk(dirPath, 1)) {
                paths.filter(p -> !Files.isDirectory(p))
                     .filter(p -> p.toString().toLowerCase().endsWith(extension))
                     .forEach(p -> allFiles.add(new ScanItem(p, format)));
            } catch (IOException e) {
                log.error("扫描目录失败: {} - {}", dir, e.getMessage(), e);
            }
        }

        // 按文件大小从小到大排序，优先录入小文件
        allFiles.sort((a, b) -> {
            try {
                return Long.compare(Files.size(a.path), Files.size(b.path));
            } catch (IOException e) {
                return 0;
            }
        });

        scanTotal = allFiles.size();
        scanCurrentFile = "准备扫描...";

        // 逐文件处理，每处理完一个推送一次进度
        for (ScanItem item : allFiles) {
            if (emitterCompleted) break;

            String fileName = item.path.getFileName().toString();
            int dotIndex = fileName.lastIndexOf('.');
            String title = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
            scanCurrentFile = title + "." + item.format.toLowerCase();

            try {
                ScanResultType result = processSingleFile(item, skipBeforeId);
                switch (result) {
                    case ADDED -> scanAdded++;
                    case UPDATED -> scanUpdated++;
                    case SKIPPED -> scanSkipped++;
                }
            } catch (Exception e) {
                log.error("处理文件失败: {} - {}", fileName, e.getMessage(), e);
                scanFailed++;
                String reason = e.getMessage() != null ? e.getMessage() : "未知错误";
                if (reason.length() > 200) reason = reason.substring(0, 200) + "...";
                scanErrors.add(Map.of("file", fileName, "reason", reason));
            }

            scanCompleted++;

            // 打印各步骤平均耗时
            stepTimer.logAverages();

            // 推送进度
            sendSse(emitter, SseEmitter.event().name("progress").data(toJson(
                    Map.of("current", scanCompleted, "total", scanTotal,
                           "added", scanAdded, "updated", scanUpdated,
                           "skipped", scanSkipped, "failed", scanFailed,
                           "errors", new ArrayList<>(scanErrors),
                           "currentFile", scanCurrentFile,
                           "status", "scanning"))));
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("扫描完成: 新增={}, 更新={}, 跳过={}, 失败={}, 耗时={}ms", scanAdded, scanUpdated, scanSkipped, scanFailed, elapsed);

        // 发送最终完成事件
        sendSse(emitter, SseEmitter.event().name("done").data(toJson(
                Map.of("added", scanAdded, "updated", scanUpdated, "skipped", scanSkipped,
                       "failed", scanFailed, "errors", new ArrayList<>(scanErrors), "elapsed", elapsed))));
        completeSse(emitter);

        // 扫描真正完成后才重置进行中标记
        scanningInProgress = false;
    }

    /**
     * 处理单个文件，返回结果类型
     */
    private ScanResultType processSingleFile(ScanItem item, Long skipBeforeId) throws Exception {
        Path filePath = item.path;
        String format = item.format;
        String fileName = filePath.getFileName().toString();
        String title = extractTitleFromFilename(fileName);

        String fileUrl = filePath.toAbsolutePath().toString();
        Optional<Book> existing = bookRepository.findByFileUrl(fileUrl);

        if (existing.isPresent()) {
            Book book = existing.get();
            // 跳过 ID 小于指定值的已有图书（断点续扫）
            if (skipBeforeId != null && book.getId() < skipBeforeId) {
                log.debug("跳过已处理图书: bookId={}, title={}", book.getId(), title);
                return ScanResultType.SKIPPED;
            }
            return handleExistingBook(book, filePath, title);
        } else {
            return handleNewBook(filePath, format, title);
        }
    }

    /**
     * 处理已存在的图书 — 完整覆盖更新
     */
    private ScanResultType handleExistingBook(Book book, Path filePath, String title) throws Exception {
        log.info("更新图书: bookId={}, title={}", book.getId(), title);

        // 解析并更新元数据
        stepTimer.start("文件解析");
        book.setFileSize(Files.size(filePath));
        bookParserService.parseAndFill(book, filePath);
        stepTimer.end("文件解析");

        stepTimer.start("封面处理");
        bookParserService.finalizeCover(book);
        stepTimer.end("封面处理");

        stepTimer.start("数据库保存");
        bookService.updateBook(book.getId(), book);
        stepTimer.end("数据库保存");

        // AI数据生成 和 内容向量生成 并行执行
        if (book.getRagContent() != null && !book.getRagContent().isBlank()) {
            stepTimer.start("AI数据生成+内容向量生成(并行)");
            CompletableFuture<Void> aiDataFuture = CompletableFuture.runAsync(() -> bookParserService.generateAllAiData(book.getId(), true));
            CompletableFuture<Void> contentEmbedFuture = CompletableFuture.runAsync(() -> bookParserService.generateContentEmbedding(book.getId(), book.getRagContent()));
            try {
                CompletableFuture.allOf(aiDataFuture, contentEmbedFuture).join();
            } catch (Exception e) {
                log.warn("并行任务异常: bookId={} - {}", book.getId(), e.getMessage());
            }
            stepTimer.end("AI数据生成+内容向量生成(并行)");
        } else {
            stepTimer.start("AI数据生成");
            bookParserService.generateAllAiData(book.getId(), true);
            stepTimer.end("AI数据生成");

            stepTimer.start("内容向量生成");
            bookParserService.generateContentEmbedding(book.getId(), book.getRagContent());
            stepTimer.end("内容向量生成");
        }

        // 标记内容向量已嵌入
        book.setContentEmbedded(true);
        // 防止覆盖AI生成的数据，清空这些字段（updateBook会跳过null字段）
        book.setDescription(null);
        book.setFormatTags(null);
        book.setRating(null);
        book.setRelevanceScores(null);
        stepTimer.start("数据库保存");
        bookService.updateBook(book.getId(), book);
        stepTimer.end("数据库保存");

        return ScanResultType.UPDATED;
    }

    /**
     * 处理新图书 — 完整插入
     */
    private ScanResultType handleNewBook(Path filePath, String format, String title) throws Exception {
        Book newBook = Book.builder()
                .title(title)
                .format(format)
                .fileUrl(filePath.toAbsolutePath().toString())
                .fileSize(Files.size(filePath))
                .build();

        stepTimer.start("文件解析");
        bookParserService.parseAndFill(newBook, filePath);
        stepTimer.end("文件解析");

        stepTimer.start("数据库保存");
        Book saved = bookService.createBook(newBook);
        stepTimer.end("数据库保存");

        stepTimer.start("封面处理");
        bookParserService.finalizeCover(saved);
        stepTimer.end("封面处理");

        stepTimer.start("数据库保存");
        bookService.updateBook(saved.getId(), saved);
        stepTimer.end("数据库保存");

        // AI数据生成 和 内容向量生成 并行执行
        if (newBook.getRagContent() != null && !newBook.getRagContent().isBlank()) {
            stepTimer.start("AI数据生成+内容向量生成(并行)");
            CompletableFuture<Void> aiDataFuture = CompletableFuture.runAsync(() -> bookParserService.generateAllAiData(saved.getId(), true));
            CompletableFuture<Void> contentEmbedFuture = CompletableFuture.runAsync(() -> bookParserService.generateContentEmbedding(saved.getId(), newBook.getRagContent()));
            try {
                CompletableFuture.allOf(aiDataFuture, contentEmbedFuture).join();
            } catch (Exception e) {
                log.warn("并行任务异常: bookId={} - {}", saved.getId(), e.getMessage());
            }
            stepTimer.end("AI数据生成+内容向量生成(并行)");
        } else {
            stepTimer.start("AI数据生成");
            bookParserService.generateAllAiData(saved.getId(), true);
            stepTimer.end("AI数据生成");

            stepTimer.start("内容向量生成");
            bookParserService.generateContentEmbedding(saved.getId(), newBook.getRagContent());
            stepTimer.end("内容向量生成");
        }

        // 标记内容向量已嵌入
        saved.setContentEmbedded(true);
        // 防止覆盖AI生成的数据，清空这些字段（updateBook会跳过null字段）
        saved.setDescription(null);
        saved.setFormatTags(null);
        saved.setRating(null);
        saved.setRelevanceScores(null);
        stepTimer.start("数据库保存");
        bookService.updateBook(saved.getId(), saved);
        stepTimer.end("数据库保存");

        log.info("新增图书: {} [{}]", title, format);
        return ScanResultType.ADDED;
    }

    /**
     * 从文件名中提取标题
     */
    private String extractTitleFromFilename(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /** 扫描是否进行中 */
    public boolean isScanning() {
        return scanningInProgress;
    }

    /** 获取当前扫描进度快照 */
    public Map<String, Object> getScanProgress() {
        return Map.of(
            "scanning", scanningInProgress,
            "current", scanCompleted,
            "total", scanTotal,
            "added", scanAdded,
            "updated", scanUpdated,
            "skipped", scanSkipped,
            "failed", scanFailed,
            "errors", new ArrayList<>(scanErrors),
            "currentFile", scanCurrentFile
        );
    }

    /** 重置扫描状态（异常恢复用） */
    public void resetScanState() {
        scanningInProgress = false;
        emitterCompleted = true;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 发送 SSE 事件，emitter 关闭后自动跳过 */
    private void sendSse(SseEmitter emitter, Object data) {
        if (emitterCompleted) return;
        try {
            emitter.send(data);
        } catch (Exception e) {
            log.warn("SSE 发送失败（emitter 可能已关闭）: {}", e.getMessage());
            emitterCompleted = true;
        }
    }

    /** 完成 emitter */
    private void completeSse(SseEmitter emitter) {
        emitterCompleted = true;
        try {
            emitter.complete();
        } catch (Exception ignored) {}
    }

    private record ScanItem(Path path, String format) {}
}
