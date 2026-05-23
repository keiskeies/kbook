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
    private final MatchScoreCacheService matchScoreCacheService;

    /**
     * 扫描进行中标记，防止重复扫描
     */
    private volatile boolean scanningInProgress = false;

    /**
     * SSE emitter 是否已完成
     */
    private volatile boolean emitterCompleted = false;

    /**
     * 步骤计时器 — 统计各步骤平均耗时
     */
    private final ScanStepTimer stepTimer = new ScanStepTimer(
            "文件解析", "数据库保存", "封面处理", "AI数据生成", "内容向量生成"
    );

    /**
     * 当前扫描进度
     */
    private int scanTotal = 0;
    private int scanAdded = 0;
    private int scanUpdated = 0;
    private int scanSkipped = 0;
    private int scanFailed = 0;
    private int scanCompleted = 0;
    private volatile String scanCurrentFile = "";
    private final List<Map<String, String>> scanErrors = new ArrayList<>();

    /**
     * 单文件处理结果
     */
    enum ScanResultType {ADDED, UPDATED, SKIPPED}

    private static final String EXT_EPUB = ".epub";
    private static final String EXT_PDF = ".pdf";
    private static final String EXT_TXT = ".txt";

    /**
     * SSE 流式扫描 — 逐文件处理，实时推送进度
     *
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
            } catch (IOException ignored) {
            }
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
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
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

        // 按格式优先级排序：EPUB > PDF > TXT，同格式内按文件大小从小到大
        allFiles.sort((a, b) -> {
            // 定义格式优先级：EPUB=0, PDF=1, TXT=2
            int priorityA = getFormatPriority(a.format);
            int priorityB = getFormatPriority(b.format);
            
            // 先按格式优先级排序
            if (priorityA != priorityB) {
                return Integer.compare(priorityA, priorityB);
            }
            
            // 同格式内按文件大小从小到大排序
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
     * 统一处理图书文件（解析/封面/AI/入库/向量），供扫描和上传共用
     *
     * @param filePath 文件路径
     * @param format   格式 (EPUB/PDF/TXT)
     * @param title    书名
     * @return 处理后的 Book 实体
     */
    public Book processBookFile(Path filePath, String format, String title) {
        String fileUrl = filePath.toAbsolutePath().toString();
        Optional<Book> existing = bookRepository.findByFileUrl(fileUrl);
        try {
            if (existing.isPresent()) {
                return processExistingBook(existing.get(), filePath);
            } else {
                return processNewBook(filePath, format, title);
            }
        } catch (Exception e) {
            throw new RuntimeException("处理图书失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理已存在的图书 — 核心逻辑
     */
    private Book processExistingBook(Book book, Path filePath) throws Exception {
        book.setFileSize(Files.size(filePath));
        book.setDescription(null);
        bookParserService.parseAndFill(book, filePath);
        bookParserService.finalizeCover(book);
        bookParserService.generateAllAiData(book);
        bookService.setAiRating(book.getId(), book.getRating());
        bookService.updateBook(book.getId(), book);
        matchScoreCacheService.evictBook(book.getId());
        CompletableFuture.runAsync(() -> bookParserService.generateBookEmbedding(book));
        return book;
    }

    /**
     * 处理新图书 — 核心逻辑
     */
    private Book processNewBook(Path filePath, String format, String title) throws Exception {
        Book newBook = Book.builder()
                .title(title)
                .format(format)
                .fileUrl(filePath.toAbsolutePath().toString())
                .fileSize(Files.size(filePath))
                .build();
        bookParserService.parseAndFill(newBook, filePath);
        Book saved = bookService.createBook(newBook);
        bookParserService.finalizeCover(saved);
        bookParserService.generateAllAiData(saved);
        bookService.setAiRating(saved.getId(), saved.getRating());
        bookService.updateBook(saved.getId(), saved);
        matchScoreCacheService.evictBook(saved.getId());
        CompletableFuture.runAsync(() -> bookParserService.generateBookEmbedding(saved));
        return saved;
    }

    /**
     * 处理单个文件，返回结果类型（扫描流程专用）
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
            processExistingBook(book, filePath);
            return ScanResultType.UPDATED;
        } else {
            processNewBook(filePath, format, title);
            return ScanResultType.ADDED;
        }
    }

    /**
     * 从文件名中提取标题
     */
    private String extractTitleFromFilename(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * 获取格式优先级（用于排序）
     * EPUB=0（最高优先级）, PDF=1, TXT=2（最低优先级）
     *
     * @param format 图书格式（EPUB/PDF/TXT）
     * @return 优先级数值，越小越优先
     */
    private int getFormatPriority(String format) {
        if (format == null) {
            return 999; // 未知格式排最后
        }
        return switch (format.toUpperCase()) {
            case "EPUB" -> 0;
            case "PDF" -> 1;
            case "TXT" -> 2;
            default -> 999; // 其他格式排最后
        };
    }

    /**
     * 扫描是否进行中
     */
    public boolean isScanning() {
        return scanningInProgress;
    }

    /**
     * 获取当前扫描进度快照
     */
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

    /**
     * 重置扫描状态（异常恢复用）
     */
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

    /**
     * 发送 SSE 事件，emitter 关闭后自动跳过
     */
    private void sendSse(SseEmitter emitter, Object data) {
        if (emitterCompleted) return;
        try {
            emitter.send(data);
        } catch (Exception e) {
            log.warn("SSE 发送失败（emitter 可能已关闭）: {}", e.getMessage());
            emitterCompleted = true;
        }
    }

    /**
     * 完成 emitter
     */
    private void completeSse(SseEmitter emitter) {
        emitterCompleted = true;
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    private record ScanItem(Path path, String format) {
    }
}
