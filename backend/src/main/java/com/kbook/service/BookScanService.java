package com.kbook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
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

    @Value("${kbook.book-paths.epub}")
    private String epubPath;

    @Value("${kbook.book-paths.pdf}")
    private String pdfPath;

    @Value("${kbook.book-paths.txt}")
    private String txtPath;

    @Value("${kbook.scan.force-update:false}")
    private boolean forceUpdate;

    /** 扫描进行中标记，防止重复扫描 */
    private volatile boolean scanningInProgress = false;

    /** SSE emitter 是否已完成 */
    private volatile boolean emitterCompleted = false;

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
     * @return SseEmitter
     */
    public SseEmitter scanAllWithProgress() {
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
            doScanWithProgress(emitter);
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
    }

    private void doScanWithProgress(SseEmitter emitter) throws IOException {
        log.info("开始扫描图书目录...");
        long startTime = System.currentTimeMillis();

        // 先收集所有待处理文件
        Map<String, String> pathFormatMap = new LinkedHashMap<>();
        pathFormatMap.put(epubPath, "EPUB");
        pathFormatMap.put(pdfPath, "PDF");
        pathFormatMap.put(txtPath, "TXT");

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
                ScanResultType result = processSingleFile(item);
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
    private ScanResultType processSingleFile(ScanItem item) throws Exception {
        Path filePath = item.path;
        String format = item.format;
        String fileName = filePath.getFileName().toString();
        String title = extractTitleFromFilename(fileName);

        String fileUrl = filePath.toAbsolutePath().toString();
        Optional<Book> existing = bookRepository.findByFileUrl(fileUrl);

        if (existing.isPresent()) {
            return handleExistingBook(existing.get(), filePath, title);
        } else {
            return handleNewBook(filePath, format, title);
        }
    }

    /**
     * 处理已存在的图书
     */
    private ScanResultType handleExistingBook(Book book, Path filePath, String title) throws Exception {
        long fileSize = Files.size(filePath);

        if (forceUpdate || !Objects.equals(book.getFileSize(), fileSize)) {
            log.debug("{}图书: {}", forceUpdate ? "强制更新" : "文件大小变化", title);
            book.setFileSize(fileSize);
            bookParserService.parseAndFill(book, filePath);
            bookParserService.finalizeCover(book);
            bookService.updateBook(book.getId(), book);
            bookParserService.generateAllAiData(book.getId(), true);
            bookParserService.generateContentEmbedding(book.getId());
            return ScanResultType.UPDATED;
        } else {
            // 补生成缺失的 AI 数据（标签/评分/相关度）
            boolean needsAiData = needsAiDataGeneration(book);
            if (needsAiData) {
                bookParserService.generateAllAiData(book.getId());
            }

            // 补提取缺失的目录/章节摘要/作者/简介（旧数据可能从未提取过）
            boolean needsMetadata = (book.getToc() == null || book.getToc().isBlank())
                    || (book.getChapterSummary() == null || book.getChapterSummary().isBlank())
                    || (book.getAuthor() == null || book.getAuthor().isBlank())
                    || (book.getDescription() == null || book.getDescription().isBlank());
            if (needsMetadata) {
                log.debug("补提取旧书元数据: bookId={}, title={}", book.getId(), title);
                bookParserService.parseAndFill(book, filePath);
                bookService.updateBook(book.getId(), book);
            }

            // 补生成缺失的向量数据（旧数据可能从未进过向量库）
            boolean needsBookEmbedding = !embeddingService.hasBookEmbedding(book.getId());
            if (needsBookEmbedding) {
                log.debug("补生成旧书元数据向量: bookId={}, title={}", book.getId(), title);
                bookParserService.generateBookEmbedding(book.getId());
            }
            boolean needsContentEmbedding = !embeddingService.hasContentEmbedding(book.getId());
            if (needsContentEmbedding) {
                log.debug("补生成旧书内容向量: bookId={}, title={}", book.getId(), title);
                bookParserService.generateContentEmbedding(book.getId());
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
        bookParserService.generateContentEmbedding(saved.getId());
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
        pathFormatMap.put(epubPath, "EPUB");
        pathFormatMap.put(pdfPath, "PDF");
        pathFormatMap.put(txtPath, "TXT");

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

                            if (forceUpdate || !Objects.equals(book.getFileSize(), fileSize)) {
                                book.setFileSize(fileSize);
                                bookParserService.parseAndFill(book, filePath);
                                bookParserService.finalizeCover(book);
                                bookService.updateBook(book.getId(), book);
                                bookParserService.generateAllAiData(book.getId(), true);
                                bookParserService.generateContentEmbedding(book.getId());
                                updated++;
                            } else {
                                if (needsAiDataGeneration(book)) {
                                    bookParserService.generateAllAiData(book.getId());
                                }
                                boolean needsMetadata = (book.getToc() == null || book.getToc().isBlank())
                                        || (book.getChapterSummary() == null || book.getChapterSummary().isBlank())
                                        || (book.getAuthor() == null || book.getAuthor().isBlank())
                                        || (book.getDescription() == null || book.getDescription().isBlank());
                                if (needsMetadata) {
                                    bookParserService.parseAndFill(book, filePath);
                                    bookService.updateBook(book.getId(), book);
                                }
                                if (!embeddingService.hasBookEmbedding(book.getId())) {
                                    bookParserService.generateBookEmbedding(book.getId());
                                }
                                if (!embeddingService.hasContentEmbedding(book.getId())) {
                                    bookParserService.generateContentEmbedding(book.getId());
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
                        bookParserService.generateContentEmbedding(saved.getId());
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
