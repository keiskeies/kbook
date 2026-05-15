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
    private final EmbeddingService embeddingService;
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
     * 处理已存在的图书
     */
    private ScanResultType handleExistingBook(Book book, Path filePath, String title) throws Exception {
        long fileSize = Files.size(filePath);

        if (storageProps.getScan().isForceUpdate() || !Objects.equals(book.getFileSize(), fileSize)) {
            log.debug("{}图书: {}", storageProps.getScan().isForceUpdate() ? "强制更新" : "文件大小变化", title);
            book.setFileSize(fileSize);

            stepTimer.start("文件解析");
            bookParserService.parseAndFill(book, filePath);
            stepTimer.end("文件解析");

            stepTimer.start("封面处理");
            bookParserService.finalizeCover(book);
            stepTimer.end("封面处理");

            stepTimer.start("数据库保存");
            bookService.updateBook(book.getId(), book);
            stepTimer.end("数据库保存");

            // AI数据生成 和 内容向量生成 并行执行
            boolean preCheckPassed = passesContentEmbedPreCheck(book);
            if (preCheckPassed && book.getRagContent() != null && !book.getRagContent().isBlank()) {
                stepTimer.start("AI数据生成+内容向量生成(并行)");
                CompletableFuture<Void> aiDataFuture = CompletableFuture.runAsync(() -> bookParserService.generateAllAiData(book.getId(), true));
                CompletableFuture<Void> contentEmbedFuture = CompletableFuture.runAsync(() -> bookParserService.generateContentEmbedding(book.getId(), book.getRagContent()));
                try {
                    CompletableFuture.allOf(aiDataFuture, contentEmbedFuture).join();
                } catch (Exception e) {
                    log.warn("并行任务异常: bookId={} - {}", book.getId(), e.getMessage());
                }
                stepTimer.end("AI数据生成+内容向量生成(并行)");

                // 根据评分决定是否保留内容向量
                Book bookWithRating = bookService.getBookById(book.getId());
                if (shouldEmbedContent(bookWithRating)) {
                    bookWithRating.setContentEmbedded(true);
                    stepTimer.start("数据库保存");
                    bookService.updateBook(bookWithRating.getId(), bookWithRating);
                    stepTimer.end("数据库保存");
                } else {
                    embeddingService.removeContentEmbedding(book.getId());
                    log.info("{}，删除已生成的内容向量: bookId={}, title={}",
                            getSkipEmbedReason(bookWithRating), book.getId(), title);
                }
            } else {
                stepTimer.start("AI数据生成");
                bookParserService.generateAllAiData(book.getId(), true);
                stepTimer.end("AI数据生成");

                Book bookWithRating = bookService.getBookById(book.getId());
                if (shouldEmbedContent(bookWithRating)) {
                    stepTimer.start("内容向量生成");
                    bookParserService.generateContentEmbedding(book.getId(), book.getRagContent());
                    stepTimer.end("内容向量生成");
                    bookWithRating.setContentEmbedded(true);
                    stepTimer.start("数据库保存");
                    bookService.updateBook(bookWithRating.getId(), bookWithRating);
                    stepTimer.end("数据库保存");
                } else {
                    if (Boolean.TRUE.equals(book.getContentEmbedded())) {
                        embeddingService.removeContentEmbedding(book.getId());
                        book.setContentEmbedded(false);
                        stepTimer.start("数据库保存");
                        bookService.updateBook(book.getId(), book);
                        stepTimer.end("数据库保存");
                    }
                    log.info("{}，跳过内容向量存储: bookId={}, title={}",
                            getSkipEmbedReason(bookWithRating), book.getId(), title);
                }
            }
            return ScanResultType.UPDATED;
        } else {
            // 补生成缺失的 AI 数据（标签/评分/相关度）
            boolean needsAiData = needsAiDataGeneration(book);
            if (needsAiData) {
                bookParserService.generateAllAiData(book.getId());
            }

            // 补提取缺失的目录/章节摘要/作者，或始终重新生成简介（AI更完整）
            log.debug("补提取旧书元数据: bookId={}, title={}", book.getId(), title);
            bookParserService.parseAndFill(book, filePath);
            bookService.updateBook(book.getId(), book);

            // 补生成缺失的向量数据（旧数据可能从未进过向量库）
            boolean needsBookEmbedding = !embeddingService.hasBookEmbedding(book.getId());
            if (needsBookEmbedding) {
                log.debug("补生成旧书元数据向量: bookId={}, title={}", book.getId(), title);
                bookParserService.generateBookEmbedding(book.getId());
            }
            // 内容向量：评分达标的才补生成，不达标的如有则删除
            boolean needsContentEmbedding = !embeddingService.hasContentEmbedding(book.getId());
            if (needsContentEmbedding && shouldEmbedContent(book)) {
                log.debug("补生成旧书内容向量: bookId={}, title={}", book.getId(), title);
                bookParserService.generateContentEmbedding(book.getId());
                book.setContentEmbedded(true);
                bookService.updateBook(book.getId(), book);
            } else if (!shouldEmbedContent(book) && Boolean.TRUE.equals(book.getContentEmbedded())) {
                // 评分不达标但标记了已嵌入，删除内容向量
                embeddingService.removeContentEmbedding(book.getId());
                book.setContentEmbedded(false);
                bookService.updateBook(book.getId(), book);
                log.info("{}，删除内容向量: bookId={}, title={}",
                        getSkipEmbedReason(book), book.getId(), title);
            }
            return ScanResultType.SKIPPED;
        }
    }

    /**
     * 处理新图书
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
        if (saved.getCoverUrl() != null) {
            String oldCoverUrl = saved.getCoverUrl();
            bookParserService.finalizeCover(saved);
            if (!saved.getCoverUrl().equals(oldCoverUrl)) {
                stepTimer.end("封面处理");
                stepTimer.start("数据库保存");
                bookService.updateBook(saved.getId(), saved);
                stepTimer.end("数据库保存");
            } else {
                stepTimer.end("封面处理");
            }
        } else {
            stepTimer.end("封面处理");
        }

        // AI数据生成 和 内容向量生成 并行执行（乐观策略：预检查通过即并行，完成后按评分决定是否保留内容向量）
        boolean preCheckPassed = passesContentEmbedPreCheck(saved);
        if (preCheckPassed && newBook.getRagContent() != null && !newBook.getRagContent().isBlank()) {
            // 并行执行
            stepTimer.start("AI数据生成+内容向量生成(并行)");
            CompletableFuture<Void> aiDataFuture = CompletableFuture.runAsync(() -> bookParserService.generateAllAiData(saved.getId(), true));
            CompletableFuture<Void> contentEmbedFuture = CompletableFuture.runAsync(() -> bookParserService.generateContentEmbedding(saved.getId(), newBook.getRagContent()));
            try {
                CompletableFuture.allOf(aiDataFuture, contentEmbedFuture).join();
            } catch (Exception e) {
                log.warn("并行任务异常: bookId={} - {}", saved.getId(), e.getMessage());
            }
            stepTimer.end("AI数据生成+内容向量生成(并行)");

            // 根据评分决定是否保留内容向量
            Book bookWithRating = bookService.getBookById(saved.getId());
            if (shouldEmbedContent(bookWithRating)) {
                bookWithRating.setContentEmbedded(true);
                stepTimer.start("数据库保存");
                bookService.updateBook(bookWithRating.getId(), bookWithRating);
                stepTimer.end("数据库保存");
            } else {
                // 评分不达标，删除已生成的内容向量
                log.info("{}，删除已生成的内容向量: bookId={}, title={}",
                        getSkipEmbedReason(bookWithRating), saved.getId(), title);
                embeddingService.removeContentEmbedding(saved.getId());
            }
        } else {
            // 预检查不通过，仅执行 AI 数据生成
            stepTimer.start("AI数据生成");
            bookParserService.generateAllAiData(saved.getId(), true);
            stepTimer.end("AI数据生成");

            Book bookWithRating = bookService.getBookById(saved.getId());
            if (shouldEmbedContent(bookWithRating)) {
                stepTimer.start("内容向量生成");
                bookParserService.generateContentEmbedding(saved.getId(), newBook.getRagContent());
                stepTimer.end("内容向量生成");
                bookWithRating.setContentEmbedded(true);
                stepTimer.start("数据库保存");
                bookService.updateBook(bookWithRating.getId(), bookWithRating);
                stepTimer.end("数据库保存");
            } else {
                log.info("{}，跳过内容向量存储: bookId={}, title={}",
                        getSkipEmbedReason(bookWithRating), saved.getId(), title);
            }
        }
        log.info("新增图书: {} [{}]", title, format);
        return ScanResultType.ADDED;
    }

    /**
     * 检查是否需要生成 AI 数据
     */
    private boolean needsAiDataGeneration(Book book) {
        boolean needsTags = book.getFormatTags() == null || book.getFormatTags().isBlank();
        boolean needsRelevance = book.getRelevanceScores() == null || book.getRelevanceScores().isBlank();
        boolean needsRating = book.getRating() == null || book.getRating() <= 0;
        return needsTags || needsRelevance || needsRating;
    }

    /**
     * 判断书籍是否应该存储内容向量
     * 需同时满足：评分 >= storageProps.getScan().getContentEmbedMinRating() 且文件大小 <= storageProps.getScan().getContentEmbedMaxSizeMb() 且非合集类图书
     */
    private boolean shouldEmbedContent(Book book) {
        if (book.getRating() == null || book.getRating() < storageProps.getScan().getContentEmbedMinRating()) {
            return false;
        }
        return passesContentEmbedPreCheck(book);
    }

    /**
     * 内容向量预检查（不依赖 AI 评分，可在 AI 数据生成前判断）
     * 检查文件大小和合集类限制
     */
    private boolean passesContentEmbedPreCheck(Book book) {
        double maxSizeMb = storageProps.getScan().getContentEmbedMaxSizeMb();
        if (maxSizeMb > 0 && book.getFileSize() != null && book.getFileSize() > maxSizeMb * 1024 * 1024) {
            return false;
        }
        return !isCompilationBook(book);
    }

    /**
     * 判断是否为合集类图书（书名包含合集关键词，或文件超大）
     * 合集类图书通常包含多本独立作品，内容杂糅，不适合存入 RAG 向量库
     */
    private boolean isCompilationBook(Book book) {
        String title = book.getTitle();
        if (title != null && !title.isBlank()) {
            String[] keywords = storageProps.getScan().getCompilationKeywords().split(",");
            for (String keyword : keywords) {
                String kw = keyword.trim();
                if (!kw.isEmpty() && title.contains(kw)) {
                    return true;
                }
            }
        }
        // 文件超大且超过合集阈值时也视为合集（超大文件通常是多本合集）
        return storageProps.getScan().getCompilationMaxSizeMb() > 0 && book.getFileSize() != null
                && book.getFileSize() > storageProps.getScan().getCompilationMaxSizeMb() * 1024 * 1024;
    }

    /**
     * 获取跳过内容向量存储的原因（用于日志）
     */
    private String getSkipEmbedReason(Book book) {
        if (book.getRating() == null || book.getRating() < storageProps.getScan().getContentEmbedMinRating()) {
            return String.format("评分 %.1f < %.1f", book.getRating() != null ? book.getRating() : 0, storageProps.getScan().getContentEmbedMinRating());
        }
        double maxSizeMb = storageProps.getScan().getContentEmbedMaxSizeMb();
        if (maxSizeMb > 0 && book.getFileSize() != null && book.getFileSize() > maxSizeMb * 1024 * 1024) {
            return String.format("文件 %.1fMB > %.0fMB", book.getFileSize() / 1024.0 / 1024.0, maxSizeMb);
        }
        if (isCompilationBook(book)) {
            if (book.getFileSize() != null && storageProps.getScan().getCompilationMaxSizeMb() > 0
                    && book.getFileSize() > storageProps.getScan().getCompilationMaxSizeMb() * 1024 * 1024) {
                return String.format("合集类图书(文件 %.1fMB > %.0fMB)", book.getFileSize() / 1024.0 / 1024.0, storageProps.getScan().getCompilationMaxSizeMb());
            }
            return "合集类图书(书名匹配)";
        }
        return "";
    }

    /**
     * 从文件名中提取标题
     */
    private String extractTitleFromFilename(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * 同步扫描（保留兼容，单线程）
     */
    public Map<String, Integer> scanAll() {
        log.info("开始同步扫描图书目录...");
        long startTime = System.currentTimeMillis();
        int added = 0, updated = 0, skipped = 0;

        Map<String, String> pathFormatMap = new LinkedHashMap<>();
        pathFormatMap.put(storageProps.getBookPaths().getEpub(), "EPUB");
        pathFormatMap.put(storageProps.getBookPaths().getPdf(), "PDF");
        pathFormatMap.put(storageProps.getBookPaths().getTxt(), "TXT");

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
                List<Path> files = paths
                        .filter(p -> !Files.isDirectory(p))
                        .filter(p -> p.toString().toLowerCase().endsWith(extension))
                        .toList();

                for (Path filePath : files) {
                    try {
                        String fileName = filePath.getFileName().toString();
                        String title = extractTitleFromFilename(fileName);

                        String fileUrl = filePath.toAbsolutePath().toString();
                        Optional<Book> existing = bookRepository.findByFileUrl(fileUrl);

                        if (existing.isPresent()) {
                            Book book = existing.get();
                            long fileSize = Files.size(filePath);

                            if (storageProps.getScan().isForceUpdate() || !Objects.equals(book.getFileSize(), fileSize)) {
                                book.setFileSize(fileSize);
                                bookParserService.parseAndFill(book, filePath);
                                bookParserService.finalizeCover(book);
                                bookService.updateBook(book.getId(), book);
                                bookParserService.generateAllAiData(book.getId(), true);
                                // 根据评分决定是否存储内容向量
                                if (shouldEmbedContent(book)) {
                                    bookParserService.generateContentEmbedding(book.getId());
                                    book.setContentEmbedded(true);
                                    bookService.updateBook(book.getId(), book);
                                }
                                updated++;
                            } else {
                                if (needsAiDataGeneration(book)) {
                                    bookParserService.generateAllAiData(book.getId());
                                }
                                bookParserService.parseAndFill(book, filePath);
                                bookService.updateBook(book.getId(), book);
                                if (!embeddingService.hasBookEmbedding(book.getId())) {
                                    bookParserService.generateBookEmbedding(book.getId());
                                }
                                if (!embeddingService.hasContentEmbedding(book.getId()) && shouldEmbedContent(book)) {
                                    bookParserService.generateContentEmbedding(book.getId());
                                    book.setContentEmbedded(true);
                                    bookService.updateBook(book.getId(), book);
                                }
                                skipped++;
                            }
                            continue;
                        }

                        Book newBook = Book.builder()
                                .title(title)
                                .format(format)
                                .fileUrl(fileUrl)
                                .fileSize(Files.size(filePath))
                                .build();

                        bookParserService.parseAndFill(newBook, filePath);
                        Book saved = bookService.createBook(newBook);

                        if (saved.getCoverUrl() != null) {
                            String oldCoverUrl = saved.getCoverUrl();
                            bookParserService.finalizeCover(saved);
                            if (!saved.getCoverUrl().equals(oldCoverUrl)) {
                                bookService.updateBook(saved.getId(), saved);
                            }
                        }

                        bookParserService.generateAllAiData(saved.getId(), true);
                        // 根据评分决定是否存储内容向量
                        Book bookWithRating = bookService.getBookById(saved.getId());
                        if (shouldEmbedContent(bookWithRating)) {
                            bookParserService.generateContentEmbedding(saved.getId());
                            bookWithRating.setContentEmbedded(true);
                            bookService.updateBook(bookWithRating.getId(), bookWithRating);
                        }
                        added++;

                    } catch (Exception e) {
                        log.error("处理文件失败: {} - {}", filePath, e.getMessage(), e);
                        skipped++;
                    }
                }
            } catch (IOException e) {
                log.error("扫描目录失败: {} - {}", dir, e.getMessage(), e);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("扫描完成: 新增={}, 更新={}, 跳过={}, 耗时={}ms", added, updated, skipped, elapsed);
        return Map.of("added", added, "updated", updated, "skipped", skipped);
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
